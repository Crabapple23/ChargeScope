package com.chargescope.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Re-arm auto-record (service may have been killed); allowed because we are in the foreground.
        if (Prefs.autoRecord(this)) SamplingService.send(this, SamplingService.ACTION_ARM)
        // Live view while the UI is visible; the background service takes over when recording.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (!Sampler.state.value.recording) Sampler.sample(applicationContext)
                    delay(Prefs.intervalMs(applicationContext))
                }
            }
        }
        setContent { App() }
    }
}

private val TABS = listOf("实时", "协议", "设备", "数据")

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val ui by Sampler.state.collectAsState()
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(t.take(1)) },
                            label = { Text(t) },
                        )
                    }
                }
            },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (tab) {
                    0 -> RealtimeTab(ui)
                    1 -> ProtocolTab(ui)
                    2 -> DeviceTab(ui)
                    else -> DataTab(ui)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun KV(k: String, v: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v ?: "—")
    }
}

private fun fmt(v: Double?, unit: String, digits: Int = 2): String? =
    v?.let { String.format("%.${digits}f %s", it, unit) }

private fun statusText(s: Int?) = when (s) {
    2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null
}

private fun pluggedText(p: Int) = when (p) {
    0 -> "未连接"; 1 -> "交流电源"; 2 -> "USB"; 4 -> "无线"; 8 -> "底座"; else -> "其他($p)"
}

private fun healthText(h: Int?) = when (h) {
    2 -> "良好"; 3 -> "过热"; 4 -> "损坏"; 5 -> "过压"; 6 -> "故障"; 7 -> "过冷"; else -> null
}

@Composable
private fun RealtimeTab(ui: UiState) {
    val ctx = LocalContext.current
    val s = ui.latest
    Section("电池（公开 API）") {
        KV("电量", s?.levelPct?.let { "$it %" })
        KV("状态", statusText(s?.status))
        KV("连接方式", s?.let { pluggedText(it.plugged) })
        KV("电压", fmt(s?.voltageMv?.let { it / 1000.0 }, "V"))
        KV("电流（原始值）", s?.currentUa?.let { "$it µA" })
        KV("电池侧功率（估算）", fmt(s?.let { Analyzer.powerMw(it) }?.div(1000.0), "W"))
        KV("温度", fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1))
        KV("健康", healthText(s?.health))
    }
    Section("功率曲线（电池侧估算，约 ${ui.powerSeries.size} 点）") {
        PowerChart(ui.powerSeries)
    }
    ui.metrics?.let { m ->
        Section("统计（最近 ${m.count} 个样本）") {
            KV("时长", "%.0f 秒".format(m.durationMs / 1000.0))
            KV("电量变化速率", fmt(m.socRatePerMin, "%/分钟"))
            KV("平均功率", fmt(m.avgPowerMw?.div(1000.0), "W"))
            KV("峰值功率", fmt(m.peakPowerMw?.div(1000.0), "W"))
            KV("平均温度", fmt(m.avgTempDeci?.div(10.0), "°C", 1))
        }
    }
    ui.notice?.let { n -> Section("提示") { Text(n) } }
    Button(
        onClick = {
            SamplingService.send(ctx, if (ui.recording) SamplingService.ACTION_STOP_REC else null)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (ui.recording) "停止记录（已写入 ${ui.recordedRows} 行）" else "开始后台记录") }
}

@Composable
private fun PowerChart(values: List<Float>) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1f)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1)
            val y = size.height * (1f - v / max)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

@Composable
private fun ProtocolTab(ui: UiState) {
    val a = ui.analysis
    if (a == null) { Text("等待首次采样…"); return }
    Section("结论：${a.status.label}") { Text(a.headline, style = MaterialTheme.typography.titleSmall) }
    Section("证据（L4 驱动明示 > L3 输入侧 > L2 功率参考）") {
        if (a.evidence.isEmpty()) Text("暂无证据") else a.evidence.sortedByDescending { it.level }.forEach {
            Text("L${it.level}  ${it.text}")
        }
    }
    Section("限制说明") { a.limitations.forEach { Text("• $it") } }
    ui.rootError?.let { Section("Root 状态") { Text(it) } }
}

@Composable
private fun DeviceTab(ui: UiState) {
    val s = ui.latest
    Section("设备") {
        KV("型号", "${Build.MANUFACTURER} ${Build.MODEL}")
        KV("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        KV("SoC", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
    }
    Section("无 Root 可读字段") {
        KV("电量/电压/温度/状态", if (s?.levelPct != null || s?.voltageMv != null) "可用" else "不可用")
        KV("瞬时电流", if (s?.currentUa != null) "可用" else "不可用")
        KV("平均电流", if (s?.currentAvgUa != null) "可用" else "不可用")
        KV("累计电荷", s?.chargeCounterUah?.let { "$it µAh" } ?: "不可用")
        KV("输入侧电压/电流", "需要 Root，且节点因机型而异")
    }
    Section("Root 读取的 power_supply 节点（只读）") {
        val nodes = s?.rootNodes.orEmpty()
        if (nodes.isEmpty()) Text(ui.rootError ?: "未启用 Root（在“数据”页开启）")
        nodes.forEach { n ->
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.props.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chips(options: List<Pair<String, Long>>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DataTab(ui: UiState) {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(Prefs.rootEnabled(ctx)) }
    var interval by remember { mutableStateOf(Prefs.intervalMs(ctx)) }
    var auto by remember { mutableStateOf(Prefs.autoRecord(ctx)) }
    var maxTemp by remember { mutableStateOf(Prefs.long(ctx, Prefs.TEMP, 45L)) }
    var maxMin by remember { mutableStateOf(Prefs.long(ctx, Prefs.MINUTES, 120L)) }
    var stopSoc by remember { mutableStateOf(Prefs.long(ctx, Prefs.SOC, 0L)) }
    var maxMb by remember { mutableStateOf(Prefs.long(ctx, Prefs.FILE_MB, 8L)) }
    var msg by remember { mutableStateOf<String?>(null) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    Recorder.flush()
                    val f = Recorder.file(ctx)
                    if (!f.exists()) false else {
                        ctx.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                        true
                    }
                } catch (e: Exception) { false }
            }
            msg = if (ok) "已导出" else "导出失败（尚无记录？）"
        }
    }

    Section("采样间隔") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1000L, 2000L, 5000L).forEach { v ->
                FilterChip(
                    selected = interval == v,
                    onClick = { interval = v; Prefs.setIntervalMs(ctx, v) },
                    label = { Text("${v / 1000} 秒") },
                )
            }
        }
    }
    Section("自动记录") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("连接充电器时自动开始，拔出后停止", Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = {
                auto = it
                Prefs.setAutoRecord(ctx, it)
                SamplingService.send(ctx, if (it) SamplingService.ACTION_ARM else SamplingService.ACTION_DISARM)
            })
        }
        Text("开启后会常驻一条低优先级通知（空闲时不采样）。系统重启后需打开一次应用以恢复。", style = MaterialTheme.typography.bodySmall)
    }
    Section("安全阈值（任一触发即停止记录）") {
        Text("电池温度上限", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("40 °C" to 40L, "42 °C" to 42L, "45 °C" to 45L, "50 °C" to 50L), maxTemp) { maxTemp = it; Prefs.setLong(ctx, Prefs.TEMP, it) }
        Text("单次最长时长", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("30 分钟" to 30L, "1 小时" to 60L, "2 小时" to 120L, "不限" to 0L), maxMin) { maxMin = it; Prefs.setLong(ctx, Prefs.MINUTES, it) }
        Text("电量达到后停止", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("80%" to 80L, "90%" to 90L, "100%" to 100L, "不限" to 0L), stopSoc) { stopSoc = it; Prefs.setLong(ctx, Prefs.SOC, it) }
        Text("记录文件大小上限", style = MaterialTheme.typography.labelLarge)
        Chips(listOf("4 MB" to 4L, "8 MB" to 8L, "16 MB" to 16L), maxMb) { maxMb = it; Prefs.setLong(ctx, Prefs.FILE_MB, it) }
        Text("阈值只会停止本应用的记录，不会控制或限制充电。", style = MaterialTheme.typography.bodySmall)
    }
    Section("Root 增强（默认关闭）") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("读取 /sys/class/power_supply", Modifier.weight(1f))
            Switch(checked = root, onCheckedChange = { root = it; Prefs.setRootEnabled(ctx, it) })
        }
        Text("仅通过 su 执行固定的只读脚本，不写任何节点。", style = MaterialTheme.typography.bodySmall)
    }
    Section("记录与导出") {
        Text("已记录 ${ui.recordedRows} 行；数据仅保存在本机，应用没有联网权限。导出文件不含设备标识。")
        Button(onClick = { export.launch("chargescope.csv") }, modifier = Modifier.fillMaxWidth()) { Text("导出 CSV") }
        OutlinedButton(
            onClick = { Recorder.clear(ctx); msg = "已清除记录" },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("清除记录") }
        msg?.let { Text(it) }
    }
}
