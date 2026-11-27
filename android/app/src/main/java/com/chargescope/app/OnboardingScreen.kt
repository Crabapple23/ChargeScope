package com.chargescope.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun hasNotificationPermission(ctx: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun hasLegacyStoragePermission(ctx: Context): Boolean =
    Build.VERSION.SDK_INT >= 30 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember { mutableIntStateOf(0) }
    val totalSteps = 4

    var notifGranted by remember { mutableStateOf(hasNotificationPermission(ctx)) }
    var notifAsked by remember { mutableStateOf(false) }

    var storageGranted by remember { mutableStateOf(hasLegacyStoragePermission(ctx)) }
    var storageAsked by remember { mutableStateOf(false) }
    var exportTreeUri by remember { mutableStateOf(Prefs.exportTreeUri(ctx)) }

    var rootProvider by remember { mutableStateOf<RootScanner.Provider?>(null) }
    var selectedPriv by remember { mutableIntStateOf(Prefs.privMode(ctx)) }
    val appPrefs by AppSettings.prefs.collectAsState()
    val shz by ShizukuBridge.stateFlow.collectAsState()
    val checking by Sampler.probing.collectAsState()
    val probeMsg by Sampler.probeMsg.collectAsState()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifAsked = true
        notifGranted = granted || hasNotificationPermission(ctx)
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        storageAsked = true
        storageGranted = granted || hasLegacyStoragePermission(ctx)
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
            Prefs.setExportTreeUri(ctx, uri.toString())
            exportTreeUri = uri.toString()
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootProvider = RootScanner.detectProvider(ctx)
            ShizukuBridge.refreshState()
        }
    }

    // Re-check permissions when returning from system settings or Shizuku app.
    DisposableEffect(ctx) {
        val lifecycle = (ctx as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifGranted = hasNotificationPermission(ctx)
                storageGranted = hasLegacyStoragePermission(ctx)
                ShizukuBridge.refreshState()
            }
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val stepTitles = listOf(
        tr("使用须知", "Notice & Terms"),
        tr("通知权限", "Notification Permission"),
        tr("存储权限", "Storage Permission"),
        tr("Shizuku / Root 权限", "Shizuku / Root Access"),
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header & progress
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("首次使用引导 (${step + 1}/$totalSteps)", "Setup Guide (${step + 1}/$totalSteps)"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stepTitles[step],
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                if (step > 0) {
                    TextButton(onClick = onFinish) {
                        Text(tr("跳过全部", "Skip All"))
                    }
                }
            }

            LinearProgressIndicator(
                progress = { (step + 1) / totalSteps.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )

            // Step content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (step) {
                    0 -> NoticeStep()
                    1 -> NotificationStep(
                        granted = notifGranted,
                        asked = notifAsked,
                        onRequest = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onOpenSettings = {
                            try {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                }
                                ctx.startActivity(intent)
                            } catch (_: Exception) {
                            }
                        },
                    )
                    2 -> StorageStep(
                        legacyGranted = storageGranted,
                        asked = storageAsked,
                        exportTreeUri = exportTreeUri,
                        onRequestLegacy = {
                            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        },
                        onPickTree = {
                            treeLauncher.launch(null)
                        },
                    )
                    else -> PrivilegedStep(
                        selectedMode = selectedPriv,
                        shzState = shz,
                        rootProvider = rootProvider,
                        checking = checking,
                        probeMsg = probeMsg,
                        onRequestShizuku = {
                            selectedPriv = 2
                            Sampler.onPrivModeChanged(ctx, 2)
                            ShizukuBridge.requestPermission()
                        },
                        onProbeShizuku = {
                            selectedPriv = 2
                            Sampler.onPrivModeChanged(ctx, 2)
                            Sampler.startProbe(ctx, 2)
                        },
                        onRequestRoot = {
                            selectedPriv = 1
                            Sampler.onPrivModeChanged(ctx, 1)
                            Sampler.startProbe(ctx, 1)
                        },
                        onDisablePriv = {
                            selectedPriv = 0
                            Sampler.onPrivModeChanged(ctx, 0)
                        },
                        dualCell = appPrefs.dualCell,
                        onDualCellChanged = { Sampler.onDualCellChanged(ctx, it) },
                    )
                }
            }

            // Bottom navigation
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(tr("上一步", "Back"))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                Button(
                    onClick = {
                        if (step < totalSteps - 1) step++ else onFinish()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        when (step) {
                            0 -> tr("同意并继续", "Agree & Continue")
                            totalSteps - 1 -> tr("完成并进入", "Finish")
                            else -> tr("下一步", "Next")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoticeStep() {
    Text(
        tr(
            "欢迎使用「充了个电 (ChargeScope)」。在开始之前，请了解以下使用原则：",
            "Welcome to ChargeScope. Please review the following principles before starting:",
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    OnboardingCard(
        title = tr("1. 纯本地离线运行", "1. Offline & Private"),
        body = tr(
            "应用未申请任何联网权限，所有充电记录、协议存档与日志仅保存在本机，导出文件不包含设备唯一标识。",
            "No internet permission is requested. All records, archives, and logs stay locally on your device without device identifiers.",
        ),
    )
    OnboardingCard(
        title = tr("2. 只读监测，绝不干预充电", "2. Read-Only Monitoring"),
        body = tr(
            "本应用仅用于读取电池与充电状态，不会修改系统充电参数或控制通断；安全阈值仅用于自动停止后台记录。",
            "This app only reads battery/charging metrics and never alters charging behavior. Safety limits only stop background recording.",
        ),
    )
    OnboardingCard(
        title = tr("3. 证据优先，拒绝猜测伪装", "3. Evidence-Based Analysis"),
        body = tr(
            "读取不到的数值统一显示“—”，不用 0 伪装；快充协议仅在内核驱动明确报告名称（L4）时标记“确认”，输入侧高压仅作“可能”参考。",
            "Missing values are shown as '—' instead of 0. Protocols are confirmed only when kernel drivers explicitly report them (L4).",
        ),
    )
    OnboardingCard(
        title = tr("4. 权限按需授予", "4. Optional Permissions"),
        body = tr(
            "接下来可按需配置通知、存储以及可选的 Shizuku / Root 权限。即使全部跳过，基础电池监测功能仍可正常使用。",
            "Next you can configure notification, storage, and optional Shizuku / Root access. Basic monitoring works even if you skip all.",
        ),
    )
}

@Composable
private fun NotificationStep(
    granted: Boolean,
    asked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val needsRuntimePerm = Build.VERSION.SDK_INT >= 33
    OnboardingCard(
        title = tr("为什么需要通知权限？", "Why Notification Permission?"),
        body = tr(
            "当开启「后台记录」或「插入充电器自动记录」时，应用需要通过前台服务通知在状态栏展示采样状态，以防系统在后台回收进程。",
            "When background recording or auto-record is enabled, a foreground service notification keeps sampling alive and displays live progress.",
        ),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tr("当前状态", "Current Status"),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                when {
                    !needsRuntimePerm -> tr("系统默认已允许（Android 12 及以下无需单独申请）", "Granted by system (Android 12 and below)")
                    granted -> tr("已授予通知权限", "Notification permission granted")
                    else -> tr("尚未授予通知权限（可现在申请，或在开启记录时再申请）", "Not granted yet (grant now or later when recording)")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (needsRuntimePerm && !granted) {
                Button(
                    onClick = onRequest,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("申请通知权限", "Grant Notification Permission"))
                }
                if (asked) {
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("前往系统通知设置", "Open System Notification Settings"))
                    }
                }
            }
        }
    }
    Text(
        tr(
            "提示：如果你选择暂不授权，应用不会在启动时重复弹窗，只会在你手动点击「开始记录」或开启「自动记录」时再次申请。",
            "Tip: If skipped, the app won't prompt on startup and will only ask when you start recording or enable auto-record.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StorageStep(
    legacyGranted: Boolean,
    asked: Boolean,
    exportTreeUri: String?,
    onRequestLegacy: () -> Unit,
    onPickTree: () -> Unit,
) {
    val isScopedStorage = Build.VERSION.SDK_INT >= 30
    OnboardingCard(
        title = tr("为什么涉及存储权限？", "Why Storage Access?"),
        body = tr(
            "用于将采样得到的充电 CSV 曲线与诊断日志导出到手机公共存储目录。日常采样默认保存在应用私有目录，采用 GZIP 压缩存储。",
            "Used to export recorded CSV curves and diagnostic logs to shared storage. Ongoing records are stored compressed in app-private storage.",
        ),
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (legacyGranted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("当前状态", "Current Status"), style = MaterialTheme.typography.titleMedium)
            if (isScopedStorage) {
                Text(
                    if (exportTreeUri != null) {
                        tr("已采用 Android 分区存储，并已授权默认导出目录。", "Scoped Storage active, and an export folder is authorized.")
                    } else {
                        tr("当前为 Android 11+ 分区存储：导出 CSV/日志时将通过系统文件选择器安全写入，无需申请全局存储权限。", "Android 11+ Scoped Storage: exports use the system file picker safely without requiring broad storage permission.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = onPickTree,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (exportTreeUri != null) tr("重新选择授权目录（可选）", "Change Authorized Folder (Optional)")
                        else tr("授权导出目录（可选）", "Authorize Export Folder (Optional)"),
                    )
                }
            } else {
                Text(
                    if (legacyGranted) {
                        tr("已授予外部存储写入权限", "External storage write permission granted")
                    } else {
                        tr("尚未授予存储权限（Android 10 及以下导出文件可能需要此权限）", "Storage permission not granted (may be needed on Android 10 and below)")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!legacyGranted) {
                    Button(
                        onClick = onRequestLegacy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(tr("申请存储权限", "Grant Storage Permission"))
                    }
                    if (asked) {
                        Text(
                            tr("如被系统永久拒绝，可稍后在系统应用权限中开启，或使用系统文件选择器导出。", "If permanently denied, you can enable it in system settings or export via the system picker."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PrivilegedStep(
    selectedMode: Int,
    shzState: ShizukuBridge.State,
    rootProvider: RootScanner.Provider?,
    checking: Boolean,
    probeMsg: String?,
    onRequestShizuku: () -> Unit,
    onProbeShizuku: () -> Unit,
    onRequestRoot: () -> Unit,
    onDisablePriv: () -> Unit,
    dualCell: Boolean,
    onDualCellChanged: (Boolean) -> Unit,
) {
    Text(
        tr(
            "特权模式为可选项，默认关闭。仅执行固定只读探测（/sys/class/power_supply 与 dumpsys battery），绝不写入任何系统节点。",
            "Privileged mode is optional and off by default. It performs fixed read-only queries (/sys/class/power_supply & dumpsys battery) and never writes to sysfs.",
        ),
        style = MaterialTheme.typography.bodyMedium,
    )

    // Shizuku card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selectedMode == 2) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("方式一：Shizuku 权限（免 Root 推荐）", "Option 1: Shizuku (No Root Needed)"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr("状态：${shzState.label}", "Status: ${shzState.label}"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !checking,
                    onClick = onRequestShizuku,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(tr("申请 Shizuku 授权", "Request Shizuku"))
                }
                OutlinedButton(
                    enabled = !checking,
                    onClick = onProbeShizuku,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (checking && selectedMode == 2) tr("检测中…", "Probing…") else tr("检测连接", "Test"))
                }
            }
        }
    }

    // Root card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selectedMode == 1) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr("方式二：Root 权限", "Option 2: Root Access"), style = MaterialTheme.typography.titleMedium)
            Text(
                tr(
                    "管理器：${rootProvider?.label ?: "未检测到 Magisk / KernelSU / APatch"}",
                    "Manager: ${rootProvider?.label ?: "Magisk / KernelSU / APatch not detected"}",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = !checking,
                onClick = onRequestRoot,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (checking && selectedMode == 1) tr("正在检测 Root…", "Probing Root…")
                    else tr("申请并检测 Root 授权", "Request & Test Root"),
                )
            }
        }
    }

    probeMsg?.let { msg ->
        Text(
            msg,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    if (selectedMode != 0) {
        OutlinedButton(
            onClick = onDisablePriv,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(tr("暂不启用特权（恢复仅公开 API）", "Disable Privileged Mode (Public API Only)"))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (dualCell) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("双电芯设备模式（可选）", "Dual-Cell Battery Mode (Optional)"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(checked = dualCell, onCheckedChange = onDualCellChanged)
            }
            Text(
                tr(
                    "如果你的手机是串联双电芯（如多数 OPPO / 一加 / realme / iQOO 或百瓦快充机型），可在此开启；不开启也会在检测到双电芯特征时弹窗询问。",
                    "Enable if your phone uses a 2S series dual-cell battery. If left off, the app will prompt you if dual-cell signals are detected.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        tr(
            "后续随时可以在「数据 → 特权增强」中切换或关闭。",
            "You can change or disable this anytime under Data → Privileged Mode.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
