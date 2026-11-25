package com.chargescope.app

import android.content.Context
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
    fun rootEnabled(c: Context): Boolean = sp(c).getBoolean("root", false)
    fun setRootEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("root", v).apply()
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
    private const val ROOT_EVERY = 3

    private val mutex = Mutex()
    private val ring = ArrayDeque<Snapshot>()
    private var tick = 0
    private var rootFails = 0
    private var rootRetryAt = 0L
    private var rootGranted = false
    private var lastRoot: List<RootNode> = emptyList()
    private var rootError: String? = null
    @Volatile private var recording = false

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setRecording(on: Boolean) {
        recording = on
        _state.value = _state.value.copy(
            recording = on,
            recordedRows = Recorder.rows,
            notice = if (on) null else _state.value.notice,
        )
    }

    fun setNotice(text: String?) {
        _state.value = _state.value.copy(notice = text)
    }

    suspend fun sample(ctx: Context) = mutex.withLock {
        val app = ctx.applicationContext
        if (Prefs.rootEnabled(app)) {
            val now = SystemClock.elapsedRealtime()
            // Back off after a failure so a denied/slow su never spams the manager's prompt.
            if (now >= rootRetryAt && tick++ % ROOT_EVERY == 0) {
                val r = RootScanner.scan(if (rootGranted) 4 else 15)
                if (r.error == null) {
                    rootGranted = true; rootFails = 0
                    lastRoot = r.nodes; rootError = null
                } else {
                    lastRoot = emptyList()
                    rootError = r.error
                    rootRetryAt = now + 30_000L
                    if (++rootFails >= 3) {
                        Prefs.setRootEnabled(app, false)
                        rootError = r.error + "（连续失败 3 次，已自动关闭 Root 增强）"
                        rootFails = 0; rootGranted = false
                    }
                }
            }
        } else {
            lastRoot = emptyList(); rootError = null; tick = 0
            rootFails = 0; rootRetryAt = 0L; rootGranted = false
        }
        withContext(Dispatchers.Default) {
            val s = Collector.battery(app).copy(rootNodes = lastRoot)
            ring.addLast(s)
            while (ring.size > WINDOW) ring.removeFirst()
            val list = ring.toList()
            val analysis = Analyzer.analyze(list)
            if (recording) Recorder.append(s, Analyzer.powerMw(s), Analyzer.input(s))
            _state.value = UiState(
                latest = s,
                powerSeries = list.takeLast(120).map { (Analyzer.powerMw(it) ?: 0.0).toFloat() },
                analysis = analysis,
                metrics = Analyzer.metrics(list),
                recording = recording,
                recordedRows = Recorder.rows,
                rootError = rootError,
                notice = _state.value.notice,
            )
        }
    }
}
