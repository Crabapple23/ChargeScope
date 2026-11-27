package com.chargescope.app

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val GRID_FRACTIONS = floatArrayOf(0f, 0.5f, 1f)
private val NICE_STEPS = floatArrayOf(5f, 10f, 20f, 30f, 50f, 70f, 100f, 150f, 200f, 300f)

@Composable
internal fun RealtimeTab() {
    val ui by Sampler.state.collectAsState()
    val prefs by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    val isEn = LocalIsEn.current
    val s = ui.latest
    val dual = prefs.dualCell
    Section(
        if (dual) tr("电池（公开 API · 双电芯）", "Battery (Public API · Dual-Cell)")
        else tr("电池（公开 API）", "Battery (Public API)"),
    ) {
        KV(tr("电量", "Level"), s?.levelPct?.let { "$it %" })
        KV(tr("状态", "Status"), statusText(s?.status, isEn))
        KV(tr("连接方式", "Plugged"), s?.let { pluggedText(it.plugged, isEn) })
        KV(tr("电池端电压", "Battery Voltage"), fmt(s?.voltageMv?.let { it / 1000.0 }, "V"))
        KV(tr("电池电流（绝对值）", "Battery Current"), fmt(ui.batteryCurrentMa, "mA", 0))
        KV(tr("电池侧功率（估算）", "Battery Power (Est.)"), fmt(ui.batteryPowerW, "W"))
        KV(tr("输入侧功率（需 Root/Shizuku）", "Input Power (Root/Shizuku)"), fmt(ui.inputPowerW, "W"))
        Analyzer.efficiencyPct(ui.batteryPowerW, ui.inputPowerW)?.let { eff ->
            KV(tr("估算转换效率", "Est. Efficiency"), "%.1f %%".format(eff))
        }
        KV(tr("温度", "Temperature"), fmt(s?.tempDeci?.let { it / 10.0 }, "°C", 1))
        val hText = healthText(s?.health, isEn)
        val hPct = s?.let { Analyzer.batteryHealthPct(it, dual) }
        val designMah = s?.let { Analyzer.designCapacityMah(it, dual) }
        val usableMah = s?.let { Analyzer.usableCapacityMah(it, dual) }
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
