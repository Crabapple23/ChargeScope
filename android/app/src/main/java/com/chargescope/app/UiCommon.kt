package com.chargescope.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ScrollBody(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun KV(k: String, v: String?) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v ?: "—", Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
internal fun Chips(options: List<Pair<String, Long>>, selected: Long, onSelect: (Long) -> Unit) {
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
internal fun Fold(title: String, summary: String, content: @Composable () -> Unit) {
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

internal fun fmt(v: Double?, unit: String, digits: Int = 2): String? =
    v?.let { String.format("%.${digits}f %s", it, unit) }

internal fun fmtBytes(b: Long): String = when {
    b < 1024L -> "$b B"
    b < 1024L * 1024L -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / (1024.0 * 1024.0))
}

internal fun fmtDuration(ms: Long, isEn: Boolean): String {
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

internal fun statusText(s: Int?, isEn: Boolean = false) = if (isEn) when (s) {
    2 -> "Charging"; 3 -> "Discharging"; 4 -> "Not Charging"; 5 -> "Full"; else -> null
} else when (s) {
    2 -> "充电中"; 3 -> "放电中"; 4 -> "未充电"; 5 -> "已充满"; else -> null
}

internal fun pluggedText(p: Int, isEn: Boolean = false) = if (isEn) when (p) {
    0 -> "Unplugged"; 1 -> "AC"; 2 -> "USB"; 4 -> "Wireless"; 8 -> "Dock"; else -> "Other($p)"
} else when (p) {
    0 -> "未连接"; 1 -> "交流电源"; 2 -> "USB"; 4 -> "无线"; 8 -> "底座"; else -> "其他($p)"
}

internal fun healthText(h: Int?, isEn: Boolean = false) = if (isEn) when (h) {
    2 -> "Good"; 3 -> "Overheat"; 4 -> "Dead"; 5 -> "Over Voltage"; 6 -> "Failure"; 7 -> "Cold"; else -> null
} else when (h) {
    2 -> "良好"; 3 -> "过热"; 4 -> "损坏"; 5 -> "过压"; 6 -> "故障"; 7 -> "过冷"; else -> null
}
