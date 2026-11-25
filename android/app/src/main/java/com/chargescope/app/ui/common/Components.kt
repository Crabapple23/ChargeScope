package com.chargescope.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chargescope.app.domain.model.AnalysisStatus
import com.chargescope.app.domain.model.DataQuality
import com.chargescope.app.domain.model.EvidenceLevel

/** Value display: null is never shown as 0. */
@Composable
fun ValueCard(
    label: String,
    value: String?,
    unit: String? = null,
    quality: DataQuality? = null,
    source: String? = null,
    modifier: Modifier = Modifier,
    onSourceClick: (() -> Unit)? = null,
) {
    Card(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = if (value == null) "不可用" else "$value${unit ?: ""}",
                style = MaterialTheme.typography.headlineSmall,
                color = if (value == null) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quality?.let { AssistChip(onClick = {}, label = { Text(it.name) }) }
                source?.let {
                    AssistChip(
                        onClick = { onSourceClick?.invoke() },
                        label = { Text("来源: $it") },
                        enabled = onSourceClick != null,
                    )
                }
            }
        }
    }
}

@Composable
fun StatusChip(status: AnalysisStatus) {
    AssistChip(
        onClick = {},
        label = { Text(status.label) },
    )
}

@Composable
fun LevelBadge(level: EvidenceLevel) {
    AssistChip(onClick = {}, label = { Text(level.name) })
}
