package com.chargescope.app

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppPrefs(
    val themeMode: Int = 0, // 0 = follow system, 1 = light, 2 = dark, 3 = scheduled
    val darkStartMin: Int = 22 * 60,
    val darkEndMin: Int = 7 * 60,
    val fontTier: Int = 1,  // 0 = small, 1 = normal, 2 = large, 3 = xlarge
    val lang: String = "zh",
    val updateSource: Int = 0, // 0 = GitHub Releases, 1 = GitHub Actions
    val predictiveBack: Boolean = true,
    val dynamicColor: Boolean = true,
    val oledBlack: Boolean = false,
    val dualCell: Boolean = false,
    val exportTreeUri: String? = null,
    val privMode: Int = 0, // 0 = off, 1 = root, 2 = Shizuku
    val uiStyle: Int = 0, // 0 = Material 3, 1 = MIUIX
    val liquidGlassM3: Boolean = false,
    val liquidGlassMiuix: Boolean = false,
) {
    val isEn: Boolean get() = lang == "en"
    val isMiuix: Boolean get() = uiStyle == 1
    val liquidGlassBar: Boolean get() = if (isMiuix) liquidGlassMiuix else liquidGlassM3
    val fontMultiplier: Float
        get() = when (fontTier) {
            0 -> 0.88f
            2 -> 1.15f
            3 -> 1.30f
            else -> 1.0f
        }
}

object AppSettings {
    const val REPO_URL = "https://github.com/Crabapple23/ChargeScope"
    const val RELEASES_URL = "$REPO_URL/releases"
    const val ACTIONS_URL = "$REPO_URL/actions"

    private val _prefs = MutableStateFlow(AppPrefs())
    val prefs = _prefs.asStateFlow()

    fun load(ctx: Context) {
        _prefs.value = AppPrefs(
            themeMode = Prefs.themeMode(ctx),
            darkStartMin = Prefs.darkStartMin(ctx),
            darkEndMin = Prefs.darkEndMin(ctx),
            fontTier = Prefs.fontTier(ctx),
            lang = Prefs.lang(ctx),
            updateSource = Prefs.updateSource(ctx),
            predictiveBack = Prefs.predictiveBack(ctx),
            dynamicColor = Prefs.dynamicColor(ctx),
            oledBlack = Prefs.oledBlack(ctx),
            dualCell = Prefs.dualCell(ctx),
            exportTreeUri = Prefs.exportTreeUri(ctx),
            privMode = Prefs.privMode(ctx),
            uiStyle = Prefs.uiStyle(ctx),
            liquidGlassM3 = Prefs.liquidGlassM3(ctx),
            liquidGlassMiuix = Prefs.liquidGlassMiuix(ctx),
        )
    }

    fun setTheme(ctx: Context, v: Int) {
        Prefs.setThemeMode(ctx, v)
        _prefs.value = _prefs.value.copy(themeMode = v)
    }

    fun setDarkSchedule(ctx: Context, startMin: Int, endMin: Int) {
        val s = startMin.coerceIn(0, 1439)
        val e = endMin.coerceIn(0, 1439)
        Prefs.setDarkStartMin(ctx, s)
        Prefs.setDarkEndMin(ctx, e)
        _prefs.value = _prefs.value.copy(darkStartMin = s, darkEndMin = e)
    }

    fun setFontTier(ctx: Context, v: Int) {
        Prefs.setFontTier(ctx, v)
        _prefs.value = _prefs.value.copy(fontTier = v)
    }

    fun setLang(ctx: Context, v: String) {
        Prefs.setLang(ctx, v)
        _prefs.value = _prefs.value.copy(lang = v)
    }

    fun setUpdateSource(ctx: Context, v: Int) {
        Prefs.setUpdateSource(ctx, v)
        _prefs.value = _prefs.value.copy(updateSource = v)
    }

    fun setPredictiveBack(ctx: Context, v: Boolean) {
        Prefs.setPredictiveBack(ctx, v)
        _prefs.value = _prefs.value.copy(predictiveBack = v)
    }

    fun setDynamicColor(ctx: Context, v: Boolean) {
        Prefs.setDynamicColor(ctx, v)
        _prefs.value = _prefs.value.copy(dynamicColor = v)
    }

    fun setOledBlack(ctx: Context, v: Boolean) {
        Prefs.setOledBlack(ctx, v)
        _prefs.value = _prefs.value.copy(oledBlack = v)
    }

    fun setDualCell(ctx: Context, v: Boolean) {
        Prefs.setDualCell(ctx, v)
        _prefs.value = _prefs.value.copy(dualCell = v)
    }

    fun setExportTreeUri(ctx: Context, v: String?) {
        Prefs.setExportTreeUri(ctx, v)
        _prefs.value = _prefs.value.copy(exportTreeUri = v)
    }

    fun setPrivMode(ctx: Context, v: Int) {
        Prefs.setPrivMode(ctx, v)
        _prefs.value = _prefs.value.copy(privMode = v)
    }

    fun setUiStyle(ctx: Context, v: Int) {
        Prefs.setUiStyle(ctx, v)
        _prefs.value = _prefs.value.copy(uiStyle = v)
    }

    fun setLiquidGlassBar(ctx: Context, v: Boolean) {
        if (_prefs.value.isMiuix) {
            Prefs.setLiquidGlassMiuix(ctx, v)
            _prefs.value = _prefs.value.copy(liquidGlassMiuix = v)
        } else {
            Prefs.setLiquidGlassM3(ctx, v)
            _prefs.value = _prefs.value.copy(liquidGlassM3 = v)
        }
    }
}

fun isDarkBySchedule(nowMin: Int, startMin: Int, endMin: Int): Boolean {
    if (startMin == endMin) return false
    return if (startMin < endMin) {
        nowMin in startMin until endMin
    } else {
        nowMin >= startMin || nowMin < endMin
    }
}

fun fmtClockMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

val LocalIsEn = compositionLocalOf { false }

@Composable
fun tr(zh: String, en: String): String = if (LocalIsEn.current) en else zh

@Composable
fun SettingsScreen(
    prefs: AppPrefs,
    onBack: () -> Unit,
    onOpenOnboarding: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val updateUrl = if (prefs.updateSource == 1) AppSettings.ACTIONS_URL else AppSettings.RELEASES_URL

    Scaffold(
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = tr("返回", "Back"),
                    )
                }
                Text(
                    tr("软件设置", "App Settings"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsCard(tr("界面风格", "UI Style")) {
                UiStyleSelector(
                    selected = prefs.uiStyle,
                    onSelect = { AppSettings.setUiStyle(ctx, it) },
                )
                Text(
                    tr(
                        "即时生效，不影响任何采集与协议分析功能。MIUIX 风格采用 HyperOS 卡片分层、弹簧动效与精简底栏。",
                        "Takes effect immediately without affecting any features. MIUIX brings HyperOS-style layered cards, spring motion, and a sleek bottom bar.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tr("底栏液态玻璃效果", "Liquid Glass Bottom Bar"),
                        Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = prefs.liquidGlassBar,
                        onCheckedChange = { AppSettings.setLiquidGlassBar(ctx, it) },
                    )
                }
                Text(
                    tr(
                        "默认关闭。开启后底部导航变为悬浮液态玻璃胶囊，Android 12+ 支持 GPU 实时毛玻璃模糊与左右滑动切页（低版本自动回退磨砂半透明）；Material 3 与 MIUIX 开关状态独立保存。",
                        "Off by default. Floating Liquid Glass capsule bar with real-time GPU backdrop blur and drag-to-switch on Android 12+ (frosted fallback on older versions); saved per UI style.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(tr("界面主题", "Theme")) {
                OptionChips(
                    options = listOf(
                        tr("跟随系统", "System") to 0,
                        tr("浅色", "Light") to 1,
                        tr("深色", "Dark") to 2,
                        tr("定时切换", "Scheduled") to 3,
                    ),
                    selected = prefs.themeMode,
                    onSelect = { AppSettings.setTheme(ctx, it) },
                )
                AnimatedVisibility(visible = prefs.themeMode == 3) {
                    val isDarkUi = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    val dialogTheme = if (isDarkUi) {
                        android.R.style.Theme_DeviceDefault_Dialog_Alert
                    } else {
                        android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(
                                        ctx,
                                        dialogTheme,
                                        { _, h, m -> AppSettings.setDarkSchedule(ctx, h * 60 + m, prefs.darkEndMin) },
                                        prefs.darkStartMin / 60,
                                        prefs.darkStartMin % 60,
                                        true,
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("深色开始 ${fmtClockMin(prefs.darkStartMin)}", "Dark ${fmtClockMin(prefs.darkStartMin)}"))
                            }
                            OutlinedButton(
                                onClick = {
                                    TimePickerDialog(
                                        ctx,
                                        dialogTheme,
                                        { _, h, m -> AppSettings.setDarkSchedule(ctx, prefs.darkStartMin, h * 60 + m) },
                                        prefs.darkEndMin / 60,
                                        prefs.darkEndMin % 60,
                                        true,
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(tr("浅色开始 ${fmtClockMin(prefs.darkEndMin)}", "Light ${fmtClockMin(prefs.darkEndMin)}"))
                            }
                        }
                        Text(
                            tr(
                                "深色时段：${fmtClockMin(prefs.darkStartMin)} – ${fmtClockMin(prefs.darkEndMin)}（支持跨夜，到点自动切换）。",
                                "Dark window: ${fmtClockMin(prefs.darkStartMin)} – ${fmtClockMin(prefs.darkEndMin)} (supports overnight, switches automatically).",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val dynSupported = Build.VERSION.SDK_INT >= 31
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tr("莫奈取色（动态配色）", "Dynamic Color (Material You)"),
                        Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = dynSupported && prefs.dynamicColor,
                        enabled = dynSupported,
                        onCheckedChange = { AppSettings.setDynamicColor(ctx, it) },
                    )
                }
                Text(
                    if (dynSupported) tr("跟随系统壁纸自动生成强调色与组件配色，即时生效。", "Adapts accent colors from your system wallpaper immediately.")
                    else tr("当前系统低于 Android 12，不支持莫奈取色。", "Requires Android 12 or higher."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tr("OLED 纯黑背景", "OLED Pure Black"),
                        Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = prefs.oledBlack,
                        onCheckedChange = { AppSettings.setOledBlack(ctx, it) },
                    )
                }
                Text(
                    tr("仅在深色模式下生效：将页面背景与系统栏设为纯黑（#000000），与莫奈取色互不冲突，可单独或同时开启。", "Dark mode only: sets backgrounds and bars to pure #000000. Independent of Dynamic Color."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(tr("字体大小", "Font Size")) {
                OptionChips(
                    options = listOf(
                        tr("小", "Small") to 0,
                        tr("标准", "Normal") to 1,
                        tr("大", "Large") to 2,
                        tr("超大", "XL") to 3,
                    ),
                    selected = prefs.fontTier,
                    onSelect = { AppSettings.setFontTier(ctx, it) },
                )
                Text(
                    tr("即时生效，按挡位缩放全应用文字。", "Takes effect immediately across all screens."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(tr("预测性返回动画", "Predictive Back Animation")) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        tr("侧滑返回时实时跟手缩放预览", "Follow-finger scale preview on back gesture"),
                        Modifier.weight(1f),
                    )
                    AppSwitch(
                        checked = prefs.predictiveBack,
                        onCheckedChange = { AppSettings.setPredictiveBack(ctx, it) },
                    )
                }
                Text(
                    tr("需 Android 14+ 全面屏手势导航；关闭后恢复普通返回动画。", "Requires Android 14+ gesture navigation; disable for standard back transition."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(tr("语言 / Language", "Language / 语言")) {
                OptionChips(
                    options = listOf("中文" to "zh", "English" to "en"),
                    selected = prefs.lang,
                    onSelect = { AppSettings.setLang(ctx, it) },
                )
            }

            SettingsCard(tr("软件更新源", "Update Source")) {
                OptionChips(
                    options = listOf("GitHub Releases" to 0, "GitHub Actions" to 1),
                    selected = prefs.updateSource,
                    onSelect = { AppSettings.setUpdateSource(ctx, it) },
                )
                Text(
                    updateUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    tr(
                        "本应用无联网权限，点击下方按钮将调用系统浏览器打开更新源页面。",
                        "This app has no internet permission. Tapping below opens the update source in your browser.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        try {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)))
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("在浏览器中打开更新源", "Open Update Source in Browser")) }
            }

            SettingsCard(tr("关于", "About")) {
                Text("充了个电 (ChargeScope) v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "com.chargescope.hanhan.app",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    AppSettings.REPO_URL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onOpenOnboarding,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(tr("重新查看首次引导与权限", "Reopen Setup Guide & Permissions")) }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    if (LocalIsMiuix.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            MiuixCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                content()
            }
        }
    }
}
