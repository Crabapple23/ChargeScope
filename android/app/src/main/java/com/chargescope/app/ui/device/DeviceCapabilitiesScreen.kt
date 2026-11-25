package com.chargescope.app.ui.device

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chargescope.app.service.SamplingForegroundService

@Composable
fun DeviceCapabilitiesScreen() {
    val snapshot by SamplingForegroundService.latestSnapshot.collectAsState()
    val scroll = rememberScrollState()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = 16.dp),
    ) {
        Text("设备能力", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        Text(
            "此页列出已达成的采集能力，不代表设备支持或当前协商了某种协议。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        InfoCard("设备型号", Build.MODEL)
        InfoCard("SoC", Build.HARDWARE)
        InfoCard("Android 版本", Build.VERSION.RELEASE)
        InfoCard("Root 采集", if (snapshot?.rootAvailable == true) "已启用（只读）" else "未启用或不可用")
        InfoCard("公开 API 电池数据", if (snapshot?.hasAnyMeasurement == true) "可用" else "暂未取得")
        InfoCard("已验证 sysfs 路径", "0（需按 docs/device-compatibility.md 完成实测后填写）")
        InfoCard("设备档案", "无已验证档案；L1 候选未启用")
    }
}

@Composable
private fun InfoCard(label: String, value: String) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
