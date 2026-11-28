package com.chargescope.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val GRID_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)
private val NICE_STEPS = floatArrayOf(5f, 10f, 20f, 30f, 50f, 70f, 100f, 150f, 200f, 300f)

@Composable
internal fun RealtimeTab() {
    val ui by Sampler.state.collectAsState()
    val prefs by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    val isEn = LocalIsEn.current
    val miuix = LocalIsMiuix.current
    val s = ui.latest
    val dual = prefs.dualCell
    var detailsExpanded by remember { mutableStateOf(false) }
    val arrowAngle = animateFloatAsState(
        targetValue = if (detailsExpanded) 90f else 0f,
        animationSpec = if (miuix) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium) else tween(180),
        label = "batteryCardArrow",
    )

    val batteryCardTitle = if (dual) tr("电池与充电状态 · 双电芯", "Battery & Charging · Dual-Cell")
        else tr("电池与充电状态", "Battery & Charging")
    val etaText = Analyzer.estimateChargeTimeRemainingMs(s, ui.metrics)?.let { (ms, fromSys) ->
        val dur = fmtDuration(ms, isEn)
        if (fromSys) dur else dur + tr("（速率估算）", " (est.)")
    }
    val batteryCardBody: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { detailsExpanded = !detailsExpanded }
                .padding(if (miuix) 16.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (miuix) 8.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    batteryCardTitle,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (miuix) FontWeight.Medium else FontWeight.Normal,
                )
                Text(
                    if (detailsExpanded) tr("收起", "Less") else tr("更多详情", "More"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("▸", Modifier.graphicsLayer { rotationZ = arrowAngle.value })
            }
            val hText = healthText(s?.health, isEn)
            val driverSoh = s?.let { Analyzer.driverSohPct(it) }
            val cycles = s?.let { Analyzer.batteryCycleCount(it) }
            val healthDisplay = buildList {
                if (hText != null) add(hText)
                if (driverSoh != null) add("$driverSoh%")
                if (cycles != null) add(tr("${cycles}次循环", "$cycles cycles"))
            }.joinToString(" · ").ifEmpty { null }
            // Key live metrics always visible
            KV(tr("电量", "Level"), s?.levelPct?.let { "$it %" })
            KV(tr("状态", "Status"), statusText(s?.status, isEn))
            KV(tr("电池侧功率（估算）", "Battery Power (Est.)"), fmt(ui.batteryPowerW, "W"))
            KV(tr("温度", "Temperature"), fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1))
            KV(tr("健康", "Health"), healthDisplay)
            KV(tr("预计充满时间", "Est. Time to Full"), etaText)

            // Secondary electrical & battery-health metrics expanded on tap
            AnimatedVisibility(
                visible = detailsExpanded,
                enter = if (miuix) expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)) else expandVertically(tween(200)),
                exit = if (miuix) shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)) + fadeOut(tween(140)) else shrinkVertically(tween(180)),
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(if (miuix) 8.dp else 6.dp),
                ) {
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    KV(tr("连接方式", "Plugged"), s?.let { pluggedText(it.plugged, isEn) })
                    KV(tr("电池端电压", "Battery Voltage"), fmt(s?.voltageMv?.let { it / 1000.0 }, "V"))
                    KV(tr("电池电流（绝对值）", "Battery Current"), fmt(ui.batteryCurrentMa, "mA", 0))
                    KV(tr("输入侧功率（需 Root/Shizuku）", "Input Power (Root/Shizuku)"), fmt(ui.inputPowerW, "W"))
                    Analyzer.efficiencyPct(ui.batteryPowerW, ui.inputPowerW)?.let { eff ->
                        KV(tr("估算转换效率", "Est. Efficiency"), "%.1f %%".format(eff))
                    }
                    val calcHealth = s?.let { Analyzer.calculatedHealthPct(it, dual) }
                    val designMah = s?.let { Analyzer.designCapacityMah(it, dual) }
                    val usableMah = s?.let { Analyzer.usableCapacityMah(it, dual) }
                    KV(tr("估算健康", "Est. Health"), calcHealth?.let { "$it %" })
                    KV(tr("电池设计容量", "Design Capacity"), designMah?.let { "$it mAh" })
                    KV(tr("电池可用上限容量", "Usable Capacity"), usableMah?.let { "$it mAh" })
                }
            }
        }
    }
    if (miuix) MiuixCard { batteryCardBody() } else Card(Modifier.fillMaxWidth()) { batteryCardBody() }
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
    AppButton(
        text = if (ui.recording) tr("停止记录（已写入 ${ui.recordedRows} 行）", "Stop Recording (${ui.recordedRows} rows)")
        else tr("开始后台记录", "Start Background Recording"),
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
    )
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    // Use complementary high-contrast colors so both curves stay distinct even under Monet dynamic themes.
    val batColor = if (isDark) Color(0xFF4FC3F7) else Color(0xFF1976D2)
    val inColor = if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (series.validCount < 2) {
        Text(tr("等待数据…（至少需要 2 个采样点）", "Waiting for data… (at least 2 samples needed)"))
        return
    }
    val maxV = niceMax(series.maxW)
    val small = MaterialTheme.typography.labelSmall
    val density = LocalDensity.current
    val stroke = remember(density) {
        Stroke(width = with(density) { 2.2.dp.toPx() }, cap = StrokeCap.Round, join = StrokeJoin.Round)
    }
    val batPath = remember { Path() }
    val inPath = remember { Path() }

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
            "纵轴：功率（瓦）；横轴：时间，最右为当前。电池侧功率 = 电池端电压 × |电池电流|（双电芯模式下上报单芯电压时按 ×2 换算），" +
                "低于充电器输出。输入侧功率 = 充电口电压 × 电流，仅在 Root/Shizuku 可读到输入节点时显示。",
            "Y-axis: power (W); X-axis: time (now on right). Battery power = V_bat × |I_bat| (×2 in dual-cell mode for single-cell voltage readings). Input power requires readable Root/Shizuku input nodes.",
        ),
        style = small,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
