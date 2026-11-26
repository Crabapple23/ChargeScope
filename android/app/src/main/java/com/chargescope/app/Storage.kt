package com.chargescope.app

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Small append-only event log (capped at 128 KB) used for the diagnostic export. */
object AppLog {
    private const val MAX_BYTES = 128 * 1024
    private const val KEEP_BYTES = 64 * 1024
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private var file: File? = null

    fun init(ctx: Context) {
        if (file == null) file = File(ctx.applicationContext.filesDir, "events.log")
    }

    private fun ts(t: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(FMT)

    @Synchronized
    fun i(msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val raw = f.readBytes()
                var start = (raw.size - KEEP_BYTES).coerceAtLeast(0)
                while (start < raw.size && raw[start] != '\n'.code.toByte()) start++
                if (start < raw.size) start++
                FileOutputStream(f, false).use { it.write(raw, start, raw.size - start) }
            }
            FileOutputStream(f, true).use { it.write("${ts()} $msg\n".toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

    @Synchronized
    fun sizeBytes(): Long = try { file?.takeIf { it.exists() }?.length() ?: 0L } catch (_: Exception) { 0L }

    @Synchronized
    fun clear() { try { file?.delete() } catch (_: Exception) {} }

    fun time(t: Long) = ts(t)
}

/** Persisted protocol-identification archive (JSON lines, newest last, capped at 100, clearable). */
object Archive {
    private const val MAX = 100
    private var file: File? = null
    private val _items = MutableStateFlow<List<ArchiveEntry>>(emptyList())
    val items = _items.asStateFlow()

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "archive.jsonl")
        file = f
        if (!f.exists()) return
        val list = ArrayList<ArchiveEntry>()
        try {
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try { list += fromJson(JSONObject(line)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
        if (list.size > MAX) {
            _items.value = list.takeLast(MAX)
            rewrite()
        } else {
            _items.value = list
        }
    }

    @Synchronized
    fun upsert(e: ArchiveEntry) {
        val cur = _items.value.toMutableList()
        val idx = cur.indexOfFirst { it.wallMs == e.wallMs }
        if (idx >= 0) {
            // Preserve any user-edited note if the session peak update overwrites the entry.
            val keptNote = if (e.note.isEmpty()) cur[idx].note else e.note
            cur[idx] = e.copy(note = keptNote)
            _items.value = cur
            rewrite()
        } else {
            cur += e
            if (cur.size > MAX) {
                _items.value = cur.takeLast(MAX)
                rewrite()
            } else {
                _items.value = cur
                appendLine(e)
            }
        }
    }

    @Synchronized
    fun updateNote(wallMs: Long, note: String) {
        val cur = _items.value.toMutableList()
        val idx = cur.indexOfFirst { it.wallMs == wallMs }
        if (idx < 0) return
        cur[idx] = cur[idx].copy(note = note.trim())
        _items.value = cur
        rewrite()
    }

    @Synchronized
    fun sizeBytes(): Long = try { file?.takeIf { it.exists() }?.length() ?: 0L } catch (_: Exception) { 0L }

    @Synchronized
    fun clear() {
        _items.value = emptyList()
        try { file?.delete() } catch (_: Exception) {}
    }

    private fun appendLine(e: ArchiveEntry) {
        val f = file ?: return
        try {
            FileOutputStream(f, true).use { it.write((toJson(e).toString() + "\n").toByteArray(Charsets.UTF_8)) }
        } catch (_: Exception) {
        }
    }

    private fun rewrite() {
        val f = file ?: return
        try {
            f.bufferedWriter().use { w ->
                for (item in _items.value) {
                    w.write(toJson(item).toString())
                    w.newLine()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun arr(l: List<String>) = JSONArray().also { a -> l.forEach { a.put(it) } }
    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.getString(it) }
    private fun dbl(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null

    private fun toJson(e: ArchiveEntry) = JSONObject().apply {
        put("t", e.wallMs); put("status", e.status); put("headline", e.headline); put("key", e.key)
        put("evidence", arr(e.evidence)); put("candidates", arr(e.candidates))
        e.maxVinV?.let { put("maxVinV", it) }
        e.maxInputW?.let { put("maxInputW", it) }
        e.maxBatteryW?.let { put("maxBatteryW", it) }
        e.levelPct?.let { put("level", it) }
        put("device", e.device)
        if (e.description.isNotEmpty()) put("desc", e.description)
        if (e.specText.isNotEmpty()) put("spec", e.specText)
        if (e.note.isNotEmpty()) put("note", e.note)
    }

    private fun fromJson(o: JSONObject) = ArchiveEntry(
        wallMs = o.getLong("t"),
        status = o.getString("status"),
        headline = o.getString("headline"),
        key = o.optString("key"),
        evidence = strs(o.optJSONArray("evidence")),
        candidates = strs(o.optJSONArray("candidates")),
        maxVinV = dbl(o, "maxVinV"),
        maxInputW = dbl(o, "maxInputW"),
        maxBatteryW = dbl(o, "maxBatteryW"),
        levelPct = if (o.has("level")) o.getInt("level") else null,
        device = o.optString("device"),
        description = o.optString("desc"),
        specText = o.optString("spec"),
        note = o.optString("note"),
    )
}

/** Builds the plain-text diagnostic report for export. Contains no personal identifiers. */
object Report {
    fun build(ctx: Context, ui: UiState): String {
        val sb = StringBuilder(4096)
        fun line(s: String = "") { sb.append(s).append('\n') }
        line("# 充了个电 (ChargeScope) 诊断日志")
        line("生成时间：" + AppLog.time(System.currentTimeMillis()))
        line("应用版本：${BuildConfig.VERSION_NAME}")
        line("设备：${Build.MANUFACTURER} ${Build.MODEL}  Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= 31) line("SoC：${Build.SOC_MODEL}")
        line()
        line("## 设置")
        line("特权通道：" + when (Prefs.privMode(ctx)) { 1 -> "Root"; 2 -> "Shizuku"; else -> "关闭" })
        line("Shizuku 状态：" + ShizukuBridge.state().label)
        line("采样间隔：${Prefs.intervalMs(ctx)} ms；自动记录：${Prefs.autoRecord(ctx)}")
        line("阈值：温度 ${Prefs.long(ctx, Prefs.TEMP, 45L)}°C，时长 ${Prefs.long(ctx, Prefs.MINUTES, 120L)} 分钟，" +
            "电量 ${Prefs.long(ctx, Prefs.SOC, 100L)}%，文件 ${Prefs.long(ctx, Prefs.FILE_MB, 8L)} MB")
        ui.rootError?.let { line("特权通道错误：$it") }
        line()
        val s = ui.latest
        line("## 最新样本")
        if (s == null) line("无") else {
            line("电量 ${s.levelPct ?: "—"}%  状态码 ${s.status ?: "—"}  连接 ${s.plugged}  健康码 ${s.health ?: "—"}")
            line("电压 ${s.voltageMv ?: "—"} mV  温度 ${s.tempDeci ?: "—"} (0.1°C)  电流 ${s.currentUa ?: "—"} µA  平均电流 ${s.currentAvgUa ?: "—"} µA")
            Analyzer.powerMw(s)?.let { line("电池侧估算功率 %.0f mW".format(it)) }
            for (n in s.rootNodes) line("[${n.name}] " + n.props.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
        line()
        ui.analysis?.let { a ->
            line("## 当前识别结果：${a.status.label}")
            line(a.headline)
            line("驱动报告协议：${a.driver ?: "未报告"}；预测当前协议：${a.predicted ?: "无法预测"}")
            a.protocolSpec?.let { line("协议规格：$it") }
            if (a.description.isNotEmpty()) line("描述：${a.description}")
            a.evidence.sortedByDescending { it.level }.forEach { line("L${it.level} ${it.text}") }
            if (a.candidates.isNotEmpty()) line("候选：" + a.candidates.joinToString("；"))
            a.limitations.forEach { line("限制：$it") }
            line()
        }
        line("## 识别存档（${Archive.items.value.size} 条）")
        for (e in Archive.items.value) {
            line("${AppLog.time(e.wallMs)} [${e.status}] ${e.headline}  电量 ${e.levelPct ?: "—"}%")
            if (e.note.isNotEmpty()) line("    备注：${e.note}")
            line("    峰值：输入电压 ${e.maxVinV?.let { "%.1f V".format(it) } ?: "—"}，输入功率 ${e.maxInputW?.let { "%.1f W".format(it) } ?: "—"}，电池侧功率 ${e.maxBatteryW?.let { "%.1f W".format(it) } ?: "—"}")
            if (e.description.isNotEmpty()) line("    描述：${e.description}")
            if (e.specText.isNotEmpty()) line("    档位说明：${e.specText}")
        }
        line()
        line("## 事件日志")
        sb.append(AppLog.read())
        Sampler.deepScan.value?.takeIf { it.isNotBlank() }?.let { deep ->
            line()
            line("## 深度扫描结果")
            sb.append(deep).append('\n')
        }
        return sb.toString().replace("\u0000", "")
    }
}
