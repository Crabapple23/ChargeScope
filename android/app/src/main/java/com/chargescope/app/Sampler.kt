package com.chargescope.app

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToLong

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

    fun autoRecord(c: Context): Boolean = sp(c).getBoolean("auto", true)
    fun setAutoRecord(c: Context, v: Boolean) = sp(c).edit().putBoolean("auto", v).apply()

    /** 0 = follow system (default), 1 = light, 2 = dark */
    fun themeMode(c: Context): Int = sp(c).getInt("theme", 0)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    /** 0 = small (0.88x), 1 = normal (1.0x, default), 2 = large (1.15x), 3 = xlarge (1.3x) */
    fun fontTier(c: Context): Int = sp(c).getInt("font_tier", 1)
    fun setFontTier(c: Context, v: Int) = sp(c).edit().putInt("font_tier", v).apply()

    /** "zh" (default) or "en" */
    fun lang(c: Context): String = sp(c).getString("lang", "zh") ?: "zh"
    fun setLang(c: Context, v: String) = sp(c).edit().putString("lang", v).apply()

    /** 0 = GitHub Releases (default), 1 = GitHub Actions */
    fun updateSource(c: Context): Int = sp(c).getInt("update_src", 0)
    fun setUpdateSource(c: Context, v: Int) = sp(c).edit().putInt("update_src", v).apply()

    fun predictiveBack(c: Context): Boolean = sp(c).getBoolean("pred_back", true)
    fun setPredictiveBack(c: Context, v: Boolean) = sp(c).edit().putBoolean("pred_back", v).apply()

    fun dynamicColor(c: Context): Boolean = sp(c).getBoolean("dyn_color", true)
    fun setDynamicColor(c: Context, v: Boolean) = sp(c).edit().putBoolean("dyn_color", v).apply()
}

/**
 * Append-only GZIP-compressed CSV (5x-10x smaller on disk than raw CSV).
 * Uses syncFlush so flushed blocks remain readable even if the process is killed,
 * and decompresses on-the-fly when exporting to a plain .csv file.
 */
object Recorder {
    private const val HEADER =
        "wall_ms,elapsed_ms,level_pct,status,plugged,voltage_mv,temp_decic,current_ua," +
            "current_avg_ua,charge_counter_uah,battery_power_mw_est,input_voltage_uv,input_current_ua,usb_type"
    private var writer: BufferedWriter? = null
    private val lineBuf = StringBuilder(160)
    @Volatile var rows: Long = 0
        private set
    @Volatile var approxBytes: Long = 0
        private set

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "records.csv.gz")
    private fun legacyFile(ctx: Context) = File(ctx.applicationContext.filesDir, "records.csv")

    @Synchronized
    fun open(ctx: Context) {
        if (writer != null) return
        val f = file(ctx)
        val cap = Prefs.long(ctx, Prefs.FILE_MB, 8L) * 1024L * 1024L
        if (f.length() >= cap) {
            val old = File(f.parentFile, "records.old.csv.gz")
            old.delete()
            f.renameTo(old)
        }
        val fresh = !f.exists() || f.length() == 0L
        val gz = GZIPOutputStream(FileOutputStream(f, true), 4096, true)
        val w = BufferedWriter(OutputStreamWriter(gz, Charsets.UTF_8), 4096)
        if (fresh) { w.write(HEADER); w.newLine() }
        writer = w
        approxBytes = f.length()
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
            approxBytes = file(ctx).length()
        }
    }

    @Synchronized fun flush() { try { writer?.flush() } catch (_: Exception) {} }

    @Synchronized
    fun close() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null
    }

    @Synchronized
    fun exportTo(ctx: Context, out: OutputStream): Boolean {
        flush()
        val gz = file(ctx)
        if (gz.exists() && gz.length() > 0L) {
            val buf = ByteArray(8192)
            GZIPInputStream(FileInputStream(gz)).use { input ->
                try {
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                } catch (_: EOFException) {
                    // Tail block truncated by a prior hard kill; previously flushed rows are intact.
                }
            }
            return true
        }
        val legacy = legacyFile(ctx)
        if (legacy.exists() && legacy.length() > 0L) {
            legacy.inputStream().use { it.copyTo(out) }
            return true
        }
        return false
    }

    fun diskBytes(ctx: Context): Long {
        val dir = ctx.applicationContext.filesDir
        var total = 0L
        for (name in arrayOf("records.csv.gz", "records.old.csv.gz", "records.csv", "records.old.csv")) {
            val f = File(dir, name)
            if (f.exists()) total += f.length()
        }
        return total
    }

    @Synchronized
    fun clear(ctx: Context) {
        close()
        val dir = ctx.applicationContext.filesDir
        for (name in arrayOf("records.csv.gz", "records.old.csv.gz", "records.csv", "records.old.csv")) {
            File(dir, name).delete()
        }
        rows = 0
        approxBytes = 0
    }
}

object Sampler {
    private const val WINDOW = 300
    private const val KEEP_NODES = 60
    private const val SERIES = 120
    private const val PRIV_EVERY = 3

    private val mutex = Mutex()
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ring = ArrayDeque<Snapshot>(WINDOW + 1)
    private val batSeries = FloatArray(SERIES)
    private val inSeries = FloatArray(SERIES)
    private val tsSeries = LongArray(SERIES)
    private var seriesSize = 0
    private var seriesHead = 0

    private var tick = 0
    private var privFails = 0
    private var privRetryAt = 0L
    private var privGranted = false
    private var lastMode = 0
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

    private val _probing = MutableStateFlow(false)
    val probing = _probing.asStateFlow()

    private val _probeMsg = MutableStateFlow<String?>(null)
    val probeMsg = _probeMsg.asStateFlow()

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

    /** Called immediately when the user switches privileged mode; runs on Sampler's own scope. */
    fun onPrivModeChanged(ctx: Context, mode: Int) {
        val app = ctx.applicationContext
        Prefs.setPrivMode(app, mode)
        _probeMsg.value = null
        _state.value = _state.value.copy(rootError = null)
        bgScope.launch {
            mutex.withLock {
                privFails = 0
                privRetryAt = 0L
                tick = 0
                privError = null
                lastLoggedError = null
                if (mode != 1) RootScanner.close()
                if (mode != 2) ShizukuBridge.unbind(app)
                if (mode == 0) {
                    lastMode = 0
                    lastNodes = emptyList()
                    privGranted = false
                }
            }
            if (mode == 2) ShizukuBridge.refreshState()
            if (mode != 0) sample(app)
        }
    }

    /** Explicit user-triggered probe: runs on Sampler's scope under mutex so tab switches never cancel it. */
    fun startProbe(ctx: Context, mode: Int) {
        if (_probing.value) return
        val app = ctx.applicationContext
        _probing.value = true
        _probeMsg.value = if (mode == 1) "正在检测…（如弹出 Root 授权请点允许）" else "正在检测 Shizuku…"
        bgScope.launch {
            try {
                mutex.withLock {
                    AppLog.init(app)
                    Archive.init(app)
                    privFails = 0
                    privRetryAt = 0L
                    tick = 0
                    val r = if (mode == 1) RootScanner.scan(20) else ShizukuBridge.scan(app)
                    if (r.error == null) {
                        lastMode = mode
                        privGranted = true
                        privFails = 0
                        privRetryAt = 0L
                        lastNodes = r.nodes
                        privError = null
                        lastLoggedError = null
                        _probeMsg.value = (if (mode == 1) "Root" else "Shizuku") + " 可用，读到 ${r.nodes.size} 个节点"
                        sampleLocked(app)
                    } else {
                        privError = r.error
                        _probeMsg.value = r.error
                        _state.value = _state.value.copy(rootError = r.error)
                    }
                }
            } finally {
                _probing.value = false
            }
        }
    }

    private suspend fun refreshPrivileged(app: Context) {
        val mode = Prefs.privMode(app)
        if (mode != lastMode) {
            if (lastMode == 1) RootScanner.close()
            if (lastMode == 2) ShizukuBridge.unbind(app)
            lastMode = mode
            tick = 0; privFails = 0; privRetryAt = 0L; privGranted = false
        }
        if (mode == 0) {
            lastNodes = emptyList(); privError = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Back off after a failure so a denied/slow channel never spams the manager's prompt.
        if (now < privRetryAt || tick++ % PRIV_EVERY != 0) return
        // Give first-time ungranted Root a 10s prompt window; granted scans stay at 4s.
        val r = if (mode == 1) RootScanner.scan(if (privGranted) 4 else 10) else ShizukuBridge.scan(app)
        if (r.error == null) {
            privGranted = true; privFails = 0
            lastNodes = r.nodes; privError = null
        } else {
            lastNodes = emptyList()
            privError = r.error
            privRetryAt = now + 30_000L
            if (++privFails >= 3) {
                Prefs.setPrivMode(app, 0)
                if (mode == 1) RootScanner.close() else ShizukuBridge.unbind(app)
                lastMode = 0
                privError = r.error + "（连续失败 3 次，已自动关闭特权增强）"
                privFails = 0; privGranted = false
            }
        }
        if (privError != lastLoggedError) {
            lastLoggedError = privError
            AppLog.i(if (privError == null) "特权通道正常（模式 $mode，${lastNodes.size} 个节点）" else "特权通道错误：$privError")
        }
    }

    private fun pushSeries(batW: Float, inW: Float, ts: Long): Series {
        val pos = if (seriesSize < SERIES) {
            (seriesHead + seriesSize++).rem(SERIES)
        } else {
            val p = seriesHead
            seriesHead = (seriesHead + 1) % SERIES
            p
        }
        batSeries[pos] = batW
        inSeries[pos] = inW
        tsSeries[pos] = ts

        val n = seriesSize
        val bOut = FloatArray(n)
        val iOut = FloatArray(n)
        var maxW = 0f
        var valid = 0
        var hasIn = false
        for (k in 0 until n) {
            val idx = (seriesHead + k) % SERIES
            val b = batSeries[idx]
            val inp = inSeries[idx]
            bOut[k] = b
            iOut[k] = inp
            if (!b.isNaN()) {
                valid++
                if (b > maxW) maxW = b
            }
            if (!inp.isNaN()) {
                valid++
                hasIn = true
                if (inp > maxW) maxW = inp
            }
        }
        val firstTs = tsSeries[seriesHead]
        val lastTs = tsSeries[(seriesHead + n - 1) % SERIES]
        return Series(bOut, iOut, ((lastTs - firstTs) / 1000L).toInt(), maxW, valid, hasIn)
    }

    private fun trackArchive(s: Snapshot, analysis: Analysis, inp: InputInfo, batW: Float) {
        if (s.isPlugged) {
            inp.voltageUv?.let { v -> (v / 1e6).let { if (peakVin == null || it > peakVin!!) peakVin = it } }
            Analyzer.inputPowerW(inp)?.let { if (peakInW == null || it > peakInW!!) peakInW = it }
            if (!batW.isNaN()) batW.toDouble().let { if (peakBatW == null || it > peakBatW!!) peakBatW = it }
            if (analysis.key.isNotEmpty() && analysis.key != archivedKey) {
                finishEntry()
                archivedKey = analysis.key
                val specParts = ArrayList<String>(4)
                analysis.protocolSpec?.let { specParts += "已确认协议：$it" }
                specParts.addAll(analysis.candidateSpecs)
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
                    description = analysis.description,
                    specText = specParts.joinToString("\n"),
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
        sampleLocked(app)
    }

    private suspend fun sampleLocked(app: Context) = withContext(Dispatchers.Default) {
        val s = Collector.battery(app).copy(rootNodes = lastNodes)
        ring.addLast(s)
        if (ring.size > WINDOW) ring.removeFirst()
        // Drop bulky sysfs maps from snapshots older than the analysis window (60 samples).
        val stripIdx = ring.size - KEEP_NODES - 1
        if (stripIdx >= 0 && ring[stripIdx].rootNodes.isNotEmpty()) {
            ring[stripIdx] = ring[stripIdx].copy(rootNodes = emptyList())
        }

        val inp = Analyzer.input(s)
        val powerMw = Analyzer.powerMw(s)
        val batW = powerMw?.div(1000.0)
        val inW = Analyzer.inputPowerW(inp)
        val batF = batW?.toFloat() ?: Float.NaN
        val inF = inW?.toFloat() ?: Float.NaN
        val series = pushSeries(batF, inF, s.elapsedMs)

        val analysis = Analyzer.analyze(ring, Build.MANUFACTURER)
        trackArchive(s, analysis, inp, batF)
        if (recording) Recorder.append(app, s, powerMw, inp)
        _state.value = UiState(
            latest = s,
            batteryPowerW = batW,
            inputPowerW = inW,
            series = series,
            analysis = analysis,
            metrics = Analyzer.metrics(ring),
            recording = recording,
            recordedRows = Recorder.rows,
            rootError = privError,
            notice = _state.value.notice,
        )
    }
}
