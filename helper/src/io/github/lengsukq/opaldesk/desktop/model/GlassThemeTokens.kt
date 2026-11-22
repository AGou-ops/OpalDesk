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
            divider = 0x160F172A,
            headerFocusedFill = 0xF2F8FAFD.toInt(),
            headerUnfocusedFill = 0xD8EDF2F8.toInt(),
            taskbarFill = 0xBCF7FAFF.toInt(),
            panelFill = 0xD2F7FAFF.toInt(),
            cardInnerFill = 0x7CF1F5FD.toInt(),
            hoverChipFill = 0x140F172A,
            activeChipFill = 0x1F007AFF,
            dangerHoverFill = 0xE8FF3B30.toInt(),
            bezelFocusedOuter = 0x360F172A,
            bezelUnfocusedOuter = 0x200F172A,
            bezelInnerSpec = 0xD4FFFFFF.toInt(),
            previewScrim = 0x0E000000,
            previewFill = 0x18007AFF,
            previewSnapFill = 0x2234C759,
            hudFill = 0xF2F7F9FD.toInt(),
            handlePillIdle = 0xD4F8FAFC.toInt(),
            handlePillStroke = 0x440F172A,
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
            divider = 0x20FFFFFF,
            headerFocusedFill = 0xEC1B2233.toInt(),
            headerUnfocusedFill = 0xCC151B28.toInt(),
            taskbarFill = 0xBE141A29.toInt(),
            panelFill = 0xD0171E2E.toInt(),
            cardInnerFill = 0x7A232C41.toInt(),
            hoverChipFill = 0x20FFFFFF,
            activeChipFill = 0x300A84FF,
            dangerHoverFill = 0xE8FF453A.toInt(),
            bezelFocusedOuter = 0x4AFFFFFF,
            bezelUnfocusedOuter = 0x28FFFFFF,
            bezelInnerSpec = 0x3CFFFFFF,
            previewScrim = 0x16000000,
            previewFill = 0x200A84FF,
            previewSnapFill = 0x2830D158,
            hudFill = 0xEE1A2132.toInt(),
            handlePillIdle = 0xCC2A3348.toInt(),
            handlePillStroke = 0x48FFFFFF,
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
