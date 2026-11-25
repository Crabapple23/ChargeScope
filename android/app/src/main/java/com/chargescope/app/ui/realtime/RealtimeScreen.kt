package com.chargescope.app.ui.realtime

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.service.SamplingForegroundService
import com.chargescope.app.ui.common.ValueCard
import kotlin.math.roundToInt

@Composable
fun RealtimeScreen() {
    val snapshot by SamplingForegroundService.latestSnapshot.collectAsState()
    val analysis by SamplingForegroundService.analysis.collectAsState()
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = 16.dp),
    ) {
        Text(
            "实时监控",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            "采样周期为应用请求值；底层硬件可能以更低频率更新。重复值会被标记为陈旧。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        ValueCard(
            label = "SOC",
            value = snapshot?.socPercent?.toString(),
            unit = "%",
            quality = if (snapshot?.socPercent != null) DataQuality.VALID else DataQuality.MISSING,
            source = "BatteryManager",
        )
        ValueCard(
            label = "电池电压（电池侧）",
            value = snapshot?.batteryVoltageUv?.let { String.format("%.3f", it / 1_000_000.0) },
            unit = "V",
            quality = if (snapshot?.batteryVoltageUv != null) DataQuality.VALID else DataQuality.MISSING,
            source = "BatteryManager.EXTRA_VOLTAGE",
        )
        ValueCard(
            label = "电池电流（电池侧，方向依设备校准）",
            value = snapshot?.batteryCurrentUa?.let { String.format("%.3f", it / 1_000_000.0) },
            unit = "A",
            quality = if (snapshot?.batteryCurrentUa != null) DataQuality.VALID else DataQuality.MISSING,
            source = "BATTERY_PROPERTY_CURRENT_NOW",
        )
        ValueCard(
            label = "电池功率（电池侧，非 USB 输入功率）",
            value = snapshot?.batteryPowerUw?.let { String.format("%.2f", it / 1_000_000.0) },
            unit = "W",
            quality = if (snapshot?.batteryPowerUw != null) DataQuality.VALID else DataQuality.MISSING,
            source = "Rust 计算",
        )
        ValueCard(
            label = "输入侧功率（实测）",
            value = snapshot?.inputPowerUw?.let { String.format("%.2f", it / 1_000_000.0) },
            unit = "W",
            quality = if (snapshot?.inputPowerUw != null) DataQuality.VALID else DataQuality.MISSING,
            source = "Root 只读 sysfs（需设备验证）",
        )
        ValueCard(
            label = "电池温度",
            value = snapshot?.batteryTemperatureDecic?.let { String.format("%.1f", it / 10.0) },
            unit = "°C",
            quality = if (snapshot?.batteryTemperatureDecic != null) DataQuality.VALID else DataQuality.MISSING,
            source = "BatteryManager",
        )
        ValueCard(
            label = "SOC 变化速率",
            value = analysis?.metrics?.socRatePercentPerMin?.let { String.format("%.2f", it) },
            unit = "%/min",
            quality = if (analysis?.metrics?.socRatePercentPerMin != null) DataQuality.VALID else DataQuality.MISSING,
            source = "单调时钟 + 重复值过滤",
        )
        ValueCard(
            label = "采样陈旧比例",
            value = analysis?.metrics?.staleRatio?.let { String.format("%.0f%%", (it * 100).roundToInt()) },
            source = "Rust 分析",
        )
    }
}
