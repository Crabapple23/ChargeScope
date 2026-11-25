package com.chargescope.app

import android.content.Context
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
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("cs", Context.MODE_PRIVATE)
    fun intervalMs(c: Context): Long = sp(c).getLong("interval", 2000L)
    fun setIntervalMs(c: Context, v: Long) = sp(c).edit().putLong("interval", v).apply()
    fun rootEnabled(c: Context): Boolean = sp(c).getBoolean("root", false)
    fun setRootEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("root", v).apply()
}

/** Append-only CSV with buffered writes (flush every few rows) and a size cap. */
object Recorder {
    private const val MAX_BYTES = 8L * 1024 * 1024
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
        if (f.length() > MAX_BYTES) {
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
    private var lastRoot: List<RootNode> = emptyList()
    private var rootError: String? = null
    @Volatile private var recording = false

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setRecording(on: Boolean) {
        recording = on
        _state.value = _state.value.copy(recording = on, recordedRows = Recorder.rows)
    }

    suspend fun sample(ctx: Context) = mutex.withLock {
        val app = ctx.applicationContext
        if (Prefs.rootEnabled(app)) {
            if (tick++ % ROOT_EVERY == 0) {
                val r = RootScanner.scan()
                lastRoot = r.nodes
                rootError = r.error
            }
        } else {
            lastRoot = emptyList(); rootError = null; tick = 0
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
            )
        }
    }
}
