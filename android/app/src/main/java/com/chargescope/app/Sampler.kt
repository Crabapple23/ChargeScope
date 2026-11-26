package com.chargescope.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import kotlin.math.abs

object Prefs {
    const val TEMP = "max_temp_c"
    const val MINUTES = "max_minutes"
    const val SOC = "stop_soc"
    const val FILE_MB = "max_file_mb"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("cs", Context.MODE_PRIVATE)
    fun long(c: Context, k: String, d: Long): Long = sp(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) = sp(c).edit().putLong(k, v).apply()
    fun intervalMs(c: Context): Long = sp(c).getLong("interval", 2000L)
    fun setIntervalMs(c: Context, v: Long) = sp(c).edit().putLong("interval", v).apply()

    /** Privileged channel: 0 = off, 1 = root (Magisk/KernelSU/APatch), 2 = Shizuku. */
    fun privMode(c: Context): Int {
        val s = sp(c)
        return if (s.contains("priv")) s.getInt("priv", 0) else if (s.getBoolean("root", false)) 1 else 0
    }
    fun setPrivMode(c: Context, v: Int) = sp(c).edit().putInt("priv", v).apply()

    fun autoRecord(c: Context): Boolean = sp(c).getBoolean("auto", false)
    fun setAutoRecord(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()
}

/** Append-only CSV with buffered writes (flush every few rows) and a size cap. */
object Recorder {
    private const val HEADER =
        "wall_ms,elapsed_ms,level_pct,status,plugged,voltage_mv,temp_decic,current_ua," +
            "current_avg_ua,charge_counter_uah,battery_power_mw_est,input_voltage_uv,input_current_ua,usb_type"
    private var writer: BufferedWriter? = null
    @Volatile var rows: Long = 0
        private set

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "records.csv")

    @Synchronized
    fun open(ctx: Context) {
        if (writer != null) return
        val f = file(ctx)
        // Rotate an oversized file left from earlier sessions.
        if (f.length() > Prefs.long(ctx, Prefs.FILE_MB, 8L) * 1024 * 1024) {
            val old = File(f.parentFile, "records.old.csv")
            old.delete()
            f.renameTo(old)
        }
        val fresh = !f.exists() || f.length() == 0L
        val w = BufferedWriter(FileWriter(f, true))
        if (fresh) { w.write(HEADER); w.newLine() }
        writer = w
    }

    @Synchronized
    fun append(s: Snapshot, powerMw: Double?, inp: InputInfo) {
        val w = writer ?: return
        val sb = StringBuilder(128)
        fun f(v: Any?) { sb.append(v?.toString() ?: ""); sb.append(',') }
        f(s.wallMs); f(s.elapsedMs); f(s.levelPct); f(s.status); f(s.plugged); f(s.voltageMv)
        f(s.tempDeci); f(s.currentUa); f(s.currentAvgUa); f(s.chargeCounterUah)
        f(powerMw?.let { "%.1f".format(it) }); f(inp.voltageUv); f(inp.currentUa)
        sb.append(inp.usbType ?: "")
        w.write(sb.toString()); w.newLine()
        rows++
        if (rows % 5L == 0L) w.flush()
    }

    @Synchronized fun flush() { writer?.flush() }

    @Synchronized
    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }

    @Synchronized
    fun clear(ctx: Context) {
        close()
        file(ctx).delete()
        rows = 0
    }
}

object Sampler {
    private const val WINDOW = 300
    private const val SERIES = 120
    private const val PRIV_EVERY = 3

    private val mutex = Mutex()
    private val ring = ArrayDeque<Snapshot>()
    private val batSeries = ArrayDeque<Float>()
    private val inSeries = ArrayDeque<Float>()
    private val tsSeries = ArrayDeque<Long>()

    private var tick = 0
    private var privFails = 0
    private var privRetryAt = 0L
    private var privGranted = false
    private var lastNodes: List<RootNode> = emptyList()
    private var privError: String? = null
    private var lastLoggedError: String? = null
    @Volatile private var recording = false

    // Per-charging-session archive bookkeeping.
    private var wasPlugged = false
    private var archivedKey = ""
    private var archivedEntry: ArchiveEntry? = null
    private var peakVin: Double? = null
    private var peakInW: Double? = null
    private var peakBatW: Double? = null

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setRecording(on: Boolean) {
        recording = on
        _state.value = _state.value.copy(
            recording = on,
            recordedRows = Recorder.rows,
            notice = if (on) null else _state.value.notice,
        )
        AppLog.i(if (on) "开始记录" else "停止记录（共写入 ${Recorder.rows} 行）")
    }

    fun setNotice(text: String?) {
        _state.value = _state.value.copy(notice = text)
        if (text != null) AppLog.i("提示：$text")
    }

    private suspend fun refreshPrivileged(app: Context) {
        val mode = Prefs.privMode(app)
        if (mode == 0) {
            lastNodes = emptyList(); privError = null; tick = 0
            privFails = 0; privRetryAt = 0L; privGranted = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Back off after a failure so a denied/slow channel never spams the manager's prompt.
        if (now < privRetryAt || tick++ % PRIV_EVERY != 0) return
        val r = if (mode == 1) RootScanner.scan(if (privGranted) 4 else 15) else ShizukuBridge.scan(app)
        if (r.error == null) {
            privGranted = true; privFails = 0
            lastNodes = r.nodes; privError = null
        } else {
            lastNodes = emptyList()
            privError = r.error
            privRetryAt = now + 30_000L
            if (++privFails >= 3) {
                Prefs.setPrivMode(app, 0)
                privError = r.error + "（连续失败 3 次，已自动关闭特权增强）"
                privFails = 0; privGranted = false
            }
        }
        if (privError != lastLoggedError) {
            lastLoggedError = privError
            AppLog.i(if (privError == null) "特权通道正常（模式 $mode，${lastNodes.size} 个节点）" else "特权通道错误：$privError")
        }
    }

    private fun trackArchive(s: Snapshot, analysis: Analysis, inp: InputInfo, batW: Float) {
        if (s.isPlugged) {
            inp.voltageUv?.let { v -> (v / 1e6).let { if (peakVin == null || it > peakVin!!) peakVin = it } }
            Analyzer.inputPowerW(inp)?.let { if (peakInW == null || it > peakInW!!) peakInW = it }
            if (!batW.isNaN()) batW.toDouble().let { if (peakBatW == null || it > peakBatW!!) peakBatW = it }
            if (analysis.key.isNotEmpty() && analysis.key != archivedKey) {
                finishEntry()
                archivedKey = analysis.key
                val e = ArchiveEntry(
                    wallMs = s.wallMs,
                    status = analysis.status.label,
                    headline = analysis.headline,
                    key = analysis.key,
                    evidence = analysis.evidence.sortedByDescending { it.level }.map { "L${it.level} ${it.text}" },
                    candidates = analysis.candidates,
                    maxVinV = peakVin, maxInputW = peakInW, maxBatteryW = peakBatW,
                    levelPct = s.levelPct,
                    device = "${Build.MANUFACTURER} ${Build.MODEL}",
                )
                archivedEntry = e
                Archive.upsert(e)
                AppLog.i("存档：[${e.status}] ${e.headline}")
            }
        } else if (wasPlugged) {
            finishEntry()
            archivedKey = ""; archivedEntry = null
            peakVin = null; peakInW = null; peakBatW = null
        }
        wasPlugged = s.isPlugged
    }

    /** Writes the final peaks of the running session into its archive entry. */
    private fun finishEntry() {
        archivedEntry?.let {
            Archive.upsert(it.copy(maxVinV = peakVin, maxInputW = peakInW, maxBatteryW = peakBatW))
        }
    }

    suspend fun sample(ctx: Context) = mutex.withLock {
        val app = ctx.applicationContext
        AppLog.init(app)
        Archive.init(app)
        refreshPrivileged(app)
        withContext(Dispatchers.Default) {
            val s = Collector.battery(app).copy(rootNodes = lastNodes)
            ring.addLast(s)
            while (ring.size > WINDOW) ring.removeFirst()
            val list = ring.toList()
            val inp = Analyzer.input(s)
            val powerMw = Analyzer.powerMw(s)
            val batW = powerMw?.let { (it / 1000.0).toFloat() } ?: Float.NaN
            val inW = Analyzer.inputPowerW(inp)?.toFloat() ?: Float.NaN
            batSeries.addLast(batW); inSeries.addLast(inW); tsSeries.addLast(s.elapsedMs)
            while (batSeries.size > SERIES) { batSeries.removeFirst(); inSeries.removeFirst(); tsSeries.removeFirst() }

            val analysis = Analyzer.analyze(list, Build.MANUFACTURER)
            trackArchive(s, analysis, inp, batW)
            if (recording) Recorder.append(s, powerMw, inp)
            _state.value = UiState(
                latest = s,
                series = Series(
                    batSeries.toList(), inSeries.toList(),
                    ((tsSeries.last() - tsSeries.first()) / 1000L).toInt(),
                ),
                analysis = analysis,
                metrics = Analyzer.metrics(list),
                recording = recording,
                recordedRows = Recorder.rows,
                rootError = privError,
                notice = _state.value.notice,
            )
        }
    }
}
