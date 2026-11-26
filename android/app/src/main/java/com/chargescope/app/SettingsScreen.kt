package com.chargescope.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppPrefs(
    val themeMode: Int = 0, // 0 = follow system, 1 = light, 2 = dark
    val fontTier: Int = 1,  // 0 = small, 1 = normal, 2 = large, 3 = xlarge
    val lang: String = "zh",
    val updateSource: Int = 0, // 0 = GitHub Releases, 1 = GitHub Actions
    val predictiveBack: Boolean = true,
    val dynamicColor: Boolean = true,
    val oledBlack: Boolean = false,
) {
    val isEn: Boolean get() = lang == "en"
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
            fontTier = Prefs.fontTier(ctx),
            lang = Prefs.lang(ctx),
            updateSource = Prefs.updateSource(ctx),
            predictiveBack = Prefs.predictiveBack(ctx),
            dynamicColor = Prefs.dynamicColor(ctx),
            oledBlack = Prefs.oledBlack(ctx),
        )
    }

    fun setTheme(ctx: Context, v: Int) {
        Prefs.setThemeMode(ctx, v)
        _prefs.value = _prefs.value.copy(themeMode = v)
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
}

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
            SettingsCard(tr("界面主题", "Theme")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        tr("跟随系统", "System") to 0,
                        tr("浅色", "Light") to 1,
                        tr("深色", "Dark") to 2,
                    ).forEach { (label, v) ->
                        FilterChip(
                            selected = prefs.themeMode == v,
                            onClick = { AppSettings.setTheme(ctx, v) },
                            label = { Text(label) },
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
                    Switch(
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
                    Switch(
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        tr("小", "Small") to 0,
                        tr("标准", "Normal") to 1,
                        tr("大", "Large") to 2,
                        tr("超大", "XL") to 3,
                    ).forEach { (label, v) ->
                        FilterChip(
                            selected = prefs.fontTier == v,
                            onClick = { AppSettings.setFontTier(ctx, v) },
                            label = { Text(label) },
                        )
                    }
                }
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
                    Switch(
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("中文" to "zh", "English" to "en").forEach { (label, code) ->
                        FilterChip(
                            selected = prefs.lang == code,
                            onClick = { AppSettings.setLang(ctx, code) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            SettingsCard(tr("软件更新源", "Update Source")) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GitHub Releases" to 0, "GitHub Actions" to 1).forEach { (label, v) ->
                        FilterChip(
                            selected = prefs.updateSource == v,
                            onClick = { AppSettings.setUpdateSource(ctx, v) },
                            label = { Text(label) },
                        )
                    }
                }
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
