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
        AppSettings.setPrivMode(app, mode)
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
            if (mode == 2) {
                val st = ShizukuBridge.refreshState(app)
                if (st == ShizukuBridge.State.READY) startProbe(app, 2)
            } else if (mode == 1) {
                sample(app)
            }
        }
    }

    /** Auto-triggered when Shizuku permission is granted or binder becomes ready while Shizuku mode is selected. */
    fun onShizukuAuthorized(ctx: Context) {
        val app = ctx.applicationContext
        if (Prefs.privMode(app) != 2) return
        bgScope.launch {
            mutex.withLock {
                privFails = 0
                privRetryAt = 0L
                privError = null
            }
            startProbe(app, 2)
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
                AppSettings.setPrivMode(app, 0)
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
