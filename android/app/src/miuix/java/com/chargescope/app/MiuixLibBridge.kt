package com.chargescope.app

import android.os.Build
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as LibMiuixCard
import top.yukonga.miuix.kmp.basic.SmallTitle as LibMiuixSmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as LibMiuixSmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch as LibMiuixSwitch
import top.yukonga.miuix.kmp.basic.TextButton as LibMiuixTextButton
import top.yukonga.miuix.kmp.basic.TextField as LibMiuixTextField
import top.yukonga.miuix.kmp.extra.SuperDialog as LibMiuixSuperDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme as LibMiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixLibDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLibLightColorScheme
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils.Companion.MiuixPopupHost
import top.yukonga.miuix.kmp.utils.SmoothRoundedCornerShape

/** MIUIX-flavor bridge backed by the open-source top.yukonga.miuix.kmp component library. */
@Composable
internal fun MiuixLibThemeWrapper(
    dark: Boolean,
    dynamicColor: Boolean,
    oledBlack: Boolean,
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val dyn = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null
    val accent = dyn?.primary ?: if (dark) Color(0xFF277AF7) else Color(0xFF3482FF)
    val onAccent = dyn?.onPrimary ?: Color.White
    val colors = if (dark) {
        if (oledBlack) {
            miuixLibDarkColorScheme(
                primary = accent,
                onPrimary = onAccent,
                background = Color.Black,
                surface = Color.Black,
                surfaceContainer = Color(0xFF141416),
            )
        } else {
            miuixLibDarkColorScheme(primary = accent, onPrimary = onAccent)
        }
    } else {
        miuixLibLightColorScheme(primary = accent, onPrimary = onAccent)
    }
    LibMiuixTheme(colors = colors, content = content)
}

@Composable
internal fun MiuixLibCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LibMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = LibMiuixTheme.colorScheme.surfaceContainer,
        cornerRadius = 16.dp,
    ) {
        content()
    }
}

@Composable
internal fun MiuixLibSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    LibMiuixSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
    )
}

@Composable
internal fun MiuixLibSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    LibMiuixSmallTitle(
        text = text,
        modifier = modifier,
        insideMargin = PaddingValues(start = 12.dp, top = 4.dp, bottom = 6.dp),
    )
}

@Composable
internal fun MiuixLibSmallTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    LibMiuixSmallTopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
    )
}

@Composable
internal fun MiuixLibButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LibMiuixTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.textButtonColorsPrimary(),
    )
}

@Composable
internal fun MiuixLibSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    LibMiuixTextButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = MiuixButtonDefaults.textButtonColors(
            textColor = if (danger) MaterialTheme.colorScheme.error else LibMiuixTheme.colorScheme.onSecondaryVariant,
        ),
    )
}

@Composable
internal fun MiuixLibTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    LibMiuixTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
    )
}

@Composable
internal fun MiuixLibSuperDialog(
    show: MutableState<Boolean>,
    title: String?,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) {
    LibMiuixSuperDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismissRequest,
        content = content,
    )
}

@Composable
internal fun MiuixLibPopupHost() {
    MiuixPopupHost()
}

internal fun miuixChipShape(): Shape = SmoothRoundedCornerShape(12.dp)
