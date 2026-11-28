package com.chargescope.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

internal fun exportTimestamp(): String =
    Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).format(EXPORT_TS_FMT)

private fun formatExportTreeLabel(treeUriStr: String): String = try {
    val docId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUriStr))
    val idx = docId.indexOf(':')
    if (idx >= 0 && idx + 1 < docId.length) docId.substring(idx + 1) else docId
} catch (_: Exception) {
    treeUriStr
}

/** Writes directly into the user's pre-authorized SAF folder; returns null if tree is unavailable/revoked. */
internal fun exportToAuthorizedTree(
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
    AppSettings.setExportTreeUri(ctx, null)
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

    val prefs by AppSettings.prefs.collectAsState()
    val mode = prefs.privMode.toLong()
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootProvider = RootScanner.detectProvider(ctx)
            ShizukuBridge.refreshState(ctx)
        }
    }
    DisposableEffect(ctx) {
        val lifecycle = (ctx as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val st = ShizukuBridge.refreshState(ctx)
                if (AppSettings.prefs.value.privMode == 2 && st == ShizukuBridge.State.READY && Sampler.probeMsg.value == null) {
                    Sampler.onShizukuAuthorized(ctx)
                }
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
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

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (_: Exception) {
            }
            AppSettings.setExportTreeUri(ctx, uri.toString())
        }
    }

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
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("连接充电器时自动记录", "Auto-Record While Charging"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    tr("插入自动开始采样，拔出后自动停止", "Starts automatically on plug-in, stops on unplug"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(checked = auto, onCheckedChange = {
                if (it && Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission(ctx)) {
                    autoNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    auto = it
                    Prefs.setAutoRecord(ctx, it)
                    SamplingService.send(ctx, if (it) SamplingService.ACTION_ARM else SamplingService.ACTION_DISARM)
                }
            })
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tr("• 空闲时仅常驻低优先级通知，不进行后台采样", "• Idle mode shows a low-priority notification with zero sampling"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr("• 手机重启后需打开一次应用以恢复自动监听", "• Open the app once after reboot to re-arm auto-listening"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Fold(tr("双电芯设备模式", "Dual-Cell Battery Mode"), if (prefs.dualCell) tr("已开启", "On") else tr("已关闭", "Off")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("启用 2S 串联双电芯换算", "Use 2S Dual-Cell Math"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    tr("按双电芯等效换算电池侧功率与电池容量", "Normalizes battery-side power and capacity for 2S packs"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(
                checked = prefs.dualCell,
                onCheckedChange = { Sampler.onDualCellChanged(ctx, it) },
            )
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    tr("• 适用机型：OPPO / 一加 / realme / iQOO 及部分百瓦快充机型", "• Applies to: OPPO / OnePlus / realme / iQOO & 100W+ 2S phones"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr("• 自动识别：关闭时若检测到双电芯特征会自动弹窗询问，不确定可保持关闭", "• Auto-detect: prompts automatically if 2S signals appear while off"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val okGreen = if (isDark) Color(0xFF66BB6A) else Color(0xFF2E7D32)
    val warnOrange = if (isDark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
    val privSummary = when (mode) {
        1L -> if (rootError == null && privMsg?.contains("可用") == true) tr("Root · 已连接", "Root · Connected") else "Root"
        2L -> when (shz) {
            ShizukuBridge.State.READY -> tr("Shizuku · 已授权", "Shizuku · Ready")
            ShizukuBridge.State.NO_PERMISSION -> tr("Shizuku · 待授权", "Shizuku · Auth Needed")
            ShizukuBridge.State.NOT_RUNNING -> tr("Shizuku · 未运行", "Shizuku · Stopped")
        }
        else -> tr("已关闭", "Off")
    }
    Fold(
        tr("特权增强", "Privileged Mode"),
        privSummary,
    ) {
        Chips(privOpts, mode) {
            Sampler.onPrivModeChanged(ctx, it.toInt())
        }
        when (mode) {
            0L -> {
                Text(
                    tr(
                        "默认关闭，仅使用公开电池 API。开启 Shizuku 或 Root 可只读获取更多充电与健康数据。",
                        "Off by default (public battery API only). Enable Shizuku or Root for read-only charging and health metrics.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            1L -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Root 管理器", "Root Manager"), style = MaterialTheme.typography.bodyMedium)
                    val detected = rootProvider != null
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (detected) okGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = rootProvider?.label ?: tr("未检测到", "Not detected"),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (detected) okGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Button(
                    enabled = !checking,
                    onClick = { Sampler.startProbe(ctx, 1) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checking) tr("正在检测连接…", "Testing…") else tr("申请并检测 Root 连接", "Request & Test Root")) }
                Text(
                    tr(
                        "支持 Magisk / KernelSU / APatch，仅执行固定只读探测。授权弹窗点允许后若未自动刷新，请再点一次检测连接。",
                        "Supports Magisk / KernelSU / APatch (read-only). Tap Test again if it doesn't refresh after granting.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            2L -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Shizuku 状态", "Shizuku Status"), style = MaterialTheme.typography.bodyMedium)
                    val (pillText, pillFg) = when (shz) {
                        ShizukuBridge.State.READY -> tr("已授权", "Authorized") to okGreen
                        ShizukuBridge.State.NO_PERMISSION -> tr("待授权", "Needs Auth") to warnOrange
                        ShizukuBridge.State.NOT_RUNNING -> tr("未运行", "Not Running") to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (shz == ShizukuBridge.State.NOT_RUNNING) MaterialTheme.colorScheme.surfaceContainerHigh else pillFg.copy(alpha = 0.14f),
                    ) {
                        Text(
                            text = pillText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = pillFg,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(
                    shz.display(isEn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (shz == ShizukuBridge.State.READY) {
                    Button(
                        enabled = !checking,
                        onClick = { Sampler.startProbe(ctx, 2) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (checking) tr("正在检测连接…", "Testing…") else tr("检测连接", "Test Connection")) }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            enabled = !checking && shz == ShizukuBridge.State.NO_PERMISSION,
                            onClick = { ShizukuBridge.requestPermission(ctx) },
                            modifier = Modifier.weight(1f),
                        ) { Text(tr("申请授权", "Authorize")) }
                        OutlinedButton(
                            enabled = !checking,
                            onClick = { Sampler.startProbe(ctx, 2) },
                            modifier = Modifier.weight(1f),
                        ) { Text(if (checking) tr("检测中…", "Probing…") else tr("检测连接", "Test")) }
                    }
                }
                Text(
                    tr(
                        "授权后将自动尝试连接；若未自动刷新，请手动点击一次「检测连接」。Android 14+ 部分机型 sysfs 受 SELinux 限制，完整节点需 Root。",
                        "Auto-connects after authorization; tap \"Test\" manually if it doesn't refresh. On Android 14+ sysfs may be SELinux-restricted (full nodes require Root).",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val statusBannerText = when {
            checking -> privMsg ?: tr("正在检测连接…", "Testing connection…")
            privMsg != null -> privMsg
            rootError != null -> rootError
            else -> null
        }
        if (statusBannerText != null && mode != 0L) {
            val isSuccess = !checking && rootError == null && statusBannerText.contains("可用")
            val bannerFg = when {
                checking -> MaterialTheme.colorScheme.primary
                isSuccess -> okGreen
                else -> MaterialTheme.colorScheme.error
            }
            val prefix = when {
                checking -> "⏳ "
                isSuccess -> "✓ "
                else -> "⚠ "
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = bannerFg.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = prefix + statusBannerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = bannerFg,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Text(
            tr(
                "安全说明：不写入任何系统节点；连续失败 3 次自动关闭。",
                "Safety: never writes to sysfs; auto-disables after 3 consecutive failures.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Fold(tr("记录与日志", "Records & Logs"), tr("$recordedRows 行 · ${fmtBytes(diskBytes)}", "$recordedRows rows · ${fmtBytes(diskBytes)}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("已记录样本", "Recorded Rows"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("$recordedRows 行", "$recordedRows rows"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tr("本机总占用", "Local Storage"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fmtBytes(diskBytes), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(
            tr(
                "CSV 采用 GZIP 压缩存储，导出时自动解压；纯本地离线，导出不含设备标识。",
                "CSV is GZIP-compressed on disk and auto-decompressed on export. Offline only, no device IDs.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val tree = prefs.exportTreeUri
                    if (tree != null) {
                        scope.launch {
                            val name = "chargescope-${exportTimestamp()}.csv"
                            val direct = withContext(Dispatchers.IO) {
                                exportToAuthorizedTree(ctx, tree, "text/csv", name) { out -> Recorder.exportTo(ctx, out) }
                            }
                            if (direct != null) {
                                msg = if (direct) "$csvOkMsg ($name)" else csvFailMsg
                            } else {
                                exportCsv.launch("chargescope.csv")
                            }
                        }
                    } else {
                        exportCsv.launch("chargescope.csv")
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(tr("导出 CSV", "Export CSV")) }
            Button(
                onClick = {
                    val tree = prefs.exportTreeUri
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
                                exportLog.launch("chargescope-log.txt")
                            }
                        }
                    } else {
                        exportLog.launch("chargescope-log.txt")
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(tr("导出诊断日志", "Export Log")) }
        }
        val treeUri = prefs.exportTreeUri
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KV(
                    tr("CSV / 日志目录", "CSV / Log Folder"),
                    treeUri?.let(::formatExportTreeLabel) ?: tr("每次弹出选择器", "Ask every time"),
                )
                KV(
                    tr("报告图片目录", "Report Images"),
                    tr("相册 Pictures/ChargeScope", "Pictures/ChargeScope"),
                )
            }
        }
        if (treeUri != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { treeLauncher.launch(null) },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("更换目录", "Change Folder")) }
                OutlinedButton(
                    onClick = { AppSettings.setExportTreeUri(ctx, null) },
                    modifier = Modifier.weight(1f),
                ) { Text(tr("清除目录", "Clear Folder")) }
            }
        } else {
            OutlinedButton(
                onClick = { treeLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("设置快捷导出目录（CSV / 日志）", "Set Quick Export Folder (CSV / Log)")) }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
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
        ) {
            Text(
                if (confirmClear) tr("再次点击确认清除记录和日志", "Tap again to confirm clear")
                else tr("清除记录和日志", "Clear Records & Logs"),
                color = if (confirmClear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
