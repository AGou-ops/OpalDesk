package io.github.lengsukq.opaldesk.design.iosglass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text

/**
 * Core iOS Liquid Glass surface with G2 continuous squircle clipping, inner luminance sheen,
 * 45° diagonal refractive specular rim stroke, and spring-damped tactile press physics.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tier: GlassTier = GlassTier.Regular,
    tintOverlay: Color = Color.Transparent,
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LiquidGlassTheme.colors
    val shape: Shape = remember(cornerRadius) { SquircleShape(cornerRadius) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (onClick != null && isPressed) 0.974f else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 540f),
        label = "glassPressScale",
    )

    val baseColor = when (tier) {
        GlassTier.UltraThin -> colors.glassUltraThin
        GlassTier.Regular -> colors.glassRegular
        GlassTier.Thick -> colors.glassThick
        GlassTier.Elevated -> colors.glassElevated
    }
    val specularBrush = remember(colors) {
        Brush.linearGradient(
            colors = listOf(colors.specularTopLeft, colors.specularMid, colors.specularBottomRight),
            start = Offset.Zero,
            end = Offset(720f, 720f),
        )
    }
    val sheenTop = colors.sheenTop

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
    } else {
        Modifier
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.45f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.55f else 0.14f),
            )
            .clip(shape)
            .background(baseColor)
            .then(if (tintOverlay != Color.Transparent) Modifier.background(tintOverlay) else Modifier)
            .drawWithContent {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(sheenTop, Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.55f,
                    ),
                )
                drawContent()
            }
            .border(width = 1.dp, brush = specularBrush, shape = shape)
            .then(clickMod)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * iOS Control Center / Settings segmented floating frosted capsule bar.
 */
@Composable
fun LiquidGlassSegmentedTabs(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LiquidGlassTheme.colors
    val barShape = LiquidShapes.Capsule
    val rimBrush = remember(colors) {
        Brush.linearGradient(listOf(colors.specularTopLeft, colors.specularMid, colors.specularBottomRight))
    }
    Row(
        modifier = modifier
            .shadow(
                elevation = 14.dp,
                shape = barShape,
                ambientColor = Color.Black.copy(alpha = if (colors.isDark) 0.42f else 0.10f),
                spotColor = Color.Black.copy(alpha = if (colors.isDark) 0.52f else 0.14f),
            )
            .clip(barShape)
            .background(colors.glassThick)
            .border(1.dp, rimBrush, barShape)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            val pillBg by animateColorAsState(
                targetValue = if (selected) colors.accentBlue else Color.Transparent,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segPillBg",
            )
            val textColor by animateColorAsState(
                targetValue = if (selected) Color.White else colors.textSecondary,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "segPillTxt",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(barShape)
                    .background(pillBg)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(idx) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * iOS Control Center vertical pill slider (for volume, brightness, or touchpad sensitivity).
 */
@Composable
fun LiquidGlassVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueBadge: String,
    modifier: Modifier = Modifier,
    accentColor: Color = LiquidGlassTheme.colors.accentBlue,
) {
    val colors = LiquidGlassTheme.colors
    val clamped = value.coerceIn(0f, 1f)
    var trackHeightPx by remember { mutableFloatStateOf(1f) }
    val shape = remember { SquircleShape(22.dp) }
    val rimBrush = remember(colors) {
        Brush.linearGradient(listOf(colors.specularTopLeft, colors.specularMid, colors.specularBottomRight))
    }

    Box(
        modifier = modifier
            .width(74.dp)
            .height(164.dp)
            .clip(shape)
            .background(colors.glassRegular)
            .border(1.dp, rimBrush, shape)
            .onSizeChanged { trackHeightPx = it.height.coerceAtLeast(1).toFloat() }
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ ->
                    change.consume()
                    val ratio = 1f - (change.position.y / trackHeightPx)
                    onValueChange(ratio.coerceIn(0f, 1f))
                }
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(clamped)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = if (colors.isDark) 0.78f else 0.88f),
                            accentColor.copy(alpha = if (colors.isDark) 0.56f else 0.72f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = valueBadge,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (clamped > 0.78f) Color.White else colors.textPrimary,
                maxLines = 1,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (clamped > 0.2f) 0.24f else 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (clamped > 0.22f) Color.White else colors.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
