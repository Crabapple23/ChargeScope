package com.chargescope.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        // Initialize disk-backed stores off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            AppLog.init(applicationContext)
            Archive.init(applicationContext)
        }
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

    override fun onDestroy() {
        if (!Sampler.state.value.recording) RootScanner.close()
        super.onDestroy()
    }
}

private data class TabItem(val zh: String, val en: String, val icon: Int)

private val TABS = listOf(
    TabItem("实时", "Live", R.drawable.ic_tab_realtime),
    TabItem("协议", "Protocol", R.drawable.ic_tab_protocol),
    TabItem("存档", "Archive", R.drawable.ic_tab_archive),
    TabItem("数据", "Data", R.drawable.ic_tab_data),
    TabItem("设备", "Device", R.drawable.ic_tab_device),
)
private val GRID_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)
private val NICE_STEPS = floatArrayOf(5f, 10f, 20f, 30f, 50f, 70f, 100f, 150f, 200f, 300f)

@Composable
private fun App() {
    val prefs by AppSettings.prefs.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val dark = when (prefs.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) darkColorScheme() else lightColorScheme()
    val view = LocalView.current
    val ctx = LocalContext.current
    SideEffect {
        (ctx as? ComponentActivity)?.window?.let { w ->
            @Suppress("DEPRECATION")
            w.statusBarColor = colors.surface.toArgb()
            @Suppress("DEPRECATION")
            w.navigationBarColor = colors.surface.toArgb()
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, prefs.fontTier) {
        Density(baseDensity.density, baseDensity.fontScale * prefs.fontMultiplier)
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity, LocalIsEn provides prefs.isEn) {
        MaterialTheme(colorScheme = colors) {
            BackHandler(enabled = showSettings) { showSettings = false }
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, t ->
                                val label = tr(t.zh, t.en)
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = {
                                        Icon(
                                            painter = painterResource(t.icon),
                                            contentDescription = label,
                                        )
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    // Each tab collects only the state it needs, avoiding root-level recompositions every 2s.
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (tab) {
                            0 -> ScrollBody { RealtimeTab() }
                            1 -> ScrollBody { ProtocolTab() }
                            2 -> ArchiveTab()
                            3 -> ScrollBody { DataTab() }
                            else -> ScrollBody { DeviceTab(onOpenSettings = { showSettings = true }) }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showSettings,
                    enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(180)),
                    exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160)),
                ) {
                    SettingsScreen(prefs = prefs, onBack = { showSettings = false })
                }
            }
        }
    }
}

@Composable
private fun ScrollBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v ?: "—", Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

private fun fmt(v: Double?, unit: String, digits: Int = 2): String? =
    v?.let { String.format("%.${digits}f %s", it, unit) }

private fun fmtBytes(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024L -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / (1024.0 * 1024.0))
}

private fun statusText(s: Int?) = when (s) {
    2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null
}

private fun pluggedText(p: Int) = when (p) {
    0 -> "未连接"; 1 -> "交流电源"; 2 -> "USB"; 4 -> "无线"; 8 -> "底座"; else -> "其他($p)"
}

private fun healthText(h: Int?) = when (h) {
    2 -> "良好"; 3 -> "过热"; 4 -> "损坏"; 5 -> "过压"; 6 -> "故障"; 7 -> "过冷"; else -> null
}

// ---------------------------------------------------------------- realtime

@Composable
private fun RealtimeTab() {
    val ui by Sampler.state.collectAsState()
    val ctx = LocalContext.current
    val s = ui.latest
    Section(tr("电池（公开 API）", "Battery (Public API)")) {
        KV(tr("电量", "Level"), s?.levelPct?.let { "$it %" })
        KV(tr("状态", "Status"), statusText(s?.status))
        KV(tr("连接方式", "Plugged"), s?.let { pluggedText(it.plugged) })
        KV(tr("电池端电压", "Battery Voltage"), fmt(s?.voltageMv?.let { it / 1000.0 }, "V"))
        KV(tr("电池电流（绝对值）", "Battery Current"), fmt(s?.let { Analyzer.currentMa(it) }, "mA", 0))
        KV(tr("电池侧功率（估算）", "Battery Power (Est.)"), fmt(ui.batteryPowerW, "W"))
        KV(tr("输入侧功率（需 Root/Shizuku）", "Input Power (Root/Shizuku)"), fmt(ui.inputPowerW, "W"))
        KV(tr("温度", "Temperature"), fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1))
        KV(tr("健康", "Health"), healthText(s?.health))
    }
    Section(tr("功率曲线", "Power Chart")) {
        PowerChart(ui.series)
    }
    ui.metrics?.let { m ->
        Section(tr("统计（最近 ${m.count} 个样本，约 ${"%.0f".format(m.durationMs / 1000.0)} 秒）", "Stats (${m.count} samples, ~${"%.0f".format(m.durationMs / 1000.0)}s)")) {
            KV(tr("电量变化速率", "SOC Rate"), fmt(m.socRatePerMin, tr("%/分钟", "%/min")))
            KV(tr("平均功率（电池侧）", "Avg Power (Bat)"), fmt(m.avgPowerMw?.div(1000.0), "W"))
            KV(tr("峰值功率（电池侧）", "Peak Power (Bat)"), fmt(m.peakPowerMw?.div(1000.0), "W"))
            KV(tr("平均温度", "Avg Temp"), fmt(m.avgTempDeci?.div(10.0), "°C", 1))
        }
    }
    ui.notice?.let { n -> Section(tr("提示", "Notice")) { Text(n) } }
    Button(
        onClick = {
            SamplingService.send(ctx, if (ui.recording) SamplingService.ACTION_STOP_REC else null)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (ui.recording) tr("停止记录（已写入 ${ui.recordedRows} 行）", "Stop Recording (${ui.recordedRows} rows)")
            else tr("开始后台记录", "Start Background Recording"),
        )
    }
}

private fun niceMax(v: Float): Float {
    for (s in NICE_STEPS) if (v <= s) return s
    return (kotlin.math.ceil(v / 100f) * 100f)
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PowerChart(series: Series) {
    val batColor = MaterialTheme.colorScheme.primary
    val inColor = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (series.validCount < 2) {
        Text("等待数据…（至少需要 2 个采样点）")
        return
    }
    val maxV = niceMax(series.maxW)
    val small = MaterialTheme.typography.labelSmall
    val batPath = remember { Path() }
    val inPath = remember { Path() }
    val stroke = remember { Stroke(width = 3f) }

    Row(Modifier.fillMaxWidth().height(150.dp)) {
        Column(
            Modifier.fillMaxHeight().padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text("%.0f W".format(maxV), style = small)
            Text("%.0f W".format(maxV / 2), style = small)
            Text("0 W", style = small)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            for (f in GRID_FRACTIONS) {
                val y = if (f == 1f) size.height - 1f else size.height * f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
            }
            fun drawSeries(vals: FloatArray, path: Path, color: Color) {
                val n = vals.size
                if (n < 2) return
                path.reset()
                var started = false
                val denom = (n - 1).toFloat()
                for (i in 0 until n) {
                    val v = vals[i]
                    if (v.isNaN()) {
                        started = false
                    } else {
                        val x = size.width * i / denom
                        val y = size.height * (1f - (v / maxV).coerceIn(0f, 1f))
                        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
                    }
                }
                drawPath(path, color, style = stroke)
            }
            drawSeries(series.batteryW, batPath, batColor)
            if (series.hasInput) drawSeries(series.inputW, inPath, inColor)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("← ${series.spanSec} 秒前", style = small)
        Text("现在", style = small)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(batColor, "电池侧功率（估算）")
        if (series.hasInput) LegendDot(inColor, "输入侧功率")
    }
    Text(
        "纵轴：功率（瓦）；横轴：时间，最右为当前。电池侧功率 = 电池端电压 × |电池电流|，是进入电池的功率，" +
            "会低于充电器输出。输入侧功率 = 充电口电压 × 电流，仅在 Root/Shizuku 能读取时显示。",
        style = small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- protocol

@Composable
private fun ProtocolTab() {
    val ui by Sampler.state.collectAsState()
    val a = ui.analysis
    if (a == null) { Text("等待首次采样…"); return }
    Section(tr("结论：${a.status.label}", "Conclusion: ${a.status.name}")) {
        Text(a.headline, style = MaterialTheme.typography.titleSmall)
        KV(tr("驱动报告协议", "Driver Protocol"), a.driver ?: tr("未报告", "None"))
        KV(tr("预测协议", "Predicted Protocol"), a.predicted ?: tr("无法预测", "Unknown"))
        a.protocolSpec?.let { spec ->
            Text(tr("协议规格说明（参考上限，不代表当前实际功率）", "Protocol Spec (Reference ceiling, not live power)"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(spec, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (a.description.isNotEmpty()) {
        Section(tr("描述", "Summary")) { Text(a.description) }
    }
    if (a.candidates.isNotEmpty()) {
        Section(tr("候选协议（仅缩小范围，★ 为与本机品牌一致）", "Candidates (★ matches device brand)")) { a.candidates.forEach { Text("• $it") } }
    }
    if (a.candidateSpecs.isNotEmpty()) {
        Section(tr("候选协议档位说明（参考上限）", "Candidate Tier Specs (Reference)")) {
            a.candidateSpecs.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    Fold(tr("证据（L4 驱动 > L3 输入 > L2 功率 > L1 品牌）", "Evidence (L4 > L3 > L2 > L1)"), tr("${a.evidence.size} 条", "${a.evidence.size}")) {
        if (a.evidence.isEmpty()) Text(tr("暂无证据", "No evidence")) else a.evidence.sortedByDescending { it.level }.forEach {
            Text("L${it.level}  ${it.text}")
        }
    }
    Section(tr("限制说明", "Limitations")) { a.limitations.forEach { Text("• $it") } }
    ui.rootError?.let { Section(tr("特权通道状态", "Privileged Channel")) { Text(it) } }
}

// ---------------------------------------------------------------- archive

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveCard(e: ArchiveEntry, collapsible: Boolean, onLongPress: () -> Unit) {
    var expanded by remember(e.wallMs, collapsible) { mutableStateOf(!collapsible) }
    val arrowAngle = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "archiveArrow",
    )
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (collapsible) expanded = !expanded },
                        onLongClick = onLongPress,
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${AppLog.time(e.wallMs)}  ·  ${e.status}", style = MaterialTheme.typography.titleSmall)
                    if (!expanded) {
                        Text(e.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.note.isNotEmpty()) {
                            Text("备注：${e.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (collapsible) Text("▸", Modifier.graphicsLayer { rotationZ = arrowAngle.value })
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)),
                exit = shrinkVertically(tween(180)),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(e.headline)
                    if (e.note.isNotEmpty()) {
                        Text("备注：${e.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    KV("设备", e.device)
                    KV("电量", e.levelPct?.let { "$it %" })
                    KV("输入电压峰值", fmt(e.maxVinV, "V", 1))
                    KV("输入功率峰值", fmt(e.maxInputW, "W", 1))
                    KV("电池侧功率峰值", fmt(e.maxBatteryW, "W", 1))
                    if (e.description.isNotEmpty()) {
                        Text("描述", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.specText.isNotEmpty()) {
                        Text("档位说明", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.specText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.candidates.isNotEmpty()) {
                        Text("候选：" + e.candidates.joinToString("；"), style = MaterialTheme.typography.bodySmall)
                    }
                    e.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ArchiveTab() {
    val items by Archive.items.collectAsState()
    val reversed = remember(items) { items.asReversed() }
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    val collapsible = items.size > 1

    editing?.let { target ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("存档备注") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text("例如：原装 120W 头 + 6A 线") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Archive.updateNote(target.wallMs, draft)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("取消") }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Section("协议识别存档（${items.size} 条，仅保存在本机）") {
                Text(
                    "当出现“已确认 / 可能 / 证据冲突”的识别结果时自动存档；同一次充电内结果变化会新增一条，拔出充电器时写入峰值。长按任意存档可添加或修改备注。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        if (confirm) { Archive.clear(); AppLog.i("已清除识别存档"); confirm = false } else confirm = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (confirm) "再次点击确认清除全部存档" else "清除存档") }
            }
        }
        if (reversed.isEmpty()) {
            item(key = "empty") { Text("暂无存档") }
        } else {
            items(reversed, key = { it.wallMs }) { e ->
                ArchiveCard(
                    e = e,
                    collapsible = collapsible,
                    onLongPress = {
                        editing = e
                        draft = e.note
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- device

@Composable
private fun DeviceTab(onOpenSettings: () -> Unit) {
    val ui by Sampler.state.collectAsState()
    val s = ui.latest
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(tr("设备", "Device"), style = MaterialTheme.typography.titleLarge)
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = tr("软件设置", "App Settings"),
            )
        }
    }
    Section(tr("设备信息", "Device Info")) {
        KV(tr("型号", "Model"), "${Build.MANUFACTURER} ${Build.MODEL}")
        KV("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        KV("SoC", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
    }
    Section(tr("特权通道读取的节点（只读）", "Privileged Nodes (Read-only)")) {
        val nodes = s?.rootNodes.orEmpty()
        if (nodes.isEmpty()) Text(ui.rootError ?: tr("未启用（在“数据”页开启 Root 或 Shizuku）", "Disabled (enable Root or Shizuku in Data tab)"))
        nodes.forEach { n ->
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.props.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------- data

private val INTERVAL_OPTS = listOf("1 秒" to 1000L, "2 秒" to 2000L, "5 秒" to 5000L)
private val TEMP_OPTS = listOf("40 °C" to 40L, "42 °C" to 42L, "45 °C" to 45L, "50 °C" to 50L)
private val MIN_OPTS = listOf("30 分钟" to 30L, "1 小时" to 60L, "2 小时" to 120L, "不限" to 0L)
private val SOC_OPTS = listOf("80%" to 80L, "90%" to 90L, "100%" to 100L, "不限" to 0L)
private val MB_OPTS = listOf("4 MB" to 4L, "8 MB" to 8L, "16 MB" to 16L)
private val PRIV_OPTS = listOf("关闭" to 0L, "Root" to 1L, "Shizuku" to 2L)

@Composable
private fun Chips(options: List<Pair<String, Long>>, selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, v) ->
            FilterChip(selected = selected == v, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun Fold(title: String, summary: String, content: @Composable () -> Unit) {
    // Local state per card so toggling one block never recomposes DataTab or sibling cards.
    var expanded by remember { mutableStateOf(false) }
    val arrowAngle = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "foldArrow",
    )
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!expanded) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Read animated value inside graphicsLayer so rotation runs on GPU without recomposing.
                Text("▸", Modifier.graphicsLayer { rotationZ = arrowAngle.value })
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)),
                exit = shrinkVertically(tween(180)),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun DataTab() {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Subscribe only to rootError and recordedRows so DataTab never recomposes on voltage/chart ticks.
    val rootError by remember { Sampler.state.map { it.rootError }.distinctUntilChanged() }.collectAsState(null)
    val recordedRows by remember { Sampler.state.map { it.recordedRows }.distinctUntilChanged() }.collectAsState(0L)

    var mode by remember(rootError) { mutableStateOf(Prefs.privMode(ctx).toLong()) }
    val shz by ShizukuBridge.stateFlow.collectAsState()
    val checking by Sampler.probing.collectAsState()
    val privMsg by Sampler.probeMsg.collectAsState()
    var rootProvider by remember { mutableStateOf<RootScanner.Provider?>(null) }
    var interval by remember { mutableStateOf(Prefs.intervalMs(ctx)) }
    var auto by remember { mutableStateOf(Prefs.autoRecord(ctx)) }
    var maxTemp by remember { mutableStateOf(Prefs.long(ctx, Prefs.TEMP, 45L)) }
    var maxMin by remember { mutableStateOf(Prefs.long(ctx, Prefs.MINUTES, 120L)) }
    var stopSoc by remember { mutableStateOf(Prefs.long(ctx, Prefs.SOC, 0L)) }
    var maxMb by remember { mutableStateOf(Prefs.long(ctx, Prefs.FILE_MB, 8L)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var diskBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootProvider = RootScanner.detectProvider(ctx)
            ShizukuBridge.refreshState()
        }
    }
    LaunchedEffect(recordedRows) {
        diskBytes = withContext(Dispatchers.IO) {
            Recorder.diskBytes(ctx) + Archive.sizeBytes() + AppLog.sizeBytes()
        }
    }

    msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> Recorder.exportTo(ctx, out) } ?: false
                } catch (e: Exception) { false }
            }
            msg = if (ok) "CSV 已导出" else "导出失败（尚无记录？）"
        }
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = Report.build(ctx, Sampler.state.value)
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: Exception) { false }
            }
            msg = if (ok) "诊断日志已导出" else "日志导出失败"
        }
    }

    Fold(tr("采样间隔", "Sampling Interval"), tr("${interval / 1000} 秒", "${interval / 1000}s")) {
        Chips(INTERVAL_OPTS, interval) { interval = it; Prefs.setIntervalMs(ctx, it) }
    }
    Fold(tr("自动记录", "Auto Record"), if (auto) tr("已开启", "On") else tr("已关闭", "Off")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("连接充电器时自动开始，拔出后停止", "Auto-start on plug-in, stop on unplug"), Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = {
                auto = it
                Prefs.setAutoRecord(ctx, it)
                SamplingService.send(ctx, if (it) SamplingService.ACTION_ARM else SamplingService.ACTION_DISARM)
            })
        }
        Text(tr("开启后会常驻一条低优先级通知（空闲时不采样）。系统重启后需打开一次应用以恢复。", "Shows a low-priority notification while idle (no sampling). Open the app once after reboot to re-arm."), style = MaterialTheme.typography.bodySmall)
    }
    Fold(
        tr("安全阈值（任一触发即停止记录）", "Safety Limits (Stop Recording)"),
        "${maxTemp}°C · " + (if (maxMin > 0) "${maxMin}m" else tr("不限时", "No limit")) + " · " + (if (stopSoc > 0) "${stopSoc}%" else tr("不限电量", "Any SOC")) + " · ${maxMb}MB",
    ) {
        Text(tr("电池温度上限", "Max Battery Temp"), style = MaterialTheme.typography.labelLarge)
        Chips(TEMP_OPTS, maxTemp) { maxTemp = it; Prefs.setLong(ctx, Prefs.TEMP, it) }
        Text(tr("单次最长时长", "Max Session Duration"), style = MaterialTheme.typography.labelLarge)
        Chips(MIN_OPTS, maxMin) { maxMin = it; Prefs.setLong(ctx, Prefs.MINUTES, it) }
        Text(tr("电量达到后停止", "Stop at SOC"), style = MaterialTheme.typography.labelLarge)
        Chips(SOC_OPTS, stopSoc) { stopSoc = it; Prefs.setLong(ctx, Prefs.SOC, it) }
        Text(tr("记录文件大小上限（压缩后）", "Max Log File Size (GZIP)"), style = MaterialTheme.typography.labelLarge)
        Chips(MB_OPTS, maxMb) { maxMb = it; Prefs.setLong(ctx, Prefs.FILE_MB, it) }
        Text(tr("阈值只会停止本应用的记录，不会控制或限制充电。", "Limits only stop recording; this app never controls charging."), style = MaterialTheme.typography.bodySmall)
    }
    Fold(
        tr("特权增强（默认关闭，只读）", "Privileged Mode (Read-only)"),
        when (mode) { 1L -> "Root"; 2L -> "Shizuku"; else -> tr("关闭", "Off") },
    ) {
        Chips(PRIV_OPTS, mode) {
            mode = it
            Sampler.onPrivModeChanged(ctx, it.toInt())
        }
        if (mode == 1L) {
            Text("Root 管理器：" + (rootProvider?.label ?: "未检测到 Magisk / KernelSU / APatch"))
            Text("兼容 Magisk、KernelSU、APatch：复用单个持久 su 会话，减少反复授权与进程开销。", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !checking,
                onClick = { Sampler.startProbe(ctx, 1) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) "正在检测…" else "检测 Root 授权") }
        }
        if (mode == 2L) {
            Text("状态：" + shz.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !checking,
                    onClick = { ShizukuBridge.requestPermission() },
                ) { Text("申请授权") }
                OutlinedButton(
                    enabled = !checking,
                    onClick = { Sampler.startProbe(ctx, 2) },
                ) { Text(if (checking) "检测中…" else "检测") }
            }
            Text(
                "Shizuku 提供 adb shell 权限：直接读取 sysfs 与 dumpsys battery，不派生额外 shell 进程。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        privMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "只执行固定只读采集，不写任何节点；失败会退避，连续失败 3 次自动关闭。",
            style = MaterialTheme.typography.bodySmall,
        )
        rootError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    Fold(tr("记录与日志", "Records & Logs"), tr("$recordedRows 行 · ${fmtBytes(diskBytes)}", "$recordedRows rows · ${fmtBytes(diskBytes)}")) {
        Text(tr("已记录 $recordedRows 行，本机总占用约 ${fmtBytes(diskBytes)}（CSV 采用 GZIP 压缩存储，导出时自动解压）。应用无联网权限，导出不含设备标识。", "$recordedRows rows recorded, ~${fmtBytes(diskBytes)} on disk (GZIP compressed, auto-decompressed on export). Offline only."))
        Button(onClick = { exportCsv.launch("chargescope.csv") }, modifier = Modifier.fillMaxWidth()) { Text(tr("导出 CSV 记录", "Export CSV")) }
        Button(onClick = { exportLog.launch("chargescope-log.txt") }, modifier = Modifier.fillMaxWidth()) { Text(tr("导出诊断日志", "Export Diagnostic Log")) }
        val clearedMsg = tr("已清除记录和日志", "Records and logs cleared")
        OutlinedButton(
            onClick = {
                if (confirmClear) {
                    Recorder.clear(ctx); AppLog.clear()
                    diskBytes = Archive.sizeBytes()
                    msg = clearedMsg; confirmClear = false
                } else confirmClear = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (confirmClear) tr("再次点击确认清除记录和日志", "Tap again to confirm clear") else tr("清除记录和日志", "Clear Records & Logs")) }
    }
}
