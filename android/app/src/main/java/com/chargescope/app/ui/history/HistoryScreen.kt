package com.chargescope.app.ui.history

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chargescope.app.data.Repository
import com.chargescope.app.domain.model.ChargingSession
import com.chargescope.app.export.Exporters
import com.chargescope.app.service.SamplingForegroundService
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context) { Repository(context) }
    val sessions by repository.sessions.collectAsState(initial = emptyList())
    val bundle by SamplingForegroundService.analysis.collectAsState()
    val exporter = Exporters(context, repository)

    var pendingExport by remember { mutableStateOf<Pair<Long, Boolean>?>(null) } // sessionId, isJson
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val request = pendingExport ?: return@rememberLauncherForActivityResult
        uri?.let {
            scope.launch {
                if (request.second) {
                    exporter.exportJson(request.first, it, bundle, redact = true)
                } else {
                    exporter.exportCsv(request.first, it, redact = true)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("历史记录", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))
        Text(
            exporter.privacyNotice,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyColumn {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onCsv = {
                        pendingExport = session.id to false
                        exportLauncher.launch("chargescope-session-${session.id}.csv")
                    },
                    onJson = {
                        pendingExport = session.id to true
                        exportLauncher.launch("chargescope-session-${session.id}.json")
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: ChargingSession, onCsv: () -> Unit, onJson: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("会话 #${session.id} · ${session.deviceModel ?: "未知设备"}")
            Text(
                "开始：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(session.startTimeMs))}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Text("峰值功率：${session.peakPowerUw?.let { "${it / 1_000_000.0} W" } ?: "不可用"}")
                Text(" · 样本 ${session.sampleCount}", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                Button(onClick = onCsv) { Text("导出 CSV") }
                Button(onClick = onJson, modifier = Modifier.padding(start = 8.dp)) { Text("导出 JSON") }
            }
        }
    }
}
