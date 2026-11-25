package com.chargescope.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chargescope.app.data.SettingsStore
import com.chargescope.app.service.SamplingForegroundService
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { SettingsStore(context) }

    val rootEnabled by settings.rootEnabled.collectAsState(initial = false)
    val realtime by settings.realtimeMode.collectAsState(initial = false)
    val notifications by settings.notificationsEnabled.collectAsState(initial = true)
    val retention by settings.retentionDays.collectAsState(initial = 30)
    val period by settings.samplePeriodMs.collectAsState(initial = 3_000)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        SettingSwitch("Root 只读采集（需用户设备已授权）", rootEnabled) {
            scope.launch { settings.setRootEnabled(it) }
        }
        Text(
            "仅读取电池/USB  Sysfs 属性，不写入任何节点，不调整充电策略。",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SettingSwitch("实时模式（约 1 秒请求周期）", realtime) {
            scope.launch { settings.setRealtimeMode(it) }
        }
        SettingSwitch("采样通知", notifications) {
            scope.launch { settings.setNotifications(it) }
        }
        Text("当前请求采样周期：$period ms（底层更新频率可能更低）", modifier = Modifier.padding(16.dp))
        Text("数据保留目标：$retention 天（MVP 暂不自动清理）", modifier = Modifier.padding(horizontal = 16.dp))
        Button(
            onClick = {
                SamplingForegroundService.stop(context)
                showToast(context, "采样已停止；重新进入应用将启动新会话")
            },
            modifier = Modifier.padding(16.dp),
        ) {
            Text("停止当前采样会话")
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun showToast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
