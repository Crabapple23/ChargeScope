package com.chargescope.app

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalIsMiuix = compositionLocalOf { false }
val LocalIsMiuixFull = compositionLocalOf { false }

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
    val onAccent = dyn?.onPrimary ?: Color.White
    val accentContainer = dyn?.primaryContainer ?: accent.copy(alpha = if (dark) 0.24f else 0.14f)
    val onAccentContainer = dyn?.onPrimaryContainer ?: if (dark) Color(0xFFD6E9FF) else Color(0xFF0D47A1)
    val secContainer = dyn?.secondaryContainer ?: if (dark) Color(0xFF1F2D3D) else Color(0xFFE3EEFF)
    val onSecContainer = dyn?.onSecondaryContainer ?: if (dark) Color(0xFFD6E9FF) else Color(0xFF0D47A1)

    return if (dark) {
        val bg = if (oledBlack) Color.Black else Color(0xFF0E0E10)
        val card = if (oledBlack) Color(0xFF141416) else Color(0xFF1C1C1E)
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = dyn?.secondary ?: Color(0xFF64D2FF),
            onSecondary = dyn?.onSecondary ?: Color.Black,
            secondaryContainer = secContainer,
            onSecondaryContainer = onSecContainer,
            tertiary = MiuixGreen,
            onTertiary = Color.Black,
            background = bg,
            onBackground = Color(0xFFF2F2F7),
            surface = bg,
            onSurface = Color(0xFFF2F2F7),
            surfaceVariant = Color(0xFF28282C),
            onSurfaceVariant = Color(0xFFA0A0A8),
            surfaceContainerLowest = card,
            surfaceContainerLow = card,
            surfaceContainer = card,
            surfaceContainerHigh = Color(0xFF28282C),
            surfaceContainerHighest = Color(0xFF343438),
            outline = Color(0xFF9898A0),
            outlineVariant = Color(0xFF2C2C2E),
            error = MiuixRedDark,
            onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentContainer,
            onPrimaryContainer = onAccentContainer,
            secondary = dyn?.secondary ?: Color(0xFF0A84FF),
            onSecondary = dyn?.onSecondary ?: Color.White,
            secondaryContainer = secContainer,
            onSecondaryContainer = onSecContainer,
            tertiary = MiuixGreen,
            onTertiary = Color.White,
            background = Color(0xFFF7F7F7),
            onBackground = Color(0xFF111111),
            surface = Color(0xFFF7F7F7),
            onSurface = Color(0xFF111111),
            surfaceVariant = Color(0xFFEDEDEF),
            onSurfaceVariant = Color(0xFF6E6E73),
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = Color.White,
            surfaceContainer = Color.White,
            surfaceContainerHigh = Color(0xFFF0F0F2),
            surfaceContainerHighest = Color(0xFFE2E2E7),
            outline = Color(0xFF8E8E93),
            outlineVariant = Color(0xFFE4E4E8),
            error = MiuixRedLight,
            onError = Color.White,
        )
    }
}

@Composable
internal fun MiuixCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalIsMiuixFull.current) {
        MiuixLibCard(modifier = modifier, content = content)
    } else {
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
}

/** MIUIX switch: authentic library switch in MIUIX Full, lightweight custom spring switch in MIUIX Lite. */
@Composable
internal fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    if (LocalIsMiuixFull.current) {
        MiuixLibSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        return
    }
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
            checked -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(180),
        label = "miuixSwitchTrack",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "miuixSwitchThumb",
    )
    val thumbScale by animateFloatAsState(
        targetValue = if (checked) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium),
        label = "miuixSwitchScale",
    )
    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.6f)
            checked -> MaterialTheme.colorScheme.onPrimary
            else -> Color.White
        },
        animationSpec = tween(180),
        label = "miuixSwitchThumbColor",
    )
    Box(
        modifier = Modifier
            .width(48.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(50))
            .background(trackColor)
            .border(
                width = 0.5.dp,
                color = if (checked) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50),
            )
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .shadow(elevation = if (enabled) 2.dp else 0.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

/** Standard MIUIX docked bottom bar with fluid sliding pill and spring icon scale. */
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
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
                val pillOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                    label = "miuixBarPill",
                )
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .padding(horizontal = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)),
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
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
                        val iconScale by animateFloatAsState(
                            targetValue = if (selected) 1.08f else 0.96f,
                            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium),
                            label = "miuixTabScale",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onSelect(i) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    painter = painterResource(t.icon),
                                    contentDescription = label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(21.dp)
                                        .graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        },
                                )
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
}

private val GlassCapsuleShape = RoundedCornerShape(31.dp)
private val GlassLensShape = RoundedCornerShape(26.dp)

/**
 * FlClash / iOS-style floating Liquid Glass capsule bottom bar.
 * On Android 12+ (API 31+), samples the live backdrop GraphicsLayer and applies GPU RenderEffect blur
 * with specular rim refraction, luminous top sheen, and interactive tap/drag fluid lens indicator.
 */
@Composable
internal fun LiquidGlassBottomBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    backdropLayer: GraphicsLayer? = null,
    dark: Boolean = isSystemInDarkTheme(),
    onSelect: (Int) -> Unit,
) {
    val canRealBlur = Build.VERSION.SDK_INT >= 31 && backdropLayer != null
    val primaryColor = MaterialTheme.colorScheme.primary
    var barRootOffset by remember { mutableStateOf(Offset.Unspecified) }
    val currentSelected by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Cache gradient brushes across recompositions to avoid per-frame allocations during animations.
    val glassTint = remember(dark, canRealBlur) {
        if (dark) {
            Brush.verticalGradient(
                listOf(
                    Color(0xFF282A34).copy(alpha = if (canRealBlur) 0.44f else 0.84f),
                    Color(0xFF14151C).copy(alpha = if (canRealBlur) 0.54f else 0.90f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = if (canRealBlur) 0.46f else 0.88f),
                    Color(0xFFEEF3FB).copy(alpha = if (canRealBlur) 0.36f else 0.82f),
                ),
            )
        }
    }
    val topSheen = remember(dark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = if (dark) 0.18f else 0.55f),
            0.42f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (dark) 0.14f else 0.03f),
        )
    }
    val rimBorder = remember(dark) {
        if (dark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.46f),
                    Color.White.copy(alpha = 0.10f),
                    Color.White.copy(alpha = 0.22f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.96f),
                    Color(0xFFC8D2E3).copy(alpha = 0.50f),
                    Color.White.copy(alpha = 0.82f),
                ),
            )
        }
    }
    val lensFill = remember(dark, primaryColor) {
        if (dark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.22f),
                    primaryColor.copy(alpha = 0.26f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.88f),
                    primaryColor.copy(alpha = 0.16f),
                ),
            )
        }
    }
    val lensRim = remember(dark, primaryColor) {
        if (dark) {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.42f),
                    Color.White.copy(alpha = 0.10f),
                ),
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = 0.98f),
                    primaryColor.copy(alpha = 0.22f),
                ),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp)
                .onGloballyPositioned { coords ->
                    barRootOffset = coords.positionInRoot()
                }
                .shadow(
                    elevation = if (dark) 18.dp else 14.dp,
                    shape = GlassCapsuleShape,
                    ambientColor = Color.Black.copy(alpha = if (dark) 0.55f else 0.16f),
                    spotColor = Color.Black.copy(alpha = if (dark) 0.65f else 0.20f),
                )
                .clip(GlassCapsuleShape)
                .pointerInput(tabs.size) {
                    detectHorizontalDragGestures { change, _ ->
                        val w = size.width.toFloat()
                        if (w > 0f && tabs.isNotEmpty()) {
                            val idx = ((change.position.x / w) * tabs.size).toInt().coerceIn(0, tabs.lastIndex)
                            if (idx != currentSelected) currentOnSelect(idx)
                        }
                    }
                },
        ) {
            // 1) Real-time GPU frosted backdrop blur (Android 12+)
            if (canRealBlur && backdropLayer != null && barRootOffset != Offset.Unspecified) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .blur(radius = 24.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                        .drawBehind {
                            if (backdropLayer.size.width > 0 && backdropLayer.size.height > 0) {
                                translate(left = -barRootOffset.x, top = -barRootOffset.y) {
                                    drawLayer(backdropLayer)
                                }
                            }
                        },
                )
            }
            // 2) Frosted tint + top specular sheen + refraction rim
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(glassTint)
                    .background(topSheen)
                    .border(width = 1.1.dp, brush = rimBorder, shape = GlassCapsuleShape),
            )
            // 3) Interactive fluid lens pill & tab items
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
            ) {
                val tabWidth = maxWidth / tabs.size.coerceAtLeast(1)
                val pillOffset by animateDpAsState(
                    targetValue = tabWidth * selectedIndex,
                    animationSpec = spring(dampingRatio = 0.64f, stiffness = Spring.StiffnessMediumLow),
                    label = "liquidGlassPill",
                )
                Box(
                    modifier = Modifier
                        .offset(x = pillOffset)
                        .width(tabWidth)
                        .fillMaxHeight()
                        .shadow(
                            elevation = if (dark) 4.dp else 3.dp,
                            shape = GlassLensShape,
                            ambientColor = Color.Black.copy(alpha = 0.12f),
                            spotColor = Color.Black.copy(alpha = 0.16f),
                        )
                        .clip(GlassLensShape)
                        .background(lensFill)
                        .border(0.85.dp, lensRim, GlassLensShape),
                )
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tabs.forEachIndexed { i, t ->
                        val selected = selectedIndex == i
                        val label = tr(t.zh, t.en)
                        val tint by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
                            animationSpec = tween(160),
                            label = "glassTabTint",
                        )
                        val scale by animateFloatAsState(
                            targetValue = if (selected) 1.10f else 0.94f,
                            animationSpec = spring(dampingRatio = 0.60f, stiffness = Spring.StiffnessMedium),
                            label = "glassTabScale",
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(26.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { onSelect(i) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    painter = painterResource(t.icon),
                                    contentDescription = label,
                                    tint = tint,
                                    modifier = Modifier
                                        .size(21.dp)
                                        .graphicsLayer {
                                            scaleX = scale
                                            scaleY = scale
                                        },
                                )
                                Text(
                                    text = label,
                                    color = tint,
                                    fontSize = 10.5.sp,
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
}
