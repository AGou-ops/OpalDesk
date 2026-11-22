package io.github.lengsukq.opaldesk.design.iosglass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass

/**
 * iOS 18 / iOS 26 Liquid Glass semantic color & frosted material tokens.
 */
@Immutable
data class LiquidGlassColors(
    val isDark: Boolean,
    val accentBlue: Color,
    val accentCyan: Color,
    val accentIndigo: Color,
    val accentGreen: Color,
    val accentAmber: Color,
    val accentRed: Color,
    val canvasBase: Color,
    val meshGlowPrimary: Color,
    val meshGlowSecondary: Color,
    val meshGlowTertiary: Color,
    val glassUltraThin: Color,
    val glassRegular: Color,
    val glassThick: Color,
    val glassElevated: Color,
    val glassPillInactive: Color,
    val specularTopLeft: Color,
    val specularMid: Color,
    val specularBottomRight: Color,
    val sheenTop: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val hairlineDivider: Color,
)

object IosGlassPalettes {
    val IceLight = LiquidGlassColors(
        isDark = false,
        accentBlue = Color(0xFF007AFF),
        accentCyan = Color(0xFF32ADE6),
        accentIndigo = Color(0xFF5856D6),
        accentGreen = Color(0xFF34C759),
        accentAmber = Color(0xFFFF9F0A),
        accentRed = Color(0xFFFF3B30),
        canvasBase = Color(0xFFEEF3FB),
        meshGlowPrimary = Color(0x4064B5FF),
        meshGlowSecondary = Color(0x308E8CFF),
        meshGlowTertiary = Color(0x285EEAD4),
        glassUltraThin = Color(0x9EF8FAFF),
        glassRegular = Color(0xC6F9FBFF),
        glassThick = Color(0xE4FFFFFF),
        glassElevated = Color(0xEBFFFFFF),
        glassPillInactive = Color(0x140F172A),
        specularTopLeft = Color(0xE8FFFFFF),
        specularMid = Color(0x4DFFFFFF),
        specularBottomRight = Color(0x9EFFFFFF),
        sheenTop = Color(0x5CFFFFFF),
        textPrimary = Color(0xFF11141C),
        textSecondary = Color(0xFF5B6272),
        textTertiary = Color(0xFF8A93A6),
        hairlineDivider = Color(0x180F172A),
    )

    val ObsidianDark = LiquidGlassColors(
        isDark = true,
        accentBlue = Color(0xFF0A84FF),
        accentCyan = Color(0xFF64D2FF),
        accentIndigo = Color(0xFF5E5CE6),
        accentGreen = Color(0xFF30D158),
        accentAmber = Color(0xFFFF9F0A),
        accentRed = Color(0xFFFF453A),
        canvasBase = Color(0xFF090C14),
        meshGlowPrimary = Color(0x380A84FF),
        meshGlowSecondary = Color(0x2E5E5CE6),
        meshGlowTertiary = Color(0x2230D158),
        glassUltraThin = Color(0x7A161B28),
        glassRegular = Color(0xA6191F2E),
        glassThick = Color(0xCC1E2536),
        glassElevated = Color(0xDE232B3E),
        glassPillInactive = Color(0x26FFFFFF),
        specularTopLeft = Color(0x5CFFFFFF),
        specularMid = Color(0x18FFFFFF),
        specularBottomRight = Color(0x36FFFFFF),
        sheenTop = Color(0x24FFFFFF),
        textPrimary = Color(0xFFF5F7FC),
        textSecondary = Color(0xFFA2ACC0),
        textTertiary = Color(0xFF6E788E),
        hairlineDivider = Color(0x22FFFFFF),
    )
}

enum class GlassTier {
    UltraThin,
    Regular,
    Thick,
    Elevated,
}

val LocalLiquidGlassColors = staticCompositionLocalOf { IosGlassPalettes.IceLight }

object LiquidGlassTheme {
    val colors: LiquidGlassColors
        @Composable get() = LocalLiquidGlassColors.current
}

@Composable
fun ProvideLiquidGlassTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) IosGlassPalettes.ObsidianDark else IosGlassPalettes.IceLight
    CompositionLocalProvider(LocalLiquidGlassColors provides palette, content = content)
}

/**
 * Responsive viewport classification powered by Jetpack WindowManager 1.5
 * [WindowSizeClass.BREAKPOINTS_V2] (`600dp` Medium, `840dp` Expanded, `1200dp` Large, `1600dp` Extra-large).
 */
enum class ViewportWidthTier {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

@Immutable
data class AdaptiveViewportInfo(
    val widthDp: Int,
    val heightDp: Int,
    val widthTier: ViewportWidthTier,
) {
    val isTwoPaneCapable: Boolean
        get() = widthTier >= ViewportWidthTier.Medium
    val isDesktopWide: Boolean
        get() = widthTier >= ViewportWidthTier.Large

    companion object {
        fun fromDp(widthDp: Int, heightDp: Int): AdaptiveViewportInfo {
            val wsc = WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
                widthDp.coerceAtLeast(1).toFloat(),
                heightDp.coerceAtLeast(1).toFloat(),
            )
            val tier = when {
                wsc.isWidthAtLeastBreakpoint(1600) -> ViewportWidthTier.ExtraLarge
                wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) -> ViewportWidthTier.Large
                wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> ViewportWidthTier.Expanded
                wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> ViewportWidthTier.Medium
                else -> ViewportWidthTier.Compact
            }
            return AdaptiveViewportInfo(widthDp, heightDp, tier)
        }
    }
}

@Composable
fun rememberAdaptiveViewportInfo(): AdaptiveViewportInfo {
    val cfg = LocalConfiguration.current
    return remember(cfg.screenWidthDp, cfg.screenHeightDp) {
        AdaptiveViewportInfo.fromDp(cfg.screenWidthDp, cfg.screenHeightDp)
    }
}

/**
 * Ambient iOS soft multi-orb mesh wallpaper canvas behind translucent Liquid Glass cards,
 * giving frosted surfaces real chromatic depth even on static screens.
 */
@Composable
fun LiquidMeshBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LiquidGlassTheme.colors
    Box(modifier = modifier.fillMaxSize().background(c.canvasBase)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxDim = size.maxDimension.coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.meshGlowPrimary, Color.Transparent),
                    center = Offset(size.width * 0.16f, size.height * 0.12f),
                    radius = maxDim * 0.58f,
                ),
                radius = maxDim * 0.58f,
                center = Offset(size.width * 0.16f, size.height * 0.12f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.meshGlowSecondary, Color.Transparent),
                    center = Offset(size.width * 0.88f, size.height * 0.24f),
                    radius = maxDim * 0.52f,
                ),
                radius = maxDim * 0.52f,
                center = Offset(size.width * 0.88f, size.height * 0.24f),
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(c.meshGlowTertiary, Color.Transparent),
                    center = Offset(size.width * 0.52f, size.height * 0.88f),
                    radius = maxDim * 0.55f,
                ),
                radius = maxDim * 0.55f,
                center = Offset(size.width * 0.52f, size.height * 0.88f),
            )
        }
        content()
    }
}
