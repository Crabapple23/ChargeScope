package com.chargescope.app

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

/**
 * Per-session GZIP-compressed CSV recorder (5x-10x smaller on disk than raw CSV).
 * Each recording session writes an isolated GZIP file with syncFlush so a hard kill
 * only truncates the tail of that session without corrupting subsequent sessions.
 * Export merges all sessions (and any legacy single-file logs) into one plain CSV stream.
 */
object Recorder {
    private const val HEADER =
        "wall_ms,elapsed_ms,level_pct,status,plugged,voltage_mv,temp_decic,current_ua," +
            "current_avg_ua,charge_counter_uah,battery_power_mw_est,input_voltage_uv,input_current_ua,usb_type"
    private val LEGACY_NAMES = arrayOf("records.old.csv", "records.csv", "records.old.csv.gz", "records.csv.gz")
    private var appCtx: Context? = null
    private var currentFile: File? = null
    private var writer: BufferedWriter? = null
    private val lineBuf = StringBuilder(160)
    @Volatile var rows: Long = 0
        private set
    @Volatile var approxBytes: Long = 0
        private set

    private fun sessionsDir(ctx: Context) = File(ctx.applicationContext.filesDir, "sessions")

    @Synchronized
    fun init(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        val bytes = diskBytes(app)
        approxBytes = bytes
        if (bytes == 0L) {
            rows = 0L
            Prefs.setRecordedRows(app, 0L)
        } else {
            val saved = Prefs.recordedRows(app)
            if (saved > rows) rows = saved
        }
        Sampler.syncRecordedRows(rows)
    }

    @Synchronized
    fun open(ctx: Context) {
        if (writer != null) return
        val app = ctx.applicationContext
        appCtx = app
        val dir = sessionsDir(app)
        dir.mkdirs()

        // Trim oldest files when total recorded storage reaches the configured cap.
        val cap = Prefs.long(app, Prefs.FILE_MB, 8L) * 1024L * 1024L
        var deletedAny = false
        if (diskBytes(app) >= cap) {
            val candidates = ArrayList<File>()
            for (name in LEGACY_NAMES) {
                val f = File(app.filesDir, name)
                if (f.exists()) candidates += f
            }
            dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }?.let { candidates.addAll(it) }
            for (old in candidates) {
                if (diskBytes(app) < cap) break
                if (old.delete()) deletedAny = true
            }
        }
        if (deletedAny) {
            rows = recountRows(app)
            Prefs.setRecordedRows(app, rows)
            Sampler.syncRecordedRows(rows)
        }

        val f = File(dir, "rec_${System.currentTimeMillis()}.csv.gz")
        val gz = GZIPOutputStream(FileOutputStream(f, false), 4096, true)
        val w = BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8), 4096)
        w.write(HEADER)
        w.newLine()
        currentFile = f
        writer = w
        approxBytes = diskBytes(app)
    }

    @Synchronized
    fun append(ctx: Context, s: Snapshot, powerMw: Double?, inp: InputInfo) {
        val w = writer ?: return
        val sb = lineBuf
        sb.setLength(0)
        fun f(v: Any?) { if (v != null) sb.append(v); sb.append(',') }
        f(s.wallMs); f(s.elapsedMs); f(s.levelPct); f(s.status); f(s.plugged); f(s.voltageMv)
        f(s.tempDeci); f(s.currentUa); f(s.currentAvgUa); f(s.chargeCounterUah)
        if (powerMw != null) {
            val tenths = (powerMw * 10.0).roundToLong()
            sb.append(tenths / 10L).append('.').append(kotlin.math.abs(tenths % 10L))
        }
        sb.append(',')
        f(inp.voltageUv); f(inp.currentUa)
        if (inp.usbType != null) sb.append(inp.usbType)
        w.write(sb.toString()); w.newLine()
        rows++
        if (rows % 10L == 0L) {
            w.flush()
            approxBytes = diskBytes(ctx)
            Prefs.setRecordedRows(ctx, rows)
        }
    }

    @Synchronized fun flush() { try { writer?.flush() } catch (_: Exception) {} }

    @Synchronized
    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
        currentFile = null
        appCtx?.let {
            Prefs.setRecordedRows(it, rows)
            approxBytes = diskBytes(it)
        }
    }

    @Synchronized
    fun exportTo(ctx: Context, out: OutputStream): Boolean {
        flush()
        return mergeCsvFiles(collectSourceFiles(ctx.applicationContext), out)
    }

    private fun collectSourceFiles(app: Context): List<File> {
        val sources = ArrayList<File>()
        for (name in LEGACY_NAMES) {
            val f = File(app.filesDir, name)
            if (f.exists() && f.length() > 0L) sources += f
        }
        sessionsDir(app).listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedBy { it.name }
            ?.let { sources.addAll(it) }
        return sources
    }

    internal fun mergeCsvFiles(sources: List<File>, out: OutputStream): Boolean {
        val existing = sources.filter { it.exists() && it.length() > 0L }
        if (existing.isEmpty()) return false

        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 8192)
        w.write(HEADER)
        w.newLine()

        fun copyLines(reader: BufferedReader) {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank() || line == HEADER) continue
                    w.write(line)
                    w.newLine()
                }
            } catch (_: EOFException) {
                // Tail block truncated by a prior hard kill; previously flushed rows are intact.
            } catch (_: IOException) {
                // Corrupted segment; skip remainder of this file without blocking later sessions.
            }
        }

        for (f in existing) {
            try {
                if (f.name.endsWith(".gz")) {
                    BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(f)), Charsets.UTF_8)).use(::copyLines)
                } else {
                    BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8)).use(::copyLines)
                }
            } catch (_: Exception) {
                // Header-level corruption in one segment; continue with remaining sessions.
            }
        }
        w.flush()
        return true
    }

    private fun recountRows(app: Context): Long = countCsvRows(collectSourceFiles(app))

    internal fun countCsvRows(sources: List<File>): Long {
        var total = 0L
        for (f in sources) {
            if (!f.exists() || f.length() <= 0L) continue
            try {
                val reader = if (f.name.endsWith(".gz")) {
                    BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(f)), Charsets.UTF_8))
                } else {
                    BufferedReader(InputStreamReader(FileInputStream(f), Charsets.UTF_8))
                }
                reader.use { r ->
                    try {
                        while (true) {
                            val line = r.readLine() ?: break
                            if (line.isBlank() || line == HEADER) continue
                            total++
                        }
                    } catch (_: IOException) {
                    }
                }
            } catch (_: Exception) {
            }
        }
        return total
    }

    fun diskBytes(ctx: Context): Long {
        val dir = ctx.applicationContext.filesDir
        var total = 0L
        for (name in LEGACY_NAMES) {
            val f = File(dir, name)
            if (f.exists()) total += f.length()
        }
        sessionsDir(ctx).listFiles()?.forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    @Synchronized
    fun clear(ctx: Context) {
        close()
        val app = ctx.applicationContext
        val dir = app.filesDir
        for (name in LEGACY_NAMES) {
            File(dir, name).delete()
        }
        sessionsDir(app).listFiles()?.forEach { it.delete() }
        rows = 0
        approxBytes = 0
        Prefs.setRecordedRows(app, 0L)
        Sampler.syncRecordedRows(0L)
    }
}
