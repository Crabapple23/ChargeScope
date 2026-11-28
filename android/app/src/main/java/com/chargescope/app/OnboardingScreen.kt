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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    val appPrefs by AppSettings.prefs.collectAsState()
    val isEn = LocalIsEn.current

    var step by remember { mutableIntStateOf(0) }
    val totalSteps = 4

    var notifGranted by remember { mutableStateOf(hasNotificationPermission(ctx)) }
    var notifAsked by remember { mutableStateOf(false) }

    var storageGranted by remember { mutableStateOf(hasLegacyStoragePermission(ctx)) }
    var storageAsked by remember { mutableStateOf(false) }

    var rootProvider by remember { mutableStateOf<RootScanner.Provider?>(null) }
    var selectedPriv by remember { mutableIntStateOf(Prefs.privMode(ctx)) }
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
            AppSettings.setExportTreeUri(ctx, uri.toString())
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            rootProvider = RootScanner.detectProvider(ctx)
            ShizukuBridge.refreshState()
        }
    }

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

    val titles = listOf(
        tr("欢迎使用「充了个电」", "Welcome to ChargeScope") to
            tr("了解使用原则，并选择你喜欢的界面风格", "Review key principles and pick your UI style"),
        tr("基础权限设置", "Basic Permissions") to
            tr("按需授予通知与导出权限，不授权也可正常使用", "Optional permissions for recording and exporting"),
        tr("特权增强（可选）", "Privileged Mode (Optional)") to
            tr("仅执行固定只读探测，解锁更多底层充电数据", "Read-only access for deeper charging metrics"),
        tr("电池模式与确认", "Battery Mode & Summary") to
            tr("确认你的设备电池类型，随后即可开始使用", "Confirm battery type and finish setup"),
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
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Top bar: step dots + skip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepDots(current = step, total = totalSteps)
                if (step > 0) {
                    TextButton(onClick = onFinish) {
                        Text(tr("跳过", "Skip"))
                    }
                } else {
                    Text(
                        "1 / $totalSteps",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Title + subtitle
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    titles[step].first,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    titles[step].second,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Animated step body
            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    val forward = targetState > initialState
                    val slideIn = slideInHorizontally(tween(220)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(180))
                    val slideOut = slideOutHorizontally(tween(180)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(140))
                    slideIn togetherWith slideOut
                },
                label = "onboardingStep",
            ) { currentStep ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (currentStep) {
                        0 -> StepWelcomeAndStyle(
                            uiStyle = appPrefs.uiStyle,
                            liquidGlassBar = appPrefs.liquidGlassBar,
                            onSelectUiStyle = { AppSettings.setUiStyle(ctx, it) },
                            onToggleLiquidGlass = { AppSettings.setLiquidGlassBar(ctx, it) },
                        )
                        1 -> StepBasicPermissions(
                            notifGranted = notifGranted,
                            notifAsked = notifAsked,
                            legacyStorageGranted = storageGranted,
                            storageAsked = storageAsked,
                            exportTreeUri = appPrefs.exportTreeUri,
                            onRequestNotif = {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenNotifSettings = {
                                try {
                                    ctx.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                                        },
                                    )
                                } catch (_: Exception) {
                                }
                            },
                            onRequestLegacyStorage = {
                                storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            },
                            onPickTree = { treeLauncher.launch(null) },
                        )
                        2 -> StepPrivileged(
                            selectedMode = selectedPriv,
                            shzState = shz,
                            isEn = isEn,
                            rootProvider = rootProvider,
                            checking = checking,
                            probeMsg = probeMsg,
                            onSelectMode = { mode ->
                                selectedPriv = mode
                                Sampler.onPrivModeChanged(ctx, mode)
                            },
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
                            onProbeRoot = {
                                selectedPriv = 1
                                Sampler.onPrivModeChanged(ctx, 1)
                                Sampler.startProbe(ctx, 1)
                            },
                        )
                        else -> StepBatteryAndFinish(
                            dualCell = appPrefs.dualCell,
                            onDualCellChanged = { Sampler.onDualCellChanged(ctx, it) },
                            uiStyle = appPrefs.uiStyle,
                            notifGranted = notifGranted,
                            privMode = selectedPriv,
                        )
                    }
                }
            }

            // Bottom action buttons
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
                }
                Button(
                    onClick = {
                        if (step < totalSteps - 1) step++ else onFinish()
                    },
                    modifier = Modifier.weight(if (step > 0) 1.4f else 1f),
                ) {
                    Text(
                        when (step) {
                            0 -> tr("同意并继续", "Agree & Continue")
                            totalSteps - 1 -> tr("完成并进入", "Get Started")
                            else -> tr("下一步", "Next")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            val active = i == current
            val passed = i < current
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
                label = "stepDotWidth",
            )
            val color by animateColorAsState(
                targetValue = when {
                    active -> MaterialTheme.colorScheme.primary
                    passed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = tween(180),
                label = "stepDotColor",
            )
            Box(
                Modifier
                    .height(7.dp)
                    .width(width)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        }
    }
}

@Composable
private fun StepCard(content: @Composable () -> Unit) {
    if (LocalIsMiuix.current) {
        MiuixCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun StatusPill(text: String, positive: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (positive) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PrincipleRow(title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepWelcomeAndStyle(
    uiStyle: Int,
    liquidGlassBar: Boolean,
    onSelectUiStyle: (Int) -> Unit,
    onToggleLiquidGlass: (Boolean) -> Unit,
) {
    StepCard {
        Text(tr("使用须知", "Principles"), style = MaterialTheme.typography.titleMedium)
        PrincipleRow(
            tr("纯本地离线", "100% Offline"),
            tr("无联网权限，所有记录与日志仅存于本机，导出不含设备标识。", "No internet permission. All data stays locally without device IDs."),
        )
        PrincipleRow(
            tr("只读不干预充电", "Read-Only Monitoring"),
            tr("仅读取充电参数；安全阈值只停止后台采样，绝不控制手机充电。", "Never alters charging. Safety limits only stop background recording."),
        )
        PrincipleRow(
            tr("证据优先、缺失不伪装", "Evidence First"),
            tr("读不到的数值显示“—”；仅内核明确报告协议名时才标记“确认”。", "Missing values show '—'. Protocols confirm only via driver report."),
        )
    }

    StepCard {
        Text(tr("界面风格", "UI Style"), style = MaterialTheme.typography.titleMedium)
        UiStyleSelector(
            selected = uiStyle,
            onSelect = onSelectUiStyle,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tr("底栏液态玻璃效果", "Liquid Glass Bottom Bar"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("悬浮半透明玻璃胶囊底栏，M3 与 MIUIX 独立保存", "Floating glass capsule bar, saved per style"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(checked = liquidGlassBar, onCheckedChange = onToggleLiquidGlass)
        }
    }
}

@Composable
private fun StepBasicPermissions(
    notifGranted: Boolean,
    notifAsked: Boolean,
    legacyStorageGranted: Boolean,
    storageAsked: Boolean,
    exportTreeUri: String?,
    onRequestNotif: () -> Unit,
    onOpenNotifSettings: () -> Unit,
    onRequestLegacyStorage: () -> Unit,
    onPickTree: () -> Unit,
) {
    val needsNotifPerm = Build.VERSION.SDK_INT >= 33
    val isScopedStorage = Build.VERSION.SDK_INT >= 30

    StepCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("通知权限", "Notifications"), style = MaterialTheme.typography.titleMedium)
            StatusPill(
                text = when {
                    !needsNotifPerm -> tr("默认允许", "Allowed")
                    notifGranted -> tr("已授权", "Granted")
                    else -> tr("未授权", "Not Granted")
                },
                positive = notifGranted,
            )
        }
        Text(
            tr(
                "用于后台采样与自动记录时在状态栏显示前台通知，以及安全阈值触发提醒。",
                "Used for foreground recording notifications and safety limit alerts.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (needsNotifPerm && !notifGranted) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestNotif, modifier = Modifier.weight(1f)) {
                    Text(tr("申请通知权限", "Grant"))
                }
                if (notifAsked) {
                    OutlinedButton(onClick = onOpenNotifSettings, modifier = Modifier.weight(1f)) {
                        Text(tr("系统设置", "Settings"))
                    }
                }
            }
        }
    }

    StepCard {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tr("存储与导出", "Storage & Export"), style = MaterialTheme.typography.titleMedium)
            StatusPill(
                text = when {
                    isScopedStorage && exportTreeUri != null -> tr("已设快捷目录", "Folder Set")
                    isScopedStorage -> tr("分区存储就绪", "Ready")
                    legacyStorageGranted -> tr("已授权", "Granted")
                    else -> tr("未授权", "Not Granted")
                },
                positive = isScopedStorage || legacyStorageGranted,
            )
        }
        Text(
            if (isScopedStorage) {
                tr(
                    "Android 11+ 采用分区存储，导出时通过系统选择器安全写入，无需全局存储权限；也可提前授权快捷导出目录。",
                    "Android 11+ uses Scoped Storage without broad permission. Optionally pick a quick export folder below.",
                )
            } else {
                tr(
                    "用于导出充电 CSV 曲线与诊断日志到公共存储目录。",
                    "Used to export recorded CSV files and diagnostic logs.",
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isScopedStorage) {
            OutlinedButton(onClick = onPickTree, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (exportTreeUri != null) tr("更换快捷导出目录（可选）", "Change Export Folder (Optional)")
                    else tr("授权快捷导出目录（可选）", "Pick Quick Export Folder (Optional)"),
                )
            }
        } else if (!legacyStorageGranted) {
            Button(onClick = onRequestLegacyStorage, modifier = Modifier.fillMaxWidth()) {
                Text(tr("申请存储权限", "Grant Storage Permission"))
            }
            if (storageAsked) {
                Text(
                    tr("若被拒绝，也可稍后在导出时通过系统文件选择器保存。", "If denied, you can still save via the system file picker."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    Text(
        tr(
            "提示：跳过授权后不会在启动时重复弹窗，仅在开启记录或导出时按需提示。",
            "Tip: Skipped permissions will not re-prompt on startup—only when needed.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun StepPrivileged(
    selectedMode: Int,
    shzState: ShizukuBridge.State,
    isEn: Boolean,
    rootProvider: RootScanner.Provider?,
    checking: Boolean,
    probeMsg: String?,
    onSelectMode: (Int) -> Unit,
    onRequestShizuku: () -> Unit,
    onProbeShizuku: () -> Unit,
    onProbeRoot: () -> Unit,
) {
    StepCard {
        Text(tr("选择特权方式", "Select Channel"), style = MaterialTheme.typography.titleMedium)
        OptionChips(
            options = listOf(
                tr("暂不启用", "Off") to 0,
                "Shizuku" to 2,
                "Root" to 1,
            ),
            selected = selectedMode,
            onSelect = onSelectMode,
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

        when (selectedMode) {
            0 -> {
                Text(
                    tr(
                        "仅使用 Android 公开电池 API（无需任何特权）。后续随时可在「数据 → 特权增强」中开启 Shizuku 或 Root。",
                        "Uses public Android battery APIs only. You can enable Shizuku or Root anytime under Data → Privileged Mode.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            2 -> {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("Shizuku 状态", "Shizuku Status"), style = MaterialTheme.typography.titleSmall)
                    StatusPill(
                        text = if (shzState == ShizukuBridge.State.READY) tr("已就绪", "Ready") else tr("未就绪", "Not Ready"),
                        positive = shzState == ShizukuBridge.State.READY,
                    )
                }
                Text(
                    shzState.display(isEn),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !checking,
                        onClick = onRequestShizuku,
                        modifier = Modifier.weight(1f),
                    ) { Text(tr("申请授权", "Authorize")) }
                    OutlinedButton(
                        enabled = !checking,
                        onClick = onProbeShizuku,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (checking) tr("检测中…", "Probing…") else tr("检测连接", "Test")) }
                }
            }
            else -> {
                Text(
                    tr(
                        "检测到管理器：${rootProvider?.label ?: "未检测到 Magisk / KernelSU / APatch"}",
                        "Manager: ${rootProvider?.label ?: "Magisk / KernelSU / APatch not detected"}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    enabled = !checking,
                    onClick = onProbeRoot,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (checking) tr("正在检测 Root…", "Probing Root…")
                        else tr("申请并检测 Root 授权", "Request & Test Root"),
                    )
                }
            }
        }

        probeMsg?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    Text(
        tr(
            "安全承诺：仅只读 /sys/class/power_supply 与系统服务，绝不写入任何系统节点。",
            "Read-only: queries /sys/class/power_supply and system services only, never writes.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun StepBatteryAndFinish(
    dualCell: Boolean,
    onDualCellChanged: (Boolean) -> Unit,
    uiStyle: Int,
    notifGranted: Boolean,
    privMode: Int,
) {
    StepCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(tr("双电芯设备模式", "Dual-Cell Battery Mode"), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr("仅串联双电芯机型需要开启（默认关闭）", "For 2S series dual-cell phones only (off by default)"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppSwitch(checked = dualCell, onCheckedChange = onDualCellChanged)
        }
        Text(
            tr(
                "适用于 OPPO / 一加 / realme / iQOO 及部分百瓦快充机型。如果不确定，保持关闭即可——软件检测到双电芯特征时会自动弹窗询问。",
                "For OPPO / OnePlus / realme / iQOO and certain 100W+ phones. If unsure, leave it off—the app will prompt automatically if dual-cell signals appear.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    StepCard {
        Text(tr("当前配置一览", "Setup Summary"), style = MaterialTheme.typography.titleMedium)
        KV(tr("界面风格", "UI Style"), if (uiStyle == 1) "MIUIX (HyperOS)" else "Material 3")
        KV(tr("通知权限", "Notifications"), if (notifGranted) tr("已就绪", "Ready") else tr("按需申请", "On Demand"))
        KV(
            tr("特权通道", "Privileged Mode"),
            when (privMode) {
                1 -> "Root"
                2 -> "Shizuku"
                else -> tr("关闭（公开 API）", "Off (Public API)")
            },
        )
        KV(tr("电池模式", "Battery Mode"), if (dualCell) tr("双电芯", "Dual-Cell") else tr("单电芯（默认）", "Single-Cell"))
    }

    Spacer(Modifier.height(4.dp))
}
