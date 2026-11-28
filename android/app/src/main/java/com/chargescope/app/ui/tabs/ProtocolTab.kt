package com.chargescope.app

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun statusColors(status: Status): Pair<Color, Color> {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (status) {
        Status.CONFIRMED -> {
            val fg = if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
            fg to fg.copy(alpha = if (isDark) 0.18f else 0.12f)
        }
        Status.POSSIBLE -> {
            val fg = if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
            fg to fg.copy(alpha = if (isDark) 0.18f else 0.12f)
        }
        Status.CONFLICT -> {
            val fg = MaterialTheme.colorScheme.error
            fg to fg.copy(alpha = if (isDark) 0.20f else 0.12f)
        }
        Status.UNKNOWN, Status.NOT_CHARGING -> {
            val fg = MaterialTheme.colorScheme.onSurfaceVariant
            fg to MaterialTheme.colorScheme.surfaceContainerHigh
        }
    }
}

@Composable
private fun ProtocolContainer(content: @Composable () -> Unit) {
    if (LocalIsMiuix.current) {
        MiuixCard { content() }
    } else {
        Card(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun ConclusionMetricTile(
    label: String,
    value: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = if (highlighted) BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)) else null,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ProtocolTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by Sampler.state.collectAsState()
    val prefs by AppSettings.prefs.collectAsState()
    val miuix = LocalIsMiuix.current
    val isEn = LocalIsEn.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val a = ui.analysis
    if (a == null) {
        Text(tr("等待首次采样…", "Waiting for first sample…"))
        return
    }

    val okToast = tr("报告图片已导出", "Report image exported")
    val galleryOkToast = tr("已保存到相册 Pictures/ChargeScope", "Saved to Pictures/ChargeScope")
    val failToast = tr("报告图片导出失败", "Failed to export report image")
    val exportImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            ReportImage.exportPng(ctx, Sampler.state.value, prefs.dualCell, isDark, out, isEn)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                Toast.makeText(ctx, if (ok) okToast else failToast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val onLongPressExport: () -> Unit = {
        val fileName = "chargescope-report-${exportTimestamp()}.png"
        scope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                ReportImage.saveToGallery(ctx, fileName) { out ->
                    ReportImage.exportPng(ctx, Sampler.state.value, prefs.dualCell, isDark, out, isEn)
                }
            }
            if (savedUri != null) {
                Toast.makeText(ctx, galleryOkToast, Toast.LENGTH_SHORT).show()
            } else {
                exportImageLauncher.launch(fileName)
            }
        }
    }

    val (badgeFg, badgeBg) = statusColors(a.status)
    val pad = if (miuix) 16.dp else 14.dp

    // 1. Hero Conclusion Card (long-press to export PNG report)
    ProtocolContainer {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = {}, onLongClick = onLongPressExport)
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tr("协议识别结论", "Protocol Analysis"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeBg,
                ) {
                    Text(
                        text = tr(a.status.label, a.status.name),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Text(
                text = a.headline,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConclusionMetricTile(
                    label = tr("驱动报告协议", "Driver Protocol"),
                    value = a.driver ?: tr("未报告", "None"),
                    highlighted = a.driver != null,
                    modifier = Modifier.weight(1f),
                )
                ConclusionMetricTile(
                    label = tr("预测当前协议", "Predicted Protocol"),
                    value = a.predicted ?: tr("无法预测", "Unknown"),
                    highlighted = a.driver == null && a.predicted != null,
                    modifier = Modifier.weight(1f),
                )
            }

            a.protocolSpec?.let { spec ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            tr("协议规格参考（上限值，不代表当前实际功率）", "Protocol Spec Reference (Ceiling, not live power)"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = spec,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Text(
                text = tr("长按此卡片导出报告图片", "Long-press this card to export report image"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 2. Structured Summary Card (splits `驱动：` / `推测：` / `功率：` into tagged rows)
    if (a.description.isNotEmpty()) {
        val descLines = a.description.lines().filter { it.isNotBlank() }
        Section(tr("分析摘要", "Analysis Summary")) {
            descLines.forEachIndexed { idx, raw ->
                val sep = raw.indexOf('：')
                val tag = if (sep in 1..6) raw.substring(0, sep) else null
                val body = if (tag != null) raw.substring(sep + 1).trim() else raw
                if (idx > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (tag != null) {
                        val tagColor = when (tag) {
                            "驱动" -> badgeFg
                            "功率" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Box(
                            modifier = Modifier
                                .padding(top = 1.dp)
                                .background(
                                    color = tagColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(6.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = tagColor,
                            )
                        }
                    }
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    // 3. Candidate Protocols as clean pill tags
    if (a.candidates.isNotEmpty()) {
        Section(tr("候选协议（★ 与本机品牌一致）", "Candidates (★ matches device brand)")) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                a.candidates.forEach { c ->
                    val brandMatch = c.startsWith("★")
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (brandMatch) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = if (brandMatch) BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)) else null,
                    ) {
                        Text(
                            text = c,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (brandMatch) FontWeight.Medium else FontWeight.Normal,
                            color = if (brandMatch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    // 4. Collapsible reference & evidence sections
    if (a.candidateSpecs.isNotEmpty()) {
        Fold(
            tr("候选协议档位参考", "Candidate Tier Specs"),
            tr("${a.candidateSpecs.size} 项", "${a.candidateSpecs.size}"),
        ) {
            a.candidateSpecs.forEach { spec ->
                val sep = spec.indexOf('：')
                val title = if (sep in 1..32) spec.substring(0, sep) else null
                val detail = if (title != null) spec.substring(sep + 1).trim() else spec
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        if (title != null) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
    val l4Count = a.evidence.count { it.level == 4 }
    val evSummary = if (l4Count > 0) tr("L4×$l4Count · 共 ${a.evidence.size} 条", "L4×$l4Count · ${a.evidence.size}")
        else tr("${a.evidence.size} 条", "${a.evidence.size}")
    Fold(tr("识别证据", "Evidence"), evSummary) {
        if (a.evidence.isEmpty()) {
            Text(tr("暂无证据", "No evidence"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val sortedEv = a.evidence.sortedByDescending { it.level }
            sortedEv.forEachIndexed { idx, ev ->
                if (idx > 0) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                    )
                }
                val lvColor = when (ev.level) {
                    4 -> if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
                    3 -> if (isDark) Color(0xFF4FC3F7) else Color(0xFF1976D2)
                    2 -> if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = 1.dp)
                            .background(lvColor.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "L${ev.level}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = lvColor,
                        )
                    }
                    Text(
                        text = ev.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                text = tr("等级说明：L4 驱动报告 > L3 输入电参 > L2 电池功率 > L1 品牌参考", "Levels: L4 Driver > L3 Input > L2 Power > L1 Brand"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (a.limitations.isNotEmpty()) {
        Fold(
            tr("限制说明", "Limitations"),
            tr("${a.limitations.size} 条", "${a.limitations.size}"),
        ) {
            a.limitations.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    ui.rootError?.let { Section(tr("特权通道状态", "Privileged Channel")) { Text(it) } }
}
