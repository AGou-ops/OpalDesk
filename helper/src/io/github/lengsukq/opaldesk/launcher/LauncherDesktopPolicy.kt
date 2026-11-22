package io.github.lengsukq.opaldesk.launcher

import io.github.lengsukq.opaldesk.overlay.OverlayConstants
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.LayoutMode
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.WindowStateSnapshot
import java.util.Locale
import kotlin.math.roundToInt

/** Visual presentation mode for the unified external DeX desktop (shared identically by USB & Mac). */
internal enum class DesktopPresentationMode {
    /** Unified computer desktop with pinned app icons, desktop widgets, and all-apps drawer trigger. */
    PHYSICAL_DESKTOP,
    /** Split screen with one empty slot awaiting an app pick. */
    PHYSICAL_SPLIT_PICKER,
}

/** Visual backdrop visibility state behind active desktop windows. */
internal enum class DesktopBackdropState {
    ACTIVE,
    SOFT_BEHIND_FREEFORM,
    FULL_DIM,
}

internal data class DesktopLayoutMode(
    val presentation: DesktopPresentationMode,
    val singleEmptySlot: Boolean,
    val compact: Boolean,
) {
    val isRemoteMac: Boolean
        get() = false
}

/**
 * Pure presentation, responsive geometry, and Focus Backdrop Auto-Fade rules for [io.github.lengsukq.opaldesk.LauncherActivity].
 */
internal object LauncherDesktopPolicy {
    const val MAX_DESKTOP_GRID_WIDTH_DP = 920
    const val MAX_SEARCH_PILL_WIDTH_DP = 420
    const val MAX_PICKER_SEARCH_WIDTH_DP = 360
    private const val COMPACT_WINDOW_RATIO = 0.88f

    fun computeDesktopLayoutMode(status: DaemonStatus, winW: Int, winH: Int): DesktopLayoutMode {
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

    fun hasVisibleNonFreeformCoveringApp(status: DaemonStatus, snapshot: WindowStateSnapshot): Boolean {
        if (status.splitActive == 1 || status.isMultiTilingActive || LayoutMode.isTilingTopology(snapshot.tilingTopology)) {
            return true
        }
        return snapshot.windows.any {
            !it.minimized && !OverlayConstants.isSystemOrLauncherPkg(it.pkg) &&
                !it.mode.equals(LayoutMode.FREEFORM, ignoreCase = true)
        }
    }

    fun computeBackdropState(
        status: DaemonStatus,
        windowSnapshot: WindowStateSnapshot,
        isResumed: Boolean,
        isTopResumed: Boolean,
        layoutMode: DesktopLayoutMode,
    ): DesktopBackdropState {
        if (layoutMode.singleEmptySlot) return DesktopBackdropState.ACTIVE
        if (hasVisibleNonFreeformCoveringApp(status, windowSnapshot)) {
            return if (!isResumed || !isTopResumed) DesktopBackdropState.FULL_DIM else DesktopBackdropState.ACTIVE
        }
        if (hasVisibleManagedForegroundApp(windowSnapshot) || !isResumed) {
            // Keep the desktop cleanly visible without flashing FULL_DIM while onPause() races ahead
            // of the daemon's incoming WindowStateSnapshot on app launch.
            return DesktopBackdropState.SOFT_BEHIND_FREEFORM
        }
        return DesktopBackdropState.ACTIVE
    }

    /**
     * Determines whether the desktop UI should fully fade out into the deep charcoal stage backdrop.
     * Floating small FREEFORM windows keep desktop icons and widgets softly visible beneath them.
     */
    fun shouldDimBackdrop(
        status: DaemonStatus,
        windowSnapshot: WindowStateSnapshot,
        isResumed: Boolean,
        isTopResumed: Boolean,
        layoutMode: DesktopLayoutMode,
    ): Boolean =
        computeBackdropState(status, windowSnapshot, isResumed, isTopResumed, layoutMode) == DesktopBackdropState.FULL_DIM

    fun computeGridColumns(gridWidthDp: Float, compact: Boolean): Int {
        val cellDp = if (compact) 96f else 128f
        val raw = (gridWidthDp / cellDp).roundToInt()
        return if (compact) raw.coerceIn(3, 6) else raw.coerceIn(4, 7)
    }
}
