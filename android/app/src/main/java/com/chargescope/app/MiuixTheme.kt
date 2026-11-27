package com.chargescope.app

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalIsMiuix = compositionLocalOf { false }

private val MiuixBlueLight = Color(0xFF3482FF)
private val MiuixBlueDark = Color(0xFF277AF7)
private val MiuixGreen = Color(0xFF34C759)
private val MiuixRedLight = Color(0xFFFA3E3E)
private val MiuixRedDark = Color(0xFFFF453A)

internal val MiuixShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
internal fun miuixColorScheme(dark: Boolean, dynamicColor: Boolean, oledBlack: Boolean): ColorScheme {
    val ctx = LocalContext.current
    val dyn = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else null

    val accent = dyn?.primary ?: if (dark) MiuixBlueDark else MiuixBlueLight
    val accentContainer = dyn?.primaryContainer ?: accent.copy(alpha = if (dark) 0.22f else 0.14f)
    val onAccentContainer = dyn?.onPrimaryContainer ?: accent

    return if (dark) {
        val bg = if (oledBlack) Color.Black else Color(0xFF0E0E10)
        val card = if (oledBlack) Color(0xFF141416) else Color(0xFF1C1C1E)
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = Color(0xFF64D2FF),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF1E2A38),
            onSecondaryContainer = Color(0xFFD6E9FF),
            tertiary = MiuixGreen,
            onTertiary = Color.Black,
            background = bg,
            onBackground = Color(0xFFF2F2F7),
            surface = bg,
            onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF242426),
            onSurfaceVariant = Color(0xFF98989F),
            surfaceContainerLowest = card,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = Color(0xFF262629),
            surfaceContainerHighest = Color(0xFF2F2F33),
            outline = Color(0xFF3A3A3C),
            outlineVariant = Color(0xFF2C2C2E),
            error = MiuixRedDark,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = Color(0xFF0A84FF),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEAF2FF),
            onSecondaryContainer = Color(0xFF0D47A1),
            tertiary = MiuixGreen,
            onTertiary = Color.White,
            background = Color(0xFFF7F7F7),
            onBackground = Color(0xFF111111),
            surface = Color(0xFFF7F7F7),
            onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFEDEDEF),
            onSurfaceVariant = Color(0xFF737378),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFF0F0F2),
            surfaceContainerHighest = Color(0xFFE5E5EA),
            outline = Color(0xFFD1D1D6),
            outlineVariant = Color(0xFFE6E6EA),
            error = MiuixRedLight,
        )
    }
}

@Composable
internal fun MiuixCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

@Composable
internal fun MiuixBottomBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val barBg = MaterialTheme.colorScheme.surfaceContainerLowest
    Surface(
        color = barBg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEachIndexed { i, t ->
                    val selected = selectedIndex == i
                    val label = tr(t.zh, t.en)
                    val tint by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(160),
                        label = "miuixTabTint",
                    )
                    val pillBg by animateColorAsState(
                        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.11f) else Color.Transparent,
                        animationSpec = tween(160),
                        label = "miuixTabPill",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onSelect(i) },
                            )
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = pillBg,
                            ) {
                                Box(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
                                    Icon(
                                        painter = painterResource(t.icon),
                                        contentDescription = label,
                                        tint = tint,
                                        modifier = Modifier.size(21.dp),
                                    )
                                }
                            }
                            Text(
                                text = label,
                                color = tint,
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
