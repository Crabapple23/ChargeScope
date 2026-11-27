package com.chargescope.app

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        // Initialize disk-backed stores off the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            AppLog.init(applicationContext)
            Archive.init(applicationContext)
            Recorder.init(applicationContext)
        }
        // Re-arm auto-record after onboarding (service may have been killed); allowed because we are in the foreground.
        if (Prefs.onboardingDone(this) && Prefs.autoRecord(this)) {
            SamplingService.send(this, SamplingService.ACTION_ARM)
        }
        // Live view while the UI is visible; the background service takes over when recording.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    if (!Sampler.state.value.recording) Sampler.sample(applicationContext)
                    delay(Prefs.intervalMs(applicationContext))
                }
            }
        }
        setContent { App() }
    }

    override fun onDestroy() {
        if (!Sampler.state.value.recording) RootScanner.close()
        super.onDestroy()
    }
}

internal data class TabItem(val zh: String, val en: String, val icon: Int)

private val TABS = listOf(
    TabItem("实时", "Live", R.drawable.ic_tab_realtime),
    TabItem("协议", "Protocol", R.drawable.ic_tab_protocol),
    TabItem("存档", "Archive", R.drawable.ic_tab_archive),
    TabItem("数据", "Data", R.drawable.ic_tab_data),
    TabItem("设备", "Device", R.drawable.ic_tab_device),
)

@Composable
private fun App() {
    val prefs by AppSettings.prefs.collectAsState()
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(!Prefs.onboardingDone(ctx)) }
    var dualCellPrompted by remember { mutableStateOf(Prefs.dualCellPrompted(ctx)) }
    val dualCellReason by remember {
        Sampler.state.map { Analyzer.detectDualCellReason(it.latest) }.distinctUntilChanged()
    }.collectAsState(null)
    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEdge by remember { mutableIntStateOf(0) }
    var lastProtocolTapMs by remember { mutableLongStateOf(0L) }

    val dark = when (prefs.themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val baseColors = when {
        prefs.dynamicColor && Build.VERSION.SDK_INT >= 31 ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    // OLED pure black overrides only surface/background layers in dark mode, preserving Monet accent colors.
    val colors = if (prefs.isMiuix) {
        miuixColorScheme(dark = dark, dynamicColor = prefs.dynamicColor, oledBlack = prefs.oledBlack)
    } else if (dark && prefs.oledBlack) {
        baseColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0C0C0C),
            surfaceContainer = Color(0xFF080808),
            surfaceContainerHigh = Color(0xFF141414),
            surfaceContainerHighest = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFF141414),
        )
    } else {
        baseColors
    }
    val view = LocalView.current
    SideEffect {
        (ctx as? ComponentActivity)?.window?.let { w ->
            @Suppress("DEPRECATION")
            w.statusBarColor = if (prefs.isMiuix) colors.background.toArgb() else colors.surface.toArgb()
            @Suppress("DEPRECATION")
            w.navigationBarColor = if (prefs.isMiuix) colors.surfaceContainerLowest.toArgb() else colors.surface.toArgb()
            val ctl = WindowCompat.getInsetsController(w, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }

    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, prefs.fontTier) {
        Density(baseDensity.density, baseDensity.fontScale * prefs.fontMultiplier)
    }

    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalIsEn provides prefs.isEn,
        LocalIsMiuix provides prefs.isMiuix,
    ) {
        MaterialTheme(
            colorScheme = colors,
            shapes = if (prefs.isMiuix) MiuixShapes else MaterialTheme.shapes,
        ) {
            BackHandler(enabled = showOnboarding) {
                // Prevent accidental dismissal of the first-launch guide via back gesture.
            }
            BackHandler(enabled = !showOnboarding && showSettings && !prefs.predictiveBack) { showSettings = false }
            PredictiveBackHandler(enabled = !showOnboarding && showSettings && prefs.predictiveBack) { progress ->
                try {
                    progress.collect { ev ->
                        backProgress = ev.progress
                        backEdge = ev.swipeEdge
                    }
                    showSettings = false
                } catch (_: CancellationException) {
                    // Swipe cancelled; restore full scale.
                } finally {
                    backProgress = 0f
                }
            }
            Box(Modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        val archivedToast = tr("已存档当前协议状态", "Current protocol archived")
                        val noDataToast = tr("暂无采样数据，无法存档", "No sample yet")
                        val onTabClick: (Int) -> Unit = { i ->
                            if (i == 1) {
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProtocolTapMs <= 400L) {
                                    lastProtocolTapMs = 0L
                                    val ok = Sampler.archiveNow(ctx)
                                    Toast.makeText(ctx, if (ok) archivedToast else noDataToast, Toast.LENGTH_SHORT).show()
                                } else {
                                    lastProtocolTapMs = now
                                }
                            } else {
                                lastProtocolTapMs = 0L
                            }
                            tab = i
                        }
                        if (prefs.isMiuix) {
                            MiuixBottomBar(tabs = TABS, selectedIndex = tab, onSelect = onTabClick)
                        } else {
                            NavigationBar {
                                TABS.forEachIndexed { i, t ->
                                    val label = tr(t.zh, t.en)
                                    NavigationBarItem(
                                        selected = tab == i,
                                        onClick = { onTabClick(i) },
                                        icon = {
                                            Icon(
                                                painter = painterResource(t.icon),
                                                contentDescription = label,
                                            )
                                        },
                                        label = { Text(label) },
                                    )
                                }
                            }
                        }
                    },
                ) { pad ->
                    // Each tab collects only the state it needs, avoiding root-level recompositions every 2s.
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (tab) {
                            0 -> ScrollBody { RealtimeTab() }
                            1 -> ScrollBody { ProtocolTab() }
                            2 -> ArchiveTab()
                            3 -> ScrollBody { DataTab() }
                            else -> ScrollBody { DeviceTab(onOpenSettings = { showSettings = true }) }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = showSettings,
                    enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(180)),
                    exit = slideOutHorizontally(tween(220)) { it } + fadeOut(tween(160)),
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val p = backProgress
                                if (p > 0f) {
                                    val s = 1f - p * 0.12f
                                    scaleX = s
                                    scaleY = s
                                    // Edge 0 = left swipe (shift right), Edge 1 = right swipe (shift left).
                                    val dir = if (backEdge == 1) -1f else 1f
                                    translationX = size.width * p * 0.14f * dir
                                    shape = RoundedCornerShape((p * 28f).dp)
                                    clip = true
                                }
                            },
                    ) {
                        SettingsScreen(
                            prefs = prefs,
                            onBack = { showSettings = false },
                            onOpenOnboarding = {
                                showSettings = false
                                showOnboarding = true
                            },
                        )
                    }
                }
                AnimatedVisibility(
                    visible = showOnboarding,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    OnboardingScreen(
                        onFinish = {
                            Prefs.setOnboardingDone(ctx, true)
                            showOnboarding = false
                            if (Prefs.autoRecord(ctx) && hasNotificationPermission(ctx)) {
                                SamplingService.send(ctx, SamplingService.ACTION_ARM)
                            }
                        },
                    )
                }
                if (!showOnboarding && !prefs.dualCell && !dualCellPrompted && dualCellReason != null) {
                    AlertDialog(
                        onDismissRequest = {
                            dualCellPrompted = true
                            Prefs.setDualCellPrompted(ctx, true)
                        },
                        title = { Text(tr("检测到可能为双电芯设备", "Possible Dual-Cell Device")) },
                        text = {
                            Text(
                                dualCellReason + "\n\n" + tr(
                                    "是否切换为双电芯算法计算功率与容量？（后续可随时在「数据 → 双电芯设备模式」中修改）",
                                    "Switch to dual-cell math for power & capacity? (You can change this anytime under Data → Dual-Cell Battery Mode)",
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                dualCellPrompted = true
                                Sampler.onDualCellChanged(ctx, true)
                            }) { Text(tr("使用双电芯算法", "Use Dual-Cell")) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                dualCellPrompted = true
                                Prefs.setDualCellPrompted(ctx, true)
                            }) { Text(tr("保持单电芯算法", "Keep Single-Cell")) }
                        },
                    )
                }
            }
        }
    }
}
