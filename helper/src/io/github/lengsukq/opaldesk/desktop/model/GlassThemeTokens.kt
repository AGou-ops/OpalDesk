package io.github.lengsukq.opaldesk.desktop.model

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import io.github.lengsukq.opaldesk.design.iosglass.LiquidGlassDrawable
import io.github.lengsukq.opaldesk.design.iosglass.SquircleMath
import io.github.lengsukq.opaldesk.overlay.dpF
import io.github.lengsukq.opaldesk.ui.ExtThemePrefs

/**
 * Unified Liquid Glass (大圆角 + 冰透/黑曜双态毛玻璃) color & radius tokens shared across
 * every Samsung DeX desktop surface (Window Headers, Bezels, Taskbar, App Drawer,
 * Quick Settings, Window Switcher, Snap Layouts, and Context Menus).
 */
data class GlassOverlayTokens(
    val isDark: Boolean,
    val accentBlue: Int,
    val accentRed: Int,
    val accentGreen: Int,
    val accentAmber: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textTertiary: Int,
    val divider: Int,
    val headerFocusedFill: Int,
    val headerUnfocusedFill: Int,
    val taskbarFill: Int,
    val panelFill: Int,
    val cardInnerFill: Int,
    val hoverChipFill: Int,
    val activeChipFill: Int,
    val dangerHoverFill: Int,
    val bezelFocusedOuter: Int,
    val bezelUnfocusedOuter: Int,
    val bezelInnerSpec: Int,
    val previewScrim: Int,
    val previewFill: Int,
    val previewSnapFill: Int,
    val hudFill: Int,
    val handlePillIdle: Int,
    val handlePillStroke: Int,
) {
    fun applyGlassBackground(
        view: View,
        fillColor: Int,
        cornerRadiusDp: Float,
        strokeWidthDp: Float = 1.15f,
        clipOutline: Boolean = false,
    ): LiquidGlassDrawable = LiquidGlassDrawable.applyToView(
        view = view,
        fillColor = fillColor,
        cornerRadiusPx = view.context.dpF(cornerRadiusDp),
        strokeWidthPx = view.context.dpF(strokeWidthDp),
        darkMode = isDark,
        clipOutline = clipOutline,
    )

    fun makeWindowHeaderBackground(ctx: Context, focused: Boolean): Drawable {
        val r = ctx.dpF(DesktopGeometry.WINDOW_CORNER_RADIUS_DP)
        return LiquidGlassDrawable(
            baseFillColor = if (focused) headerFocusedFill else headerUnfocusedFill,
            cornerRadiusPx = r,
            strokeWidthPx = ctx.dpF(1.05f),
            darkMode = isDark,
        ).apply {
            cornerRadii = SquircleMath.CornerRadiiPx(r, r, 0f, 0f)
        }
    }

    companion object {
        val IceLight = GlassOverlayTokens(
            isDark = false,
            accentBlue = 0xFF007AFF.toInt(),
            accentRed = 0xFFFF3B30.toInt(),
            accentGreen = 0xFF34C759.toInt(),
            accentAmber = 0xFFFF9F0A.toInt(),
            textPrimary = 0xFF11141C.toInt(),
            textSecondary = 0xFF5B6272.toInt(),
            textTertiary = 0xFF8A93A6.toInt(),
            divider = 0x1A0F172A,
            headerFocusedFill = 0xEEF8FAFF.toInt(),
            headerUnfocusedFill = 0xDCEEF3FA.toInt(),
            taskbarFill = 0xCCF7FAFF.toInt(),
            panelFill = 0xE2F8FAFF.toInt(),
            cardInnerFill = 0x99F1F5FD.toInt(),
            hoverChipFill = 0x160F172A,
            activeChipFill = 0x22007AFF,
            dangerHoverFill = 0xE8FF3B30.toInt(),
            bezelFocusedOuter = 0x5A007AFF,
            bezelUnfocusedOuter = 0x2A0F172A,
            bezelInnerSpec = 0xDEFFFFFF.toInt(),
            previewScrim = 0x1A000000,
            previewFill = 0x24007AFF,
            previewSnapFill = 0x2C34C759,
            hudFill = 0xF2F7F9FD.toInt(),
            handlePillIdle = 0xD4F8FAFC.toInt(),
            handlePillStroke = 0x550F172A,
        )

        val ObsidianDark = GlassOverlayTokens(
            isDark = true,
            accentBlue = 0xFF0A84FF.toInt(),
            accentRed = 0xFFFF453A.toInt(),
            accentGreen = 0xFF30D158.toInt(),
            accentAmber = 0xFFFF9F0A.toInt(),
            textPrimary = 0xFFF5F7FC.toInt(),
            textSecondary = 0xFFA2ACC0.toInt(),
            textTertiary = 0xFF6E788E.toInt(),
            divider = 0x24FFFFFF,
            headerFocusedFill = 0xE41B2233.toInt(),
            headerUnfocusedFill = 0xCC151B28.toInt(),
            taskbarFill = 0xCC161C2B.toInt(),
            panelFill = 0xE01A2132.toInt(),
            cardInnerFill = 0x99232C41.toInt(),
            hoverChipFill = 0x24FFFFFF,
            activeChipFill = 0x360A84FF,
            dangerHoverFill = 0xE8FF453A.toInt(),
            bezelFocusedOuter = 0x800A84FF.toInt(),
            bezelUnfocusedOuter = 0x38FFFFFF,
            bezelInnerSpec = 0x4CFFFFFF,
            previewScrim = 0x26000000,
            previewFill = 0x2C0A84FF,
            previewSnapFill = 0x3030D158,
            hudFill = 0xEE1A2132.toInt(),
            handlePillIdle = 0xCC2A3348.toInt(),
            handlePillStroke = 0x52FFFFFF,
        )

        fun resolve(ctx: Context): GlassOverlayTokens {
            val mode = ExtThemePrefs.getThemeModeIndex(ctx)
            val isDark = when (mode) {
                ExtThemePrefs.MODE_LIGHT -> false
                ExtThemePrefs.MODE_DARK -> true
                else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return if (isDark) ObsidianDark else IceLight
        }

        fun iconOnAccent(): Int = Color.WHITE
    }
}
