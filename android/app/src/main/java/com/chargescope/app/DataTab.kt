package com.chargescope.app

import android.Manifest
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val INTERVAL_OPTS = listOf("1 秒" to 1000L, "2 秒" to 2000L, "5 秒" to 5000L)
private val TEMP_OPTS = listOf("40 °C" to 40L, "42 °C" to 42L, "45 °C" to 45L, "50 °C" to 50L)
private val MIN_OPTS = listOf("30 分钟" to 30L, "1 小时" to 60L, "2 小时" to 120L, "不限" to 0L)
private val SOC_OPTS = listOf("80%" to 80L, "90%" to 90L, "100%" to 100L, "不限" to 0L)
private val MB_OPTS = listOf("4 MB" to 4L, "8 MB" to 8L, "16 MB" to 16L)
private val PRIV_OPTS = listOf("关闭" to 0L, "Root" to 1L, "Shizuku" to 2L)

private val EXPORT_TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private fun exportTimestamp(): String =
    Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).format(EXPORT_TS_FMT)

/** Writes directly into the user's pre-authorized SAF folder; returns null if tree is unavailable/revoked. */
private fun exportToAuthorizedTree(
    ctx: Context,
    treeUriStr: String,
    mime: String,
    fileName: String,
    writer: (OutputStream) -> Boolean,
): Boolean? = try {
    val treeUri = Uri.parse(treeUriStr)
    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
    val docUri = DocumentsContract.createDocument(ctx.contentResolver, parentDocUri, mime, fileName)
        ?: return null
    ctx.contentResolver.openOutputStream(docUri)?.use(writer) ?: false
} catch (_: SecurityException) {
    Prefs.setExportTreeUri(ctx, null)
    null
} catch (_: Exception) {
    null
}

@Composable
internal fun DataTab() {
    val ctx: Context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Subscribe only to rootError and recordedRows so DataTab never recomposes on voltage/chart ticks.
    val rootError by remember { Sampler.state.map { it.rootError }.distinctUntilChanged() }.collectAsState(null)
    val recordedRows by remember { Sampler.state.map { it.recordedRows }.distinctUntilChanged() }.collectAsState(0L)

    var mode by remember(rootError) { mutableStateOf(Prefs.privMode(ctx).toLong()) }
    val shz by ShizukuBridge.stateFlow.collectAsState()
    val checking by Sampler.probing.collectAsState()
    val privMsg by Sampler.probeMsg.collectAsState()
    var rootProvider by remember { mutableStateOf<RootScanner.Provider?>(null) }
    var interval by remember { mutableStateOf(Prefs.intervalMs(ctx)) }
    var auto by remember { mutableStateOf(Prefs.autoRecord(ctx)) }
    var maxTemp by remember { mutableStateOf(Prefs.long(ctx, Prefs.TEMP, 45L)) }
    var maxMin by remember { mutableStateOf(Prefs.long(ctx, Prefs.MINUTES, 120L)) }
    var stopSoc by remember { mutableStateOf(Prefs.long(ctx, Prefs.SOC, 100L)) }
    var maxMb by remember { mutableStateOf(Prefs.long(ctx, Prefs.FILE_MB, 8L)) }
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var diskBytes by remember { mutableLongStateOf(0L) }
    var exportTree by remember { mutableStateOf(Prefs.exportTreeUri(ctx)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootProvider = RootScanner.detectProvider(ctx)
            ShizukuBridge.refreshState()
        }
    }
    LaunchedEffect(recordedRows) {
        diskBytes = withContext(Dispatchers.IO) {
            Recorder.diskBytes(ctx) + Archive.sizeBytes() + AppLog.sizeBytes()
        }
    }

    msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val csvOkMsg = tr("CSV 已导出", "CSV exported")
    val csvFailMsg = tr("导出失败（尚无记录？）", "Export failed (no records yet?)")
    val logOkMsg = tr("诊断日志已导出", "Diagnostic log exported")
    val logFailMsg = tr("日志导出失败", "Log export failed")

    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { out -> Recorder.exportTo(ctx, out) } ?: false
                } catch (e: Exception) { false }
            }
            msg = if (ok) csvOkMsg else csvFailMsg
        }
    }
    val exportLog = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val text = Report.build(ctx, Sampler.state.value)
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    true
                } catch (e: Exception) { false }
            }
            msg = if (ok) logOkMsg else logFailMsg
        }
    }

    val autoNotifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        auto = true
        Prefs.setAutoRecord(ctx, true)
        SamplingService.send(ctx, SamplingService.ACTION_ARM)
    }

    val prefs by AppSettings.prefs.collectAsState()
    val isEn = LocalIsEn.current
    val intervalOpts = remember(isEn) {
        if (isEn) listOf("1s" to 1000L, "2s" to 2000L, "5s" to 5000L) else INTERVAL_OPTS
    }
    val minOpts = remember(isEn) {
        if (isEn) listOf("30m" to 30L, "1h" to 60L, "2h" to 120L, "No limit" to 0L) else MIN_OPTS
    }
    val socOpts = remember(isEn) {
        if (isEn) listOf("80%" to 80L, "90%" to 90L, "100%" to 100L, "Any" to 0L) else SOC_OPTS
    }
    val privOpts = remember(isEn) {
        if (isEn) listOf("Off" to 0L, "Root" to 1L, "Shizuku" to 2L) else PRIV_OPTS
    }

    Fold(tr("采样间隔", "Sampling Interval"), tr("${interval / 1000} 秒", "${interval / 1000}s")) {
        Chips(intervalOpts, interval) { interval = it; Prefs.setIntervalMs(ctx, it) }
    }
    Fold(tr("自动记录", "Auto Record"), if (auto) tr("已开启", "On") else tr("已关闭", "Off")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("连接充电器时自动开始，拔出后停止", "Auto-start on plug-in, stop on unplug"), Modifier.weight(1f))
            Switch(checked = auto, onCheckedChange = {
                if (it && Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(ctx)) {
                    autoNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    auto = it
                    Prefs.setAutoRecord(ctx, it)
                    SamplingService.send(ctx, if (it) SamplingService.ACTION_ARM else SamplingService.ACTION_DISARM)
                }
            })
        }
        Text(tr("开启后会常驻一条低优先级通知（空闲时不采样）。系统重启后需打开一次应用以恢复。", "Shows a low-priority notification while idle (no sampling). Open the app once after reboot to re-arm."), style = MaterialTheme.typography.bodySmall)
    }
    Fold(tr("双电芯设备模式", "Dual-Cell Battery Mode"), if (prefs.dualCell) tr("已开启", "On") else tr("已关闭", "Off")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("按 2S 串联双电芯算法计算功率与容量", "Use 2S dual-cell math for power & capacity"), Modifier.weight(1f))
            Switch(
                checked = prefs.dualCell,
                onCheckedChange = { Sampler.onDualCellChanged(ctx, it) },
            )
        }
        Text(
            tr(
                "适用于 OPPO / 一加 / realme / iQOO / 部分百瓦快充等串联双电芯机型。开启后自动按双电芯等效换算电池侧功率与容量；关闭时若自动检测到双电芯特征会弹窗询问。",
                "For 2S series dual-cell phones. Normalizes battery-side power and capacity when enabled; prompts automatically if dual-cell signals are detected while off.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Fold(
        tr("安全阈值", "Safety Limits"),
        "${maxTemp}°C · " + (if (maxMin > 0) "${maxMin}min" else tr("不限时", "No limit")) + " · " + (if (stopSoc > 0) "${stopSoc}%" else tr("不限", "Any")) + " · ${maxMb}MB",
    ) {
        Text(
            tr("达到以下任一上限时自动停止记录（仅停止采样，不影响手机充电）：", "Auto-stops recording when any limit is reached (never controls charging):"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(tr("电池温度上限", "Max Battery Temp"), style = MaterialTheme.typography.labelLarge)
        Chips(TEMP_OPTS, maxTemp) { maxTemp = it; Prefs.setLong(ctx, Prefs.TEMP, it) }
        Text(tr("单次最长时长", "Max Session Duration"), style = MaterialTheme.typography.labelLarge)
        Chips(minOpts, maxMin) { maxMin = it; Prefs.setLong(ctx, Prefs.MINUTES, it) }
        Text(tr("电量达到后停止", "Stop at SOC"), style = MaterialTheme.typography.labelLarge)
        Chips(socOpts, stopSoc) { stopSoc = it; Prefs.setLong(ctx, Prefs.SOC, it) }
        Text(tr("记录文件大小上限（压缩后）", "Max Log File Size (GZIP)"), style = MaterialTheme.typography.labelLarge)
        Chips(MB_OPTS, maxMb) { maxMb = it; Prefs.setLong(ctx, Prefs.FILE_MB, it) }
    }
    Fold(
        tr("特权增强（默认关闭，只读）", "Privileged Mode (Read-only)"),
        when (mode) { 1L -> "Root"; 2L -> "Shizuku"; else -> tr("关闭", "Off") },
    ) {
        Chips(privOpts, mode) {
            mode = it
            Sampler.onPrivModeChanged(ctx, it.toInt())
        }
        if (mode == 1L) {
            Text(tr("Root 管理器：", "Root Manager: ") + (rootProvider?.label ?: tr("未检测到 Magisk / KernelSU / APatch", "Magisk / KernelSU / APatch not detected")))
            Text(tr("兼容 Magisk、KernelSU、APatch：复用单个持久 su 会话，减少反复授权与进程开销。", "Supports Magisk, KernelSU, and APatch via one persistent su session."), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                enabled = !checking,
                onClick = { Sampler.startProbe(ctx, 1) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (checking) tr("正在检测…", "Probing…") else tr("检测 Root 授权", "Test Root Access")) }
        }
        if (mode == 2L) {
            Text(tr("状态：", "Status: ") + shz.display(isEn))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !checking,
                    onClick = { ShizukuBridge.requestPermission() },
                ) { Text(tr("申请授权", "Request Access")) }
                OutlinedButton(
                    enabled = !checking,
                    onClick = { Sampler.startProbe(ctx, 2) },
                ) { Text(if (checking) tr("检测中…", "Probing…") else tr("检测", "Test")) }
            }
            Text(
                tr("Shizuku 提供 adb shell 权限：直接读取 sysfs 与 dumpsys battery，不派生额外 shell 进程。", "Shizuku provides adb shell access to read sysfs and dumpsys battery without extra shells."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        privMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            tr("只执行固定只读采集，不写任何节点；失败会退避，连续失败 3 次自动关闭。", "Fixed read-only queries only; backs off on errors and auto-disables after 3 consecutive failures."),
            style = MaterialTheme.typography.bodySmall,
        )
        rootError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    Fold(tr("记录与日志", "Records & Logs"), tr("$recordedRows 行 · ${fmtBytes(diskBytes)}", "$recordedRows rows · ${fmtBytes(diskBytes)}")) {
        Text(tr("已记录 $recordedRows 行，本机总占用约 ${fmtBytes(diskBytes)}（CSV 采用 GZIP 压缩存储，导出时自动解压）。应用无联网权限，导出不含设备标识。", "$recordedRows rows recorded, ~${fmtBytes(diskBytes)} on disk (GZIP compressed, auto-decompressed on export). Offline only."))
        Button(
            onClick = {
                val tree = exportTree
                if (tree != null) {
                    scope.launch {
                        val name = "chargescope-${exportTimestamp()}.csv"
                        val direct = withContext(Dispatchers.IO) {
                            exportToAuthorizedTree(ctx, tree, "text/csv", name) { out -> Recorder.exportTo(ctx, out) }
                        }
                        if (direct != null) {
                            msg = if (direct) "$csvOkMsg ($name)" else csvFailMsg
                        } else {
                            exportTree = Prefs.exportTreeUri(ctx)
                            exportCsv.launch("chargescope.csv")
                        }
                    }
                } else {
                    exportCsv.launch("chargescope.csv")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("导出 CSV 记录", "Export CSV")) }
        Button(
            onClick = {
                val tree = exportTree
                if (tree != null) {
                    scope.launch {
                        val name = "chargescope-log-${exportTimestamp()}.txt"
                        val direct = withContext(Dispatchers.IO) {
                            exportToAuthorizedTree(ctx, tree, "text/plain", name) { out ->
                                val text = Report.build(ctx, Sampler.state.value)
                                out.write(text.toByteArray(Charsets.UTF_8))
                                true
                            }
                        }
                        if (direct != null) {
                            msg = if (direct) "$logOkMsg ($name)" else logFailMsg
                        } else {
                            exportTree = Prefs.exportTreeUri(ctx)
                            exportLog.launch("chargescope-log.txt")
                        }
                    }
                } else {
                    exportLog.launch("chargescope-log.txt")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(tr("导出诊断日志", "Export Diagnostic Log")) }
        if (exportTree != null) {
            Text(
                tr("已启用快捷导出目录：点击导出将直接写入已授权目录。", "Quick export folder active: exports write directly to your authorized folder."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    Prefs.setExportTreeUri(ctx, null)
                    exportTree = null
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("清除已授权导出目录（改回每次选择）", "Clear Export Folder (Ask Every Time)")) }
        }
        val clearedMsg = tr("已清除记录和日志", "Records and logs cleared")
        OutlinedButton(
            onClick = {
                if (confirmClear) {
                    Recorder.clear(ctx); AppLog.clear()
                    diskBytes = Archive.sizeBytes()
                    msg = clearedMsg; confirmClear = false
                } else confirmClear = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (confirmClear) tr("再次点击确认清除记录和日志", "Tap again to confirm clear") else tr("清除记录和日志", "Clear Records & Logs")) }
    }
}
