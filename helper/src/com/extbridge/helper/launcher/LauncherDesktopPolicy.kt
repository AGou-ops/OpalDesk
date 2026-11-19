package com.extbridge.helper.launcher

import com.extbridge.helper.overlay.OverlayConstants
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.WindowStateSnapshot
import java.util.Locale
import kotlin.math.roundToInt

/** Visual presentation mode for the external DeX desktop activity. */
internal enum class DesktopPresentationMode {
    /** Remote Mac streaming: Mac renders native DeX taskbar & App Drawer; Android shows pure wallpaper. */
    REMOTE_MAC,
    /** Physical Type-C monitor: clean Launchpad/DeX desktop with centered search pill & max-width app grid. */
    PHYSICAL_DESKTOP,
    /** Physical Type-C monitor split screen with one empty slot awaiting an app pick. */
    PHYSICAL_SPLIT_PICKER,
}

internal data class DesktopLayoutMode(
    val presentation: DesktopPresentationMode,
    val singleEmptySlot: Boolean,
    val compact: Boolean,
) {
    val isRemoteMac: Boolean
        get() = presentation == DesktopPresentationMode.REMOTE_MAC
}

/**
 * Pure presentation, responsive geometry, and Focus Backdrop Auto-Fade rules for [com.extbridge.helper.LauncherActivity].
 */
internal object LauncherDesktopPolicy {
    const val MAX_DESKTOP_GRID_WIDTH_DP = 920
    const val MAX_SEARCH_PILL_WIDTH_DP = 420
    const val MAX_PICKER_SEARCH_WIDTH_DP = 360
    private const val COMPACT_WINDOW_RATIO = 0.88f

    fun computeDesktopLayoutMode(status: DaemonStatus, winW: Int, winH: Int): DesktopLayoutMode {
        if (status.isRemoteStreaming) {
            return DesktopLayoutMode(
                presentation = DesktopPresentationMode.REMOTE_MAC,
                singleEmptySlot = false,
                compact = false,
            )
        }
        val singleEmpty = status.connected &&
            status.splitActive == 1 &&
            (status.splitSlot0Pkg.isEmpty() != status.splitSlot1Pkg.isEmpty())
        val smallerThanDisplay = status.connected &&
            status.virtW > 0 &&
            status.virtH > 0 &&
            (winW < status.virtW * COMPACT_WINDOW_RATIO || winH < status.virtH * COMPACT_WINDOW_RATIO)
        val compact = singleEmpty || smallerThanDisplay
        val presentation = if (singleEmpty) {
            DesktopPresentationMode.PHYSICAL_SPLIT_PICKER
        } else {
            DesktopPresentationMode.PHYSICAL_DESKTOP
        }
        return DesktopLayoutMode(presentation, singleEmptySlot = singleEmpty, compact = compact)
    }

    fun isCoveringLayoutMode(status: DaemonStatus): Boolean {
        if (status.splitActive == 1 || status.isMultiTilingActive) return true
        val mode = status.activeLayoutMode.uppercase(Locale.ROOT)
        return mode == LayoutMode.ADAPTIVE_SINGLE ||
            mode == LayoutMode.FREEFORM ||
            LayoutMode.isTilingTopology(mode)
    }

    fun hasVisibleManagedForegroundApp(snapshot: WindowStateSnapshot): Boolean =
        snapshot.windows.any { !it.minimized && !OverlayConstants.isSystemOrLauncherPkg(it.pkg) }

    /**
     * Determines whether the desktop UI should fade out into the deep charcoal stage backdrop
     * so nothing from LauncherActivity bleeds behind centered apps, freeform windows, or split seams.
     */
    fun shouldDimBackdrop(
        status: DaemonStatus,
        windowSnapshot: WindowStateSnapshot,
        isResumed: Boolean,
        isTopResumed: Boolean,
        layoutMode: DesktopLayoutMode,
    ): Boolean {
        if (layoutMode.singleEmptySlot) return false
        if (!isResumed) return true
        if (isTopResumed) return false
        return hasVisibleManagedForegroundApp(windowSnapshot) || status.isMultiTilingActive
    }

    fun computeGridColumns(gridWidthDp: Float, compact: Boolean): Int {
        val cellDp = if (compact) 96f else 128f
        val raw = (gridWidthDp / cellDp).roundToInt()
        return if (compact) raw.coerceIn(3, 6) else raw.coerceIn(4, 7)
    }
}
