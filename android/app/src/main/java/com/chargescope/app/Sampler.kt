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

    /** 0 = follow system (default), 1 = light, 2 = dark, 3 = scheduled */
    fun themeMode(c: Context): Int = sp(c).getInt("theme", 0)
    fun setThemeMode(c: Context, v: Int) = sp(c).edit().putInt("theme", v).apply()

    /** Minute of day [0..1439] when scheduled dark theme starts (default 22:00). */
    fun darkStartMin(c: Context): Int = sp(c).getInt("dark_start_min", 22 * 60)
    fun setDarkStartMin(c: Context, v: Int) = sp(c).edit().putInt("dark_start_min", v.coerceIn(0, 1439)).apply()

    /** Minute of day [0..1439] when scheduled dark theme ends / light starts (default 07:00). */
    fun darkEndMin(c: Context): Int = sp(c).getInt("dark_end_min", 7 * 60)
    fun setDarkEndMin(c: Context, v: Int) = sp(c).edit().putInt("dark_end_min", v.coerceIn(0, 1439)).apply()

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

    fun oledBlack(c: Context): Boolean = sp(c).getBoolean("oled_black", false)
    fun setOledBlack(c: Context, v: Boolean) = sp(c).edit().putBoolean("oled_black", v).apply()

    fun onboardingDone(c: Context): Boolean = sp(c).getBoolean("onboarding_done", false)
    fun setOnboardingDone(c: Context, v: Boolean) = sp(c).edit().putBoolean("onboarding_done", v).apply()

    fun exportTreeUri(c: Context): String? = sp(c).getString("export_tree_uri", null)
    fun setExportTreeUri(c: Context, v: String?) = sp(c).edit().putString("export_tree_uri", v).apply()

    fun recordedRows(c: Context): Long = sp(c).getLong("recorded_rows", 0L)
    fun setRecordedRows(c: Context, v: Long) = sp(c).edit().putLong("recorded_rows", v).apply()

    fun dualCell(c: Context): Boolean = sp(c).getBoolean("dual_cell", false)
    fun setDualCell(c: Context, v: Boolean) = sp(c).edit().putBoolean("dual_cell", v).apply()

    fun dualCellPrompted(c: Context): Boolean = sp(c).getBoolean("dual_cell_prompted", false)
    fun setDualCellPrompted(c: Context, v: Boolean) = sp(c).edit().putBoolean("dual_cell_prompted", v).apply()

    /** 0 = Material 3 (default), 1 = MIUIX (HyperOS style) */
    fun uiStyle(c: Context): Int = sp(c).getInt("ui_style", 0)
    fun setUiStyle(c: Context, v: Int) = sp(c).edit().putInt("ui_style", v).apply()

    fun liquidGlassM3(c: Context): Boolean = sp(c).getBoolean("liquid_glass_m3", false)
    fun setLiquidGlassM3(c: Context, v: Boolean) = sp(c).edit().putBoolean("liquid_glass_m3", v).apply()

    fun liquidGlassMiuix(c: Context): Boolean = sp(c).getBoolean("liquid_glass_miuix", false)
    fun setLiquidGlassMiuix(c: Context, v: Boolean) = sp(c).edit().putBoolean("liquid_glass_miuix", v).apply()
}

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
                            if (line.isNotBlank() && line != HEADER) total++
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

    private val _deepScanning = MutableStateFlow(false)
    val deepScanning = _deepScanning.asStateFlow()

    private val _deepScan = MutableStateFlow<String?>(null)
    val deepScan = _deepScan.asStateFlow()

    fun startDeepScan(ctx: Context) {
        if (_deepScanning.value) return
        val app = ctx.applicationContext
        val mode = Prefs.privMode(app)
        if (mode == 0) {
            _deepScan.value = "请先在“数据”页开启 Root 或 Shizuku"
            return
        }
        _deepScanning.value = true
        bgScope.launch {
            try {
                val (text, err) = if (mode == 1) RootScanner.deepProbe(10) else ShizukuBridge.deepProbe(app)
                if (err != null) {
                    _deepScan.value = "扫描失败：$err"
                    AppLog.i("深度扫描失败：$err")
                } else {
                    val trimmed = text?.trim().orEmpty()
                    val allLines = trimmed.lines()
                    var sysfsDirs = 0
                    var sysfsKv = 0
                    var serviceLines = 0
                    var inSysfs = false
                    for (l in allLines) {
                        when {
                            l.startsWith("=== /sys/") -> { sysfsDirs++; inSysfs = true }
                            l.startsWith("=== ") -> { inSysfs = false }
                            l.startsWith("#") || l.isBlank() -> Unit
                            inSysfs && l.contains('=') -> sysfsKv++
                            !inSysfs -> serviceLines++
                        }
                    }
                    _deepScan.value = when {
                        trimmed.isEmpty() -> "未发现可读节点"
                        sysfsDirs > 0 && sysfsKv == 0 && serviceLines == 0 ->
                            "检测到 $sysfsDirs 个充电节点目录，但未能读取到任何键值（常见原因：Android 14+ SELinux 限制 Shizuku uid=2000 读取 sysfs，需 Root 权限）。\n\n$trimmed"
                        sysfsDirs > 0 && sysfsKv == 0 ->
                            "sysfs 节点被 SELinux 限制（$sysfsDirs 个目录 0 条可读，完整内核节点需 Root），已通过系统服务/属性补充采集 $serviceLines 行数据：\n\n$trimmed"
                        else -> trimmed
                    }
                    AppLog.i("深度扫描完成（sysfs $sysfsDirs 目录/$sysfsKv 键值，服务数据 $serviceLines 行，${trimmed.length} 字节）")
                }
            } finally {
                _deepScanning.value = false
            }
        }
    }

    fun syncRecordedRows(count: Long) {
        if (_state.value.recordedRows != count) {
            _state.value = _state.value.copy(recordedRows = count)
        }
    }

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

    fun onDualCellChanged(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        AppSettings.setDualCell(app, enabled)
        Prefs.setDualCellPrompted(app, true)
        AppLog.i(if (enabled) "双电芯模式：已开启" else "双电芯模式：已关闭")
        bgScope.launch {
            mutex.withLock {
                rebuildSeriesLocked(enabled)
            }
            sample(app)
        }
    }

    private fun rebuildSeriesLocked(dual: Boolean) {
        seriesSize = 0
        seriesHead = 0
        val forceUa = Analyzer.usesMicroAmps(ring)
        val start = (ring.size - SERIES).coerceAtLeast(0)
        for (i in start until ring.size) {
            val snap = ring[i]
            val batF = Analyzer.powerMw(snap, dual, forceUa)?.div(1000.0)?.toFloat() ?: Float.NaN
            val inF = Analyzer.inputPowerW(Analyzer.input(snap))?.toFloat() ?: Float.NaN
            pushSeries(batF, inF, snap.elapsedMs)
        }
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

    /** Manually snapshots the current state into the archive (returns false if no sample yet). */
    fun archiveNow(ctx: Context): Boolean {
        val app = ctx.applicationContext
        AppLog.init(app)
        Archive.init(app)
        val ui = _state.value
        val s = ui.latest ?: return false
        val analysis = ui.analysis ?: return false
        val inp = Analyzer.input(s)
        val curVin = inp.voltageUv?.let { it / 1e6 }
        val specParts = ArrayList<String>(4)
        analysis.protocolSpec?.let { specParts += "已确认协议：$it" }
        specParts.addAll(analysis.candidateSpecs)
        val e = ArchiveEntry(
            wallMs = System.currentTimeMillis(),
            status = analysis.status.label,
            headline = analysis.headline,
            key = analysis.key.ifEmpty { "M:${analysis.status.name}" },
            evidence = analysis.evidence.sortedByDescending { it.level }.map { "L${it.level} ${it.text}" },
            candidates = analysis.candidates,
            maxVinV = peakVin ?: curVin,
            maxInputW = peakInW ?: ui.inputPowerW,
            maxBatteryW = peakBatW ?: ui.batteryPowerW,
            levelPct = s.levelPct,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            description = analysis.description,
            specText = specParts.joinToString("\n"),
        )
        Archive.upsert(e)
        AppLog.i("手动存档：[${e.status}] ${e.headline}")
        return true
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

        val dual = Prefs.dualCell(app)
        val forceUa = Analyzer.usesMicroAmps(ring)
        val inp = Analyzer.input(s)
        val currentMa = Analyzer.currentMa(s, forceUa)
        val powerMw = Analyzer.powerMw(s, dual, forceUa)
        val batW = powerMw?.div(1000.0)
        val inW = Analyzer.inputPowerW(inp)
        val batF = batW?.toFloat() ?: Float.NaN
        val inF = inW?.toFloat() ?: Float.NaN
        val series = pushSeries(batF, inF, s.elapsedMs)

        val analysis = Analyzer.analyze(ring, Build.MANUFACTURER, dual)
        trackArchive(s, analysis, inp, batF)
        if (recording) Recorder.append(app, s, powerMw, inp)
        _state.value = UiState(
            latest = s,
            batteryCurrentMa = currentMa,
            batteryPowerW = batW,
            inputPowerW = inW,
            series = series,
            analysis = analysis,
            metrics = Analyzer.metrics(ring, dual),
            recording = recording,
            recordedRows = Recorder.rows,
            rootError = privError,
            notice = _state.value.notice,
        )
    }
}
