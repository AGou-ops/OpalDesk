package com.extbridge.helper.desktop.model

import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.WindowPresentation
import com.extbridge.helper.protocol.ManagedWindowInfo
import com.extbridge.helper.protocol.TaskEntry
import com.extbridge.helper.protocol.WindowStateSnapshot

/** Single managed window rendered by the DeX desktop shell (ordered bottom -> top by Z-order). */
data class DesktopWindow(
    val taskId: Int,
    val pkg: String,
    val mode: String,
    val presentation: String,
    val bounds: IntRect,
    val restoreBounds: IntRect?,
    val focused: Boolean,
    val minimized: Boolean,
) {
    val isImmersive: Boolean
        get() = presentation.equals(WindowPresentation.IMMERSIVE, ignoreCase = true) ||
            mode.equals(LayoutMode.FULLSCREEN, ignoreCase = true)

    val isMaximized: Boolean
        get() = presentation.equals(WindowPresentation.MAXIMIZED, ignoreCase = true)

    val isSnapped: Boolean
        get() = presentation.startsWith("SNAPPED_", ignoreCase = true)

    val isVisibleChromeWindow: Boolean
        get() = !minimized && !isImmersive && taskId > 0
}

/**
 * Short-lived optimistic geometry lock held after committing a drag/resize/snap so stale
 * daemon snapshots never snap the header bar or bezel back to old coordinates.
 */
data class OptimisticBoundsLease(
    val taskId: Int,
    val bounds: IntRect,
    val minExpectedSeq: Long,
    val expiresAtUptimeMs: Long,
) {
    fun isActive(nowUptimeMs: Long, currentSeq: Long): Boolean =
        nowUptimeMs < expiresAtUptimeMs && currentSeq < minExpectedSeq
}

/** Live ghost preview rendered by `DesktopBezelCanvasView` during snap-drag or 8-way resize. */
data class PreviewOverlayState(
    val active: Boolean = false,
    val outerRect: IntRect = IntRect.EMPTY,
    val hudLabel: String = "",
    val isSnapped: Boolean = false,
)

enum class TaskbarClickOutcome {
    LAUNCH_NEW,
    FOCUS_AND_RESTORE,
    MINIMIZE_FOCUSED,
}

data class TaskbarItem(
    val pkg: String,
    val taskId: Int,
    val pinned: Boolean,
    val running: Boolean,
    val focused: Boolean,
    val minimized: Boolean,
)

/** Immutable single-source-of-truth state for the entire Samsung DeX desktop shell. */
data class DesktopState(
    val displayW: Int = 2560,
    val displayH: Int = 1440,
    val dpi: Int = 240,
    val stateSeq: Long = 0L,
    val taskbarAutoHide: Boolean = false,
    val windows: List<DesktopWindow> = emptyList(),
    val taskbarItems: List<TaskbarItem> = emptyList(),
    val leases: Map<Int, OptimisticBoundsLease> = emptyMap(),
    val showDesktopRestoreIds: List<Int> = emptyList(),
) {
    val visibleChromeWindows: List<DesktopWindow>
        get() = windows.filter { it.isVisibleChromeWindow }

    val focusedVisibleWindow: DesktopWindow?
        get() = windows.lastOrNull { !it.minimized && it.focused }
            ?: windows.lastOrNull { !it.minimized }

    val activeImmersiveWindow: DesktopWindow?
        get() = windows.lastOrNull { !it.minimized && it.isImmersive }

    val isDesktopClear: Boolean
        get() = windows.none { !it.minimized }
}

/**
 * Pure state reconciliation & interaction reducer for [DesktopState] — fully JVM unit-testable.
 */
object DesktopStateReducer {
    private val SYSTEM_PKGS = setOf("com.extbridge.helper", "android", "com.android.systemui")

    fun isSystemOrLauncher(pkg: String?): Boolean = pkg.isNullOrBlank() || pkg in SYSTEM_PKGS

    fun reconcile(
        prev: DesktopState,
        snap: WindowStateSnapshot,
        tasks: List<TaskEntry>?,
        pinnedPkgs: List<String>,
        displayW: Int,
        displayH: Int,
        dpi: Int,
        taskbarAutoHide: Boolean,
        nowUptimeMs: Long,
    ): DesktopState {
        // Drop out-of-order stale snapshots when stateSeq is explicitly populated
        if (snap.stateSeq > 0L && prev.stateSeq > 0L && snap.stateSeq < prev.stateSeq) {
            return prev
        }
        val effectiveSeq = if (snap.stateSeq > 0L) snap.stateSeq else prev.stateSeq
        val activeLeases = prev.leases.filterValues { it.isActive(nowUptimeMs, effectiveSeq) }

        val mappedWindows = snap.windows
            .filter { it.taskId > 0 && !isSystemOrLauncher(it.pkg) }
            .map { info -> mapWindow(info, activeLeases[info.taskId], displayW, displayH) }

        val taskbarItems = buildTaskbarItems(pinnedPkgs, mappedWindows, tasks)
        val nextShowDesktopStack = if (mappedWindows.any { !it.minimized }) emptyList() else prev.showDesktopRestoreIds

        return prev.copy(
            displayW = displayW,
            displayH = displayH,
            dpi = dpi,
            stateSeq = effectiveSeq,
            taskbarAutoHide = taskbarAutoHide,
            windows = mappedWindows,
            taskbarItems = taskbarItems,
            leases = activeLeases,
            showDesktopRestoreIds = nextShowDesktopStack,
        )
    }

    fun applyOptimisticLease(
        state: DesktopState,
        taskId: Int,
        rect: IntRect,
        nowUptimeMs: Long,
        leaseDurationMs: Long = 420L,
    ): DesktopState {
        val lease = OptimisticBoundsLease(
            taskId = taskId,
            bounds = rect,
            minExpectedSeq = state.stateSeq + 1L,
            expiresAtUptimeMs = nowUptimeMs + leaseDurationMs,
        )
        val updatedLeases = state.leases + (taskId to lease)
        val updatedWindows = state.windows.map { w ->
            if (w.taskId == taskId) w.copy(bounds = rect, minimized = false) else w
        }
        return state.copy(windows = updatedWindows, leases = updatedLeases)
    }

    /** Standard Samsung DeX taskbar icon click rule. */
    fun resolveTaskbarClick(item: TaskbarItem): TaskbarClickOutcome = when {
        !item.running || item.taskId <= 0 -> TaskbarClickOutcome.LAUNCH_NEW
        item.focused && !item.minimized -> TaskbarClickOutcome.MINIMIZE_FOCUSED
        else -> TaskbarClickOutcome.FOCUS_AND_RESTORE
    }

    /** Computes task IDs to minimize (or restore if desktop is currently clear) for Show Desktop. */
    fun planShowDesktopToggle(state: DesktopState): Pair<Boolean, List<Int>> {
        val visibleIds = state.windows.filter { !it.minimized }.map { it.taskId }
        return if (visibleIds.isNotEmpty()) {
            true to visibleIds // minimizeAll, remember visibleIds
        } else {
            val aliveSet = state.windows.map { it.taskId }.toHashSet()
            val toRestore = state.showDesktopRestoreIds.filter { it in aliveSet }
            false to toRestore
        }
    }

    private fun mapWindow(
        info: ManagedWindowInfo,
        lease: OptimisticBoundsLease?,
        displayW: Int,
        displayH: Int,
    ): DesktopWindow {
        val rawRect = lease?.bounds ?: IntRect(info.left, info.top, info.right, info.bottom)
        val sanitized = DesktopGeometry.sanitizeWindowRect(rawRect, displayW, displayH)
        val restore = if (info.hasRestoreBounds) {
            IntRect(info.restoreLeft, info.restoreTop, info.restoreRight, info.restoreBottom)
        } else {
            null
        }
        return DesktopWindow(
            taskId = info.taskId,
            pkg = info.pkg,
            mode = info.mode,
            presentation = info.presentation,
            bounds = sanitized,
            restoreBounds = restore,
            focused = info.focused,
            minimized = info.minimized,
        )
    }

    private fun buildTaskbarItems(
        pinnedPkgs: List<String>,
        managedWindows: List<DesktopWindow>,
        tasks: List<TaskEntry>?,
    ): List<TaskbarItem> {
        val byPkg = LinkedHashMap<String, TaskbarItem>()
        for (pkg in pinnedPkgs) {
            if (isSystemOrLauncher(pkg)) continue
            byPkg[pkg] = TaskbarItem(pkg = pkg, taskId = -1, pinned = true, running = false, focused = false, minimized = false)
        }
        // Overlay managed window state (authoritative for focus / minimized / running)
        for (w in managedWindows) {
            val wasPinned = byPkg[w.pkg]?.pinned == true
            byPkg[w.pkg] = TaskbarItem(
                pkg = w.pkg,
                taskId = w.taskId,
                pinned = wasPinned,
                running = true,
                focused = w.focused && !w.minimized,
                minimized = w.minimized,
            )
        }
        // Supplement any external-display running tasks not yet tracked in managedWindows
        if (tasks != null) {
            for (t in tasks) {
                if (isSystemOrLauncher(t.pkg) || !t.onExt() && !t.isRunning()) continue
                val existing = byPkg[t.pkg]
                if (existing == null || !existing.running) {
                    byPkg[t.pkg] = TaskbarItem(
                        pkg = t.pkg,
                        taskId = t.taskId,
                        pinned = existing?.pinned == true,
                        running = t.taskId > 0,
                        focused = t.isTopExt(),
                        minimized = !t.isTopExt(),
                    )
                }
            }
        }
        return byPkg.values.toList()
    }
}
