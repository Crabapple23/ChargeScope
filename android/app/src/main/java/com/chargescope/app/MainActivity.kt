package com.chargescope.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        // Initialize disk-backed stores off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            AppLog.init(applicationContext)
            Archive.init(applicationContext)
            Recorder.init(applicationContext)
        }
        // Re-arm auto-record after onboarding (service may have been killed); allowed because we are in the foreground.
        if (Prefs.onboardingDone(this) && Prefs.autoRecord(this)) {
            SamplingService.send(this, SamplingService.ACTION_ARM)
        }
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
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!Prefs.onboardingDone(ctx)) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEdge by remember { mutableIntStateOf(0) }
    var lastProtocolTapMs by remember { mutableLongStateOf(0L) }

    val dark = when (prefs.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val baseColors = when {
        prefs.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // OLED pure black overrides only surface/background layers in dark mode, preserving Monet accent colors.
    val colors = if (dark && prefs.oledBlack) {
        baseColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0C0C0C),
            surfaceContainer = Color(0xFF080808),
            surfaceContainerHigh = Color(0xFF141414),
            surfaceContainerHighest = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF141414),
        )
    } else {
        baseColors
    }
    val view = LocalView.current
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
            BackHandler(enabled = showOnboarding) {
                // Prevent accidental dismissal of the first-launch guide via back gesture.
            }
            BackHandler(enabled = !showOnboarding && showSettings && !prefs.predictiveBack) { showSettings = false }
            PredictiveBackHandler(enabled = !showOnboarding && showSettings && prefs.predictiveBack) { progress ->
                try {
                    progress.collect { ev ->
                        backProgress = ev.progress
                        backEdge = ev.swipeEdge
                    }
                    showSettings = false
                } catch (_: CancellationException) {
                    // Swipe cancelled; restore full scale.
                } finally {
                    backProgress = 0f
                }
            }
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            TABS.forEachIndexed { i, t ->
                                val label = tr(t.zh, t.en)
                                val archivedToast = tr("已存档当前协议状态", "Current protocol archived")
                                val noDataToast = tr("暂无采样数据，无法存档", "No sample yet")
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = {
                                        if (i == 1) {
                                            val now = SystemClock.elapsedRealtime()
                                            if (now - lastProtocolTapMs <= 400L) {
                                                lastProtocolTapMs = 0L
                                                val ok = Sampler.archiveNow(ctx)
                                                Toast.makeText(ctx, if (ok) archivedToast else noDataToast, Toast.LENGTH_SHORT).show()
                                            } else {
                                                lastProtocolTapMs = now
                                            }
                                        } else {
                                            lastProtocolTapMs = 0L
                                        }
                                        tab = i
                                    },
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
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = backProgress
                                if (p > 0f) {
                                    val s = 1f - p * 0.12f
                                    scaleX = s
                                    scaleY = s
                                    // Edge 0 = left swipe (shift right), Edge 1 = right swipe (shift left).
                                    val dir = if (backEdge == 1) -1f else 1f
                                    translationX = size.width * p * 0.14f * dir
                                    shape = RoundedCornerShape((p * 28f).dp)
                                    clip = true
                                }
                            },
                    ) {
                        SettingsScreen(
                            prefs = prefs,
                            onBack = { showSettings = false },
                            onOpenOnboarding = {
                                showSettings = false
                                showOnboarding = true
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showOnboarding,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    OnboardingScreen(
                        onFinish = {
                            Prefs.setOnboardingDone(ctx, true)
                            showOnboarding = false
                            if (Prefs.autoRecord(ctx) && hasNotificationPermission(ctx)) {
                                SamplingService.send(ctx, SamplingService.ACTION_ARM)
                            }
                        },
                    )
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

private fun fmtDuration(ms: Long, isEn: Boolean): String {
    val totalMin = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
    if (totalMin < 60L) return if (isEn) "${totalMin}m" else "${totalMin} 分钟"
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        m == 0L -> if (isEn) "${h}h" else "${h} 小时"
        isEn -> "${h}h ${m}m"
        else -> "${h} 小时 ${m} 分钟"
    }
}

private fun statusText(s: Int?, isEn: Boolean = false) = if (isEn) when (s) {
    2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not Charging"; 5 -> "Full"; else -> null
} else when (s) {
    2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null
}

private fun pluggedText(p: Int, isEn: Boolean = false) = if (isEn) when (p) {
    0 -> "Unplugged"; 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; else -> "Other($p)"
} else when (p) {
    0 -> "未连接"; 1 -> "交流电源"; 2 -> "USB"; 4 -> "无线"; 8 -> "底座"; else -> "其他($p)"
}

private fun healthText(h: Int?, isEn: Boolean = false) = if (isEn) when (h) {
    2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over Voltage"; 6 -> "Failure"; 7 -> "Cold"; else -> null
} else when (h) {
    2 -> "良好"; 3 -> "过热"; 4 -> "损坏"; 5 -> "过压"; 6 -> "故障"; 7 -> "过冷"; else -> null
}

// ---------------------------------------------------------------- realtime

@Composable
private fun RealtimeTab() {
    val ui by Sampler.state.collectAsState()
    val ctx = LocalContext.current
    val isEn = LocalIsEn.current
    val s = ui.latest
    Section(tr("电池（公开 API）", "Battery (Public API)")) {
        KV(tr("电量", "Level"), s?.levelPct?.let { "$it %" })
        KV(tr("状态", "Status"), statusText(s?.status, isEn))
        KV(tr("连接方式", "Plugged"), s?.let { pluggedText(it.plugged, isEn) })
        KV(tr("电池端电压", "Battery Voltage"), fmt(s?.voltageMv?.let { it / 1000.0 }, "V"))
        KV(tr("电池电流（绝对值）", "Battery Current"), fmt(s?.let { Analyzer.currentMa(it) }, "mA", 0))
        KV(tr("电池侧功率（估算）", "Battery Power (Est.)"), fmt(ui.batteryPowerW, "W"))
        KV(tr("输入侧功率（需 Root/Shizuku）", "Input Power (Root/Shizuku)"), fmt(ui.inputPowerW, "W"))
        Analyzer.efficiencyPct(ui.batteryPowerW, ui.inputPowerW)?.let { eff ->
            KV(tr("估算转换效率", "Est. Efficiency"), "%.1f %%".format(eff))
        }
        KV(tr("温度", "Temperature"), fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1))
        val hText = healthText(s?.health, isEn)
        val hPct = s?.let { Analyzer.batteryHealthPct(it) }
        val designMah = s?.let { Analyzer.designCapacityMah(it) }
        val usableMah = s?.let { Analyzer.usableCapacityMah(it) }
        val cycles = s?.let { Analyzer.batteryCycleCount(it) }
        val healthDisplay = buildList {
            if (hText != null) add(hText)
            if (cycles != null) add(tr("${cycles}次循环", "$cycles cycles"))
        }.joinToString(" · ").ifEmpty { null }
        KV(tr("健康", "Health"), healthDisplay)
        KV(tr("估算健康", "Est. Health"), hPct?.let { "$it %" })
        KV(tr("电池设计容量", "Design Capacity"), designMah?.let { "$it mAh" })
        KV(tr("电池可用上限容量", "Usable Capacity"), usableMah?.let { "$it mAh" })
        val etaText = Analyzer.estimateChargeTimeRemainingMs(s, ui.metrics)?.let { (ms, fromSys) ->
            val dur = fmtDuration(ms, isEn)
            if (fromSys) dur else dur + tr("（速率估算）", " (est.)")
        }
        KV(tr("预计充满时间", "Est. Time to Full"), etaText)
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
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        SamplingService.send(ctx, null)
    }
    Button(
        onClick = {
            if (ui.recording) {
                SamplingService.send(ctx, SamplingService.ACTION_STOP_REC)
            } else if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(ctx)) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                SamplingService.send(ctx, null)
            }
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
        Text(tr("等待数据…（至少需要 2 个采样点）", "Waiting for data… (at least 2 samples needed)"))
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
        Text(tr("← ${series.spanSec} 秒前", "← ${series.spanSec}s ago"), style = small)
        Text(tr("现在", "Now"), style = small)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendDot(batColor, tr("电池侧功率（估算）", "Battery Power (Est.)"))
        if (series.hasInput) LegendDot(inColor, tr("输入侧功率", "Input Power"))
    }
    Text(
        tr(
            "纵轴：功率（瓦）；横轴：时间，最右为当前。电池侧功率 = 电池端电压 × |电池电流|，是进入电池的功率，" +
                "会低于充电器输出。输入侧功率 = 充电口电压 × 电流，仅在 Root/Shizuku 能读取时显示。",
            "Y-axis: power (W); X-axis: time (now on right). Battery power = V_bat × |I_bat| (lower than charger output). Input power requires Root/Shizuku.",
        ),
        style = small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- protocol

@Composable
private fun ProtocolTab() {
    val ui by Sampler.state.collectAsState()
    val a = ui.analysis
    if (a == null) { Text(tr("等待首次采样…", "Waiting for first sample…")); return }
    Section(tr("结论：${a.status.label}", "Conclusion: ${a.status.name}")) {
        Text(a.headline, style = MaterialTheme.typography.titleSmall)
        KV(tr("驱动报告协议", "Driver Protocol"), a.driver ?: tr("未报告", "None"))
        KV(tr("预测当前协议", "Predicted Current Protocol"), a.predicted ?: tr("无法预测", "Unknown"))
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
                    if (e.note.isNotEmpty()) {
                        Text(
                            e.note,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${AppLog.time(e.wallMs)}  ·  ${e.status}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("${AppLog.time(e.wallMs)}  ·  ${e.status}", style = MaterialTheme.typography.titleSmall)
                    }
                    if (!expanded) {
                        Text(e.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    KV(tr("设备", "Device"), e.device)
                    KV(tr("电量", "Level"), e.levelPct?.let { "$it %" })
                    KV(tr("输入电压峰值", "Peak Input Voltage"), fmt(e.maxVinV, "V", 1))
                    KV(tr("输入功率峰值", "Peak Input Power"), fmt(e.maxInputW, "W", 1))
                    KV(tr("电池侧功率峰值", "Peak Battery Power"), fmt(e.maxBatteryW, "W", 1))
                    if (e.description.isNotEmpty()) {
                        Text(tr("描述", "Summary"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.specText.isNotEmpty()) {
                        Text(tr("档位说明", "Tier Specs"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.specText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.candidates.isNotEmpty()) {
                        Text(tr("候选：", "Candidates: ") + e.candidates.joinToString("；"), style = MaterialTheme.typography.bodySmall)
                    }
                    e.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun ArchiveTab() {
    val ctx = LocalContext.current
    val items by Archive.items.collectAsState()
    val reversed = remember(items) { items.asReversed() }
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirmDeleteOne by remember { mutableStateOf(false) }
    val collapsible = items.size > 1
    val archivedToast = tr("已增加当前状态为存档", "Saved current state to archive")
    val noDataToast = tr("暂无采样数据，无法存档", "No sample yet")
    val deletedToast = tr("已删除该条存档", "Archive entry deleted")

    editing?.let { target ->
        AlertDialog(
            onDismissRequest = {
                editing = null
                confirmDeleteOne = false
            },
            title = { Text(tr("管理存档", "Manage Archive")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(tr("备注（优先显示）", "Note (shown first)")) },
                        placeholder = { Text(tr("例如：原装 120W 头 + 6A 线", "e.g., OEM 120W brick + 6A cable")) },
                        singleLine = false,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(
                        onClick = {
                            if (confirmDeleteOne) {
                                if (Archive.delete(target.wallMs)) {
                                    AppLog.i("已删除一条识别存档")
                                    Toast.makeText(ctx, deletedToast, Toast.LENGTH_SHORT).show()
                                }
                                editing = null
                                confirmDeleteOne = false
                            } else {
                                confirmDeleteOne = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (confirmDeleteOne) tr("再次点击确认删除此存档", "Tap again to confirm delete")
                            else tr("删除此存档", "Delete This Entry"),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Archive.updateNote(target.wallMs, draft)
                    editing = null
                    confirmDeleteOne = false
                }) { Text(tr("保存", "Save")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    editing = null
                    confirmDeleteOne = false
                }) { Text(tr("取消", "Cancel")) }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "header") {
            Section(tr("协议识别存档（${items.size} 条，仅保存在本机）", "Protocol Archive (${items.size}, local only)")) {
                Text(
                    tr(
                        "当出现“已确认 / 可能 / 证据冲突”的识别结果时自动存档；同一次充电内结果变化会新增一条，拔出充电器时写入峰值。长按任意存档可编辑备注或删除。",
                        "Auto-saved on Confirmed / Likely / Conflicting results; updates peaks on unplug. Long-press any entry to edit note or delete.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        if (confirm) { Archive.clear(); AppLog.i("已清除识别存档"); confirm = false } else confirm = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (confirm) tr("再次点击确认清除全部存档", "Tap again to clear all") else tr("清除存档", "Clear Archive")) }
                Button(
                    onClick = {
                        val ok = Sampler.archiveNow(ctx)
                        Toast.makeText(ctx, if (ok) archivedToast else noDataToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("增加当前为存档", "Save Current as Archive")) }
            }
        }
        if (reversed.isEmpty()) {
            item(key = "empty") { Text(tr("暂无存档", "No archives yet")) }
        } else {
            items(reversed, key = { it.wallMs }) { e ->
                ArchiveCard(
                    e = e,
                    collapsible = collapsible,
                    onLongPress = {
                        editing = e
                        draft = e.note
                        confirmDeleteOne = false
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- device

@Composable
private fun DeviceTab(onOpenSettings: () -> Unit) {
    val ctx = LocalContext.current
    val ui by Sampler.state.collectAsState()
    val s = ui.latest
    val screenInfo = remember(ctx) {
        val dm = ctx.resources.displayMetrics
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val (w, h) = if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            dm.widthPixels to dm.heightPixels
        }
        val hz = if (Build.VERSION.SDK_INT >= 30) {
            try { ctx.display?.refreshRate } catch (_: Exception) { null }
        } else {
            @Suppress("DEPRECATION") wm.defaultDisplay?.refreshRate
        }
        "${w}×${h} · ${dm.densityDpi} dpi" + (hz?.let { " · %.0f Hz".format(it) } ?: "")
    }
    val kernelInfo = remember {
        val ver = System.getProperty("os.version")
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        when {
            !ver.isNullOrBlank() && abi != null -> "$ver ($abi)"
            !ver.isNullOrBlank() -> ver
            else -> null
        }
    }
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
        KV(tr("品牌", "Brand"), if (Build.BRAND.equals(Build.MANUFACTURER, true)) Build.BRAND else "${Build.BRAND} (${Build.MANUFACTURER})")
        KV(tr("型号", "Model"), Build.MODEL)
        KV("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        KV("SoC", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else null)
        KV(tr("屏幕", "Display"), screenInfo)
        KV(tr("内核", "Kernel"), kernelInfo)
    }
    val deepScanning by Sampler.deepScanning.collectAsState()
    val deepScan by Sampler.deepScan.collectAsState()
    Section(tr("特权通道读取的节点（只读）", "Privileged Nodes (Read-only)")) {
        val nodes = s?.rootNodes.orEmpty()
        if (nodes.isEmpty()) Text(ui.rootError ?: tr("未启用（在“数据”页开启 Root 或 Shizuku）", "Disabled (enable Root or Shizuku in Data tab)"))
        nodes.forEach { n ->
            Text(n.name, style = MaterialTheme.typography.titleSmall)
            Text(n.props.entries.joinToString("  ") { "${it.key}=${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(
            enabled = !deepScanning,
            onClick = { Sampler.startDeepScan(ctx) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (deepScanning) tr("深度扫描中…", "Deep Scanning…") else tr("一键深度扫描（全量充电节点）", "Deep Scan All Charging Nodes"))
        }
        Text(
            tr("扫描本机全部可读的 power_supply / Type-C / 厂商快充节点，结果会自动附带在“导出诊断日志”中。", "Scans all readable power_supply, Type-C, and vendor nodes. Included in exported diagnostic logs."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    deepScan?.let { report ->
        Fold(tr("深度扫描结果", "Deep Scan Output"), tr("${report.lines().size} 行", "${report.lines().size} lines")) {
            Text(report.take(4000), style = MaterialTheme.typography.bodySmall)
            if (report.length > 4000) {
                Text(tr("（已截断，完整内容请点“数据 → 导出诊断日志”查看）", "(Truncated; export diagnostic log for full output)"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
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
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
    var stopSoc by remember { mutableStateOf(Prefs.long(ctx, Prefs.SOC, 100L)) }
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

    val csvOkMsg = tr("CSV 已导出", "CSV exported")
    val csvFailMsg = tr("导出失败（尚无记录？）", "Export failed (no records yet?)")
    val logOkMsg = tr("诊断日志已导出", "Diagnostic log exported")
    val logFailMsg = tr("日志导出失败", "Log export failed")

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> Recorder.exportTo(ctx, out) } ?: false
                } catch (e: Exception) { false }
            }
            msg = if (ok) csvOkMsg else csvFailMsg
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
            msg = if (ok) logOkMsg else logFailMsg
        }
    }

    val autoNotifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        auto = true
        Prefs.setAutoRecord(ctx, true)
        SamplingService.send(ctx, SamplingService.ACTION_ARM)
    }

    val isEn = LocalIsEn.current
    val intervalOpts = remember(isEn) {
        if (isEn) listOf("1s" to 1000L, "2s" to 2000L, "5s" to 5000L) else INTERVAL_OPTS
    }
    val minOpts = remember(isEn) {
        if (isEn) listOf("30m" to 30L, "1h" to 60L, "2h" to 120L, "No limit" to 0L) else MIN_OPTS
    }
    val socOpts = remember(isEn) {
        if (isEn) listOf("80%" to 80L, "90%" to 90L, "100%" to 100L, "Any" to 0L) else SOC_OPTS
    }
    val privOpts = remember(isEn) {
        if (isEn) listOf("Off" to 0L, "Root" to 1L, "Shizuku" to 2L) else PRIV_OPTS
    }

    Fold(tr("采样间隔", "Sampling Interval"), tr("${interval / 1000} 秒", "${interval / 1000}s")) {
        Chips(intervalOpts, interval) { interval = it; Prefs.setIntervalMs(ctx, it) }
    }
    Fold(tr("自动记录", "Auto Record"), if (auto) tr("已开启", "On") else tr("已关闭", "Off")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("连接充电器时自动开始，拔出后停止", "Auto-start on plug-in, stop on unplug"), Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = {
                if (it && Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(ctx)) {
                    autoNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    auto = it
                    Prefs.setAutoRecord(ctx, it)
                    SamplingService.send(ctx, if (it) SamplingService.ACTION_ARM else SamplingService.ACTION_DISARM)
                }
            })
        }
        Text(tr("开启后会常驻一条低优先级通知（空闲时不采样）。系统重启后需打开一次应用以恢复。", "Shows a low-priority notification while idle (no sampling). Open the app once after reboot to re-arm."), style = MaterialTheme.typography.bodySmall)
    }
    Fold(
        tr("安全阈值", "Safety Limits"),
        "${maxTemp}°C · " + (if (maxMin > 0) "${maxMin}min" else tr("不限时", "No limit")) + " · " + (if (stopSoc > 0) "${stopSoc}%" else tr("不限", "Any")) + " · ${maxMb}MB",
    ) {
        Text(
            tr("达到以下任一上限时自动停止记录（仅停止采样，不影响手机充电）：", "Auto-stops recording when any limit is reached (never controls charging):"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(tr("电池温度上限", "Max Battery Temp"), style = MaterialTheme.typography.labelLarge)
        Chips(TEMP_OPTS, maxTemp) { maxTemp = it; Prefs.setLong(ctx, Prefs.TEMP, it) }
        Text(tr("单次最长时长", "Max Session Duration"), style = MaterialTheme.typography.labelLarge)
        Chips(minOpts, maxMin) { maxMin = it; Prefs.setLong(ctx, Prefs.MINUTES, it) }
        Text(tr("电量达到后停止", "Stop at SOC"), style = MaterialTheme.typography.labelLarge)
        Chips(socOpts, stopSoc) { stopSoc = it; Prefs.setLong(ctx, Prefs.SOC, it) }
        Text(tr("记录文件大小上限（压缩后）", "Max Log File Size (GZIP)"), style = MaterialTheme.typography.labelLarge)
        Chips(MB_OPTS, maxMb) { maxMb = it; Prefs.setLong(ctx, Prefs.FILE_MB, it) }
    }
    Fold(
        tr("特权增强（默认关闭，只读）", "Privileged Mode (Read-only)"),
        when (mode) { 1L -> "Root"; 2L -> "Shizuku"; else -> tr("关闭", "Off") },
    ) {
        Chips(privOpts, mode) {
            mode = it
            Sampler.onPrivModeChanged(ctx, it.toInt())
        }
        if (mode == 1L) {
            Text(tr("Root 管理器：", "Root Manager: ") + (rootProvider?.label ?: tr("未检测到 Magisk / KernelSU / APatch", "Magisk / KernelSU / APatch not detected")))
            Text(tr("兼容 Magisk、KernelSU、APatch：复用单个持久 su 会话，减少反复授权与进程开销。", "Supports Magisk, KernelSU, and APatch via one persistent su session."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !checking,
                onClick = { Sampler.startProbe(ctx, 1) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) tr("正在检测…", "Probing…") else tr("检测 Root 授权", "Test Root Access")) }
        }
        if (mode == 2L) {
            Text(tr("状态：", "Status: ") + shz.label)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !checking,
                    onClick = { ShizukuBridge.requestPermission() },
                ) { Text(tr("申请授权", "Request Access")) }
                OutlinedButton(
                    enabled = !checking,
                    onClick = { Sampler.startProbe(ctx, 2) },
                ) { Text(if (checking) tr("检测中…", "Probing…") else tr("检测", "Test")) }
            }
            Text(
                tr("Shizuku 提供 adb shell 权限：直接读取 sysfs 与 dumpsys battery，不派生额外 shell 进程。", "Shizuku provides adb shell access to read sysfs and dumpsys battery without extra shells."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        privMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            tr("只执行固定只读采集，不写任何节点；失败会退避，连续失败 3 次自动关闭。", "Fixed read-only queries only; backs off on errors and auto-disables after 3 consecutive failures."),
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
