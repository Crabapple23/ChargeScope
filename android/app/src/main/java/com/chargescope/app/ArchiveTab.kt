package com.chargescope.app

import android.widget.Toast
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
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveCard(e: ArchiveEntry, collapsible: Boolean, onLongPress: () -> Unit) {
    val miuix = LocalIsMiuix.current
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
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (e.note.isNotEmpty()) {
                        Text(
                            e.note,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "${AppLog.time(e.wallMs)}  ·  ${e.status}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("${AppLog.time(e.wallMs)}  ·  ${e.status}", style = MaterialTheme.typography.titleSmall)
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
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(e.headline)
                    KV(tr("设备", "Device"), e.device)
                    KV(tr("电量", "Level"), e.levelPct?.let { "$it %" })
                    KV(tr("输入电压峰值", "Peak Input Voltage"), fmt(e.maxVinV, "V", 1))
                    KV(tr("输入功率峰值", "Peak Input Power"), fmt(e.maxInputW, "W", 1))
                    KV(tr("电池侧功率峰值", "Peak Battery Power"), fmt(e.maxBatteryW, "W", 1))
                    if (e.description.isNotEmpty()) {
                        Text(tr("描述", "Summary"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.specText.isNotEmpty()) {
                        Text(tr("档位说明", "Tier Specs"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(e.specText, style = MaterialTheme.typography.bodySmall)
                    }
                    if (e.candidates.isNotEmpty()) {
                        Text(tr("候选：", "Candidates: ") + e.candidates.joinToString("；"), style = MaterialTheme.typography.bodySmall)
                    }
                    e.evidence.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    if (miuix) MiuixCard { cardContent() } else Card(Modifier.fillMaxWidth()) { cardContent() }
}

@Composable
internal fun ArchiveTab() {
    val ctx = LocalContext.current
    val items by Archive.items.collectAsState()
    val reversed = remember(items) { items.asReversed() }
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ArchiveEntry?>(null) }
    var draft by remember { mutableStateOf("") }
    var confirmDeleteOne by remember { mutableStateOf(false) }
    val collapsible = items.size > 1
    val archivedToast = tr("已增加当前状态为存档", "Saved current state to archive")
    val noDataToast = tr("暂无采样数据，无法存档", "No sample yet")
    val deletedToast = tr("已删除该条存档", "Archive entry deleted")

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

    val miuix = LocalIsMiuix.current
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
                        "当出现“已确认 / 可能 / 证据冲突”的识别结果时自动存档；同一次充电内结果变化会新增一条，拔出充电器时写入峰值。长按任意存档可编辑备注或删除。",
                        "Auto-saved on Confirmed / Likely / Conflicting results; updates peaks on unplug. Long-press any entry to edit note or delete.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        if (confirm) { Archive.clear(); AppLog.i("已清除识别存档"); confirm = false } else confirm = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (confirm) tr("再次点击确认清除全部存档", "Tap again to clear all") else tr("清除存档", "Clear Archive")) }
                Button(
                    onClick = {
                        val ok = Sampler.archiveNow(ctx)
                        Toast.makeText(ctx, if (ok) archivedToast else noDataToast, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("增加当前为存档", "Save Current as Archive")) }
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
