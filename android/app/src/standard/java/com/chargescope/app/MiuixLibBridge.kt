package com.chargescope.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Standard-flavor bridge (zero third-party MIUIX library dependency).
 * None of the MIUIX-Full paths are active when BuildConfig.HAS_MIUIX_LIB is false.
 */
@Composable
internal fun MiuixLibThemeWrapper(
    dark: Boolean,
    dynamicColor: Boolean,
    oledBlack: Boolean,
    content: @Composable () -> Unit,
) = content()

@Composable
internal fun MiuixLibCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) = content()

@Composable
internal fun MiuixLibSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) = Unit

@Composable
internal fun MiuixLibSmallTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = modifier)
}

@Composable
internal fun MiuixLibSmallTopAppBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) = Unit

@Composable
internal fun MiuixLibButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled) { Text(text) }
}

@Composable
internal fun MiuixLibSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Text(text, color = if (danger) MaterialTheme.colorScheme.error else Color.Unspecified)
    }
}

@Composable
internal fun MiuixLibTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = modifier)
}

@Composable
internal fun MiuixLibSuperDialog(
    show: MutableState<Boolean>,
    title: String?,
    summary: String? = null,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit,
) = Unit

@Composable
internal fun MiuixLibPopupHost() = Unit

internal fun miuixChipShape(): Shape = RoundedCornerShape(50)
