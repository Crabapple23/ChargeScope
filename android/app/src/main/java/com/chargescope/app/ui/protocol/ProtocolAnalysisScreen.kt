package com.chargescope.app.ui.protocol

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.chargescope.app.ui.common.LevelBadge
import com.chargescope.app.ui.common.StatusChip

@Composable
fun ProtocolAnalysisScreen() {
    val bundle by SamplingForegroundService.analysis.collectAsState()
    val analysis = bundle?.analysis
    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(bottom = 16.dp),
    ) {
        Text("协议分析", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(16.dp))

        if (analysis == null) {
            Text(
                "尚未取得足够数据。应用不会在证据不足时给出协议结论。",
                modifier = Modifier.padding(16.dp),
            )
        } else {
            Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                StatusChip(analysis.status)
                analysis.primaryCandidate?.let {
                    Text(
                        "候选：${it.label}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Text(
                analysis.explanation,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )

            if (analysis.candidates.isNotEmpty()) {
                SectionTitle("候选协议（排序不是概率）")
                analysis.candidates.forEach { candidate ->
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row {
                                Text(candidate.protocol.label, style = MaterialTheme.typography.titleMedium)
                                LevelBadge(candidate.evidenceLevel)
                            }
                            Text("排序分：${candidate.score.round()}（原型权重，非百分比概率）")
                            candidate.reasons.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }

            if (analysis.conflicts.isNotEmpty()) {
                SectionTitle("证据冲突")
                analysis.conflicts.forEach {
                    Text(
                        it.description,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            SectionTitle("证据明细")
            analysis.evidence.forEach { e ->
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            LevelBadge(e.level)
                            Text(
                                if (e.supports) "支持" else "反证",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        Text(e.observation)
                        Text("来源：${e.source} · 质量：${e.quality}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionTitle("限制说明")
            analysis.limitations.forEach {
                Text("· $it", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "算法版本：${analysis.algorithmVersion}",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun Double.round(): String = String.format("%.0f", this)
