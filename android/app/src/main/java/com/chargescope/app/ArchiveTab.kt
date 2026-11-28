package com.chargescope.app

import android.widget.Toast
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.extra.SuperDialog as MiuixSuperDialog

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveCard(e: ArchiveEntry, collapsible: Boolean, onLongPress: () -> Unit) {
    val miuix = LocalIsMiuix.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val statusKind = remember(e.status) { Status.entries.firstOrNull { it.label == e.status } ?: Status.UNKNOWN }
    val (badgeFg, badgeBg) = when (statusKind) {
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
        Status.UNKNOWN, Status.NOT_CHARGING -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
    }
    var expanded by remember(e.wallMs, collapsible) { mutableStateOf(!collapsible) }
    val arrowAngle = animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = if (miuix) spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium) else tween(180),
        label = "archiveArrow",
    )
    val cardContent: @Composable () -> Unit = {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { if (collapsible) expanded = !expanded },
                        onLongClick = onLongPress,
                    )
                    .padding(if (miuix) 16.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(50), color = badgeBg) {
                            Text(
                                text = tr(e.status, statusKind.name),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeFg,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                        Text(
                            AppLog.time(e.wallMs),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.note.isNotEmpty()) {
                        Text(
                            e.note,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (!expanded) {
                        Text(e.headline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (collapsible) Text("▸", Modifier.graphicsLayer { rotationZ = arrowAngle.value })
            }
            AnimatedVisibility(
                visible = expanded,
                enter = if (miuix) expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(180)) else expandVertically(tween(200)),
                exit = if (miuix) shrinkVertically(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium)) + fadeOut(tween(140)) else shrinkVertically(tween(180)),
            ) {
                val pad = if (miuix) 16.dp else 12.dp
                Column(
                    Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = onLongPress)
                        .padding(start = pad, end = pad, bottom = pad),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(e.headline, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(tr("输入功率峰值", "Peak Input"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fmt(e.maxInputW, "W", 1) ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(tr("电池侧功率峰值", "Peak Battery"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(fmt(e.maxBatteryW, "W", 1) ?: "—", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    KV(tr("设备", "Device"), e.device)
                    KV(tr("电量", "Level"), e.levelPct?.let { "$it %" })
                    KV(tr("输入电压峰值", "Peak Input Voltage"), fmt(e.maxVinV, "V", 1))
                    if (e.description.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        Text(tr("分析摘要", "Summary"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        e.description.lines().filter { it.isNotBlank() }.forEach { raw ->
                            val sep = raw.indexOf('：')
                            val tag = if (sep in 1..6) raw.substring(0, sep) else null
                            val body = if (tag != null) raw.substring(sep + 1).trim() else raw
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                if (tag != null) {
                                    val tc = if (tag == "驱动") badgeFg else MaterialTheme.colorScheme.primary
                                    Box(
                                        Modifier
                                            .padding(top = 1.dp)
                                            .background(tc.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    ) {
                                        Text(tag, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tc)
                                    }
                                }
                                Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                    if (e.specText.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(tr("档位说明", "Tier Specs"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(e.specText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (e.candidates.isNotEmpty()) {
                        Text(
                            tr("候选：", "Candidates: ") + e.candidates.joinToString("；"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (e.evidence.isNotEmpty()) {
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
                        Text(tr("关键证据（${e.evidence.size} 条）", "Evidence (${e.evidence.size})"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        e.evidence.forEach { raw ->
                            val hasLvl = raw.length >= 3 && raw[0] == 'L' && raw[1] in '1'..'4' && raw[2] == ' '
                            val lvl = if (hasLvl) raw[1] - '0' else 0
                            val body = if (hasLvl) raw.substring(3).trim() else raw
                            val lvlFg = when (lvl) {
                                4 -> if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
                                3 -> if (isDark) Color(0xFF4FC3F7) else Color(0xFF1976D2)
                                2 -> if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                if (hasLvl) {
                                    Surface(shape = RoundedCornerShape(6.dp), color = lvlFg.copy(alpha = 0.14f)) {
                                        Text(
                                            "L$lvl",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = lvlFg,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                                        )
                                    }
                                }
                                Text(body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
    if (miuix) MiuixCard { cardContent() } else Card(Modifier.fillMaxWidth()) { cardContent() }
}

@Composable
internal fun ArchiveTab() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Archive.items.collectAsState()
    val isEn = LocalIsEn.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val reversed = remember(items) { items.asReversed() }
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirmDeleteOne by remember { mutableStateOf(false) }
    var pendingExport by remember { mutableStateOf<ArchiveEntry?>(null) }
    val collapsible = items.size > 1
    val archivedToast = tr("已增加当前状态为存档", "Saved current state to archive")
    val noDataToast = tr("暂无采样数据，无法存档", "No sample yet")
    val deletedToast = tr("已删除该条存档", "Archive entry deleted")
    val okToast = tr("报告图片已导出", "Report image exported")
    val galleryOkToast = tr("已保存到相册 Pictures/ChargeScope", "Saved to Pictures/ChargeScope")
    val failToast = tr("报告图片导出失败", "Failed to export report image")
    val shareTitle = tr("分享协议报告图片", "Share Report Image")

    val exportImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val entry = pendingExport
        pendingExport = null
        if (uri != null && entry != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            ReportImage.exportArchivePng(entry, isDark, out, isEn)
                        } ?: false
                    } catch (_: Exception) {
                        false
                    }
                }
                Toast.makeText(ctx, if (ok) okToast else failToast, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val startExportImage: (ArchiveEntry, Boolean) -> Unit = { entry, shareAfter ->
        val fileName = "chargescope-archive-${exportTimestamp()}.png"
        scope.launch {
            val savedUri = withContext(Dispatchers.IO) {
                ReportImage.saveToGallery(ctx, fileName) { out ->
                    ReportImage.exportArchivePng(entry, isDark, out, isEn)
                }
            }
            if (savedUri != null) {
                Toast.makeText(ctx, galleryOkToast, Toast.LENGTH_SHORT).show()
                if (shareAfter) ReportImage.shareImage(ctx, savedUri, shareTitle)
            } else {
                pendingExport = entry
                exportImageLauncher.launch(fileName)
            }
        }
    }

    val miuix = LocalIsMiuix.current
    val miuixFull = LocalIsMiuixFull.current
    if (miuixFull) {
        val showEdit = remember { mutableStateOf(false) }
        showEdit.value = editing != null
        MiuixSuperDialog(
            show = showEdit,
            title = tr("管理存档", "Manage Archive"),
            onDismissRequest = {
                editing = null
                confirmDeleteOne = false
            },
        ) {
            editing?.let { target ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = tr("备注（优先显示）", "Note (shown first)"),
                        placeholder = tr("例如：原装 120W 头 + 6A 线", "e.g., OEM 120W brick + 6A cable"),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSecondaryButton(
                            text = tr("保存图片", "Save Image"),
                            onClick = {
                                val snapshot = target.copy(note = draft.trim())
                                editing = null
                                confirmDeleteOne = false
                                startExportImage(snapshot, false)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        AppButton(
                            text = tr("保存并分享", "Save & Share"),
                            onClick = {
                                val snapshot = target.copy(note = draft.trim())
                                editing = null
                                confirmDeleteOne = false
                                startExportImage(snapshot, true)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AppSecondaryButton(
                        text = if (confirmDeleteOne) tr("再次点击确认删除此存档", "Tap again to confirm delete")
                        else tr("删除此存档", "Delete This Entry"),
                        onClick = {
                            if (confirmDeleteOne) {
                                if (Archive.delete(target.wallMs)) {
                                    AppLog.i("已删除一条识别存档")
                                    Toast.makeText(ctx, deletedToast, Toast.LENGTH_SHORT).show()
                                }
                                editing = null
                                confirmDeleteOne = false
                            } else {
                                confirmDeleteOne = true
                            }
                        },
                        danger = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppSecondaryButton(
                            text = tr("取消", "Cancel"),
                            onClick = {
                                editing = null
                                confirmDeleteOne = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                        AppButton(
                            text = tr("保存备注", "Save Note"),
                            onClick = {
                                Archive.updateNote(target.wallMs, draft)
                                editing = null
                                confirmDeleteOne = false
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    } else {
        editing?.let { target ->
            AlertDialog(
                onDismissRequest = {
                    editing = null
                    confirmDeleteOne = false
                },
                title = { Text(tr("管理存档", "Manage Archive")) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            label = { Text(tr("备注（优先显示）", "Note (shown first)")) },
                            placeholder = { Text(tr("例如：原装 120W 头 + 6A 线", "e.g., OEM 120W brick + 6A cable")) },
                            singleLine = false,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    val snapshot = target.copy(note = draft.trim())
                                    editing = null
                                    confirmDeleteOne = false
                                    startExportImage(snapshot, false)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("保存图片", "Save Image"))
                            }
                            Button(
                                onClick = {
                                    val snapshot = target.copy(note = draft.trim())
                                    editing = null
                                    confirmDeleteOne = false
                                    startExportImage(snapshot, true)
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("保存并分享", "Save & Share"))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                if (confirmDeleteOne) {
                                    if (Archive.delete(target.wallMs)) {
                                        AppLog.i("已删除一条识别存档")
                                        Toast.makeText(ctx, deletedToast, Toast.LENGTH_SHORT).show()
                                    }
                                    editing = null
                                    confirmDeleteOne = false
                                } else {
                                    confirmDeleteOne = true
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (confirmDeleteOne) tr("再次点击确认删除此存档", "Tap again to confirm delete")
                                else tr("删除此存档", "Delete This Entry"),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        Archive.updateNote(target.wallMs, draft)
                        editing = null
                        confirmDeleteOne = false
                    }) { Text(tr("保存", "Save")) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        editing = null
                        confirmDeleteOne = false
                    }) { Text(tr("取消", "Cancel")) }
                },
            )
        }
    }

    val extraBottom = LocalBottomBarExtra.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = if (miuix) 16.dp else 12.dp,
            end = if (miuix) 16.dp else 12.dp,
            top = 12.dp,
            bottom = 12.dp + extraBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(if (miuix) 12.dp else 10.dp),
    ) {
        item(key = "header") {
            Section(tr("协议识别存档（${items.size} 条，仅保存在本机）", "Protocol Archive (${items.size}, local only)")) {
                Text(
                    tr(
                        "当出现“已确认 / 可能 / 证据冲突”的识别结果时自动存档；同一次充电内结果变化会新增一条，拔出充电器时写入峰值。长按任意存档可编辑备注、导出报告图片或删除。",
                        "Auto-saved on Confirmed / Likely / Conflicting results; updates peaks on unplug. Long-press any entry to edit note, export report image, or delete.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                AppSecondaryButton(
                    text = if (confirm) tr("再次点击确认清除全部存档", "Tap again to clear all") else tr("清除存档", "Clear Archive"),
                    onClick = {
                        if (confirm) { Archive.clear(); AppLog.i("已清除识别存档"); confirm = false } else confirm = true
                    },
                    danger = confirm,
                    modifier = Modifier.fillMaxWidth(),
                )
                AppButton(
                    text = tr("增加当前为存档", "Save Current as Archive"),
                    onClick = {
                        val ok = Sampler.archiveNow(ctx)
                        Toast.makeText(ctx, if (ok) archivedToast else noDataToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (reversed.isEmpty()) {
            item(key = "empty") { Text(tr("暂无存档", "No archives yet")) }
        } else {
            items(reversed, key = { it.wallMs }) { e ->
                ArchiveCard(
                    e = e,
                    collapsible = collapsible,
                    onLongPress = {
                        editing = e
                        draft = e.note
                        confirmDeleteOne = false
                    },
                )
            }
        }
    }
}
