package com.extbridge.helper.desktop

import android.view.KeyEvent
import com.extbridge.helper.NavAssistService
import com.extbridge.helper.desktop.model.DesktopState
import com.extbridge.helper.desktop.model.DesktopStateReducer
import com.extbridge.helper.desktop.model.IntRect
import com.extbridge.helper.desktop.model.TaskbarClickOutcome
import com.extbridge.helper.desktop.model.TaskbarItem
import com.extbridge.helper.protocol.BridgeProtocol.LayoutMode
import com.extbridge.helper.protocol.BridgeProtocol.ShortcutAction
import com.extbridge.helper.protocol.BridgeProtocol.WindowPresentation
import com.extbridge.helper.protocol.ManagedWindowInfo
import com.extbridge.helper.protocol.WindowStateSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopStateTest {

    private fun win(
        id: Int,
        pkg: String,
        l: Int = 100,
        t: Int = 100,
        r: Int = 900,
        b: Int = 700,
        focused: Boolean = false,
        minimized: Boolean = false,
        presentation: String = WindowPresentation.FLOATING,
    ) = ManagedWindowInfo(
        taskId = id,
        pkg = pkg,
        mode = if (presentation == WindowPresentation.IMMERSIVE) LayoutMode.FULLSCREEN else LayoutMode.FREEFORM,
        left = l,
        top = t,
        right = r,
        bottom = b,
        focused = focused,
        minimized = minimized,
        presentation = presentation,
    )

    @Test
    fun reconcileRejectsOutOfOrderStaleStateSeq() {
        val s10 = DesktopStateReducer.reconcile(
            prev = DesktopState(),
            snap = WindowStateSnapshot(
                stateSeq = 10L,
                windows = listOf(win(1, "com.a", l = 200, focused = true)),
            ),
            tasks = null,
            pinnedPkgs = emptyList(),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 1000L,
        )
        assertEquals(10L, s10.stateSeq)
        assertEquals(200, s10.windows.single().bounds.left)

        // Older seq=8 arrives late -> ignored completely
        val afterStale = DesktopStateReducer.reconcile(
            prev = s10,
            snap = WindowStateSnapshot(
                stateSeq = 8L,
                windows = listOf(win(1, "com.a", l = 50, focused = true)),
            ),
            tasks = null,
            pinnedPkgs = emptyList(),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 1050L,
        )
        assertEquals(10L, afterStale.stateSeq)
        assertEquals(200, afterStale.windows.single().bounds.left)
    }

    @Test
    fun optimisticBoundsLeasePreventsSnapBackAndReleasesOnNewSeqOrExpiry() {
        val base = DesktopStateReducer.reconcile(
            prev = DesktopState(),
            snap = WindowStateSnapshot(
                stateSeq = 5L,
                windows = listOf(win(7, "com.a", l = 100, t = 100, r = 800, b = 700, focused = true)),
            ),
            tasks = null,
            pinnedPkgs = emptyList(),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 1000L,
        )

        // User commits drag to [500, 120, 1200, 720] at t=1050ms (expects seq >= 6)
        val leased = DesktopStateReducer.applyOptimisticLease(
            state = base,
            taskId = 7,
            rect = IntRect(500, 120, 1200, 720),
            nowUptimeMs = 1050L,
            leaseDurationMs = 420L,
        )
        assertEquals(500, leased.windows.single().bounds.left)

        // Same seq=5 snapshot arrives with old coords before expiry -> lease holds [500..1200]
        val held = DesktopStateReducer.reconcile(
            prev = leased,
            snap = WindowStateSnapshot(
                stateSeq = 5L,
                windows = listOf(win(7, "com.a", l = 100, t = 100, r = 800, b = 700, focused = true)),
            ),
            tasks = null,
            pinnedPkgs = emptyList(),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 1200L,
        )
        assertEquals(500, held.windows.single().bounds.left)

        // Authoritative seq=6 arrives -> lease releases immediately and adopts daemon bounds
        val confirmed = DesktopStateReducer.reconcile(
            prev = held,
            snap = WindowStateSnapshot(
                stateSeq = 6L,
                windows = listOf(win(7, "com.a", l = 504, t = 120, r = 1204, b = 720, focused = true)),
            ),
            tasks = null,
            pinnedPkgs = emptyList(),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 1220L,
        )
        assertEquals(504, confirmed.windows.single().bounds.left)
        assertTrue(confirmed.leases.isEmpty())
    }

    @Test
    fun taskbarClickStateMachineFollowsSamsungDeXRules() {
        assertEquals(
            TaskbarClickOutcome.LAUNCH_NEW,
            DesktopStateReducer.resolveTaskbarClick(TaskbarItem("com.a", -1, pinned = true, running = false, focused = false, minimized = false)),
        )
        assertEquals(
            TaskbarClickOutcome.MINIMIZE_FOCUSED,
            DesktopStateReducer.resolveTaskbarClick(TaskbarItem("com.a", 10, pinned = true, running = true, focused = true, minimized = false)),
        )
        assertEquals(
            TaskbarClickOutcome.FOCUS_AND_RESTORE,
            DesktopStateReducer.resolveTaskbarClick(TaskbarItem("com.a", 10, pinned = true, running = true, focused = false, minimized = false)),
        )
        assertEquals(
            TaskbarClickOutcome.FOCUS_AND_RESTORE,
            DesktopStateReducer.resolveTaskbarClick(TaskbarItem("com.a", 10, pinned = true, running = true, focused = false, minimized = true)),
        )
    }

    @Test
    fun showDesktopPlansMinimizeAllAndRestoresPreviousStack() {
        val withVisible = DesktopStateReducer.reconcile(
            prev = DesktopState(),
            snap = WindowStateSnapshot(
                stateSeq = 1L,
                windows = listOf(
                    win(1, "com.a", focused = false, minimized = false),
                    win(2, "com.b", focused = true, minimized = false),
                    win(3, "com.c", focused = false, minimized = true),
                ),
            ),
            tasks = null,
            pinnedPkgs = listOf("com.a"),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 100L,
        )
        val (minAll, idsToMin) = DesktopStateReducer.planShowDesktopToggle(withVisible)
        assertTrue(minAll)
        assertEquals(listOf(1, 2), idsToMin)

        // After minimizing all, state keeps restore stack [1, 2]
        val allMinimized = DesktopStateReducer.reconcile(
            prev = withVisible.copy(showDesktopRestoreIds = idsToMin),
            snap = WindowStateSnapshot(
                stateSeq = 2L,
                windows = listOf(
                    win(1, "com.a", focused = false, minimized = true),
                    win(2, "com.b", focused = false, minimized = true),
                    win(3, "com.c", focused = false, minimized = true),
                ),
            ),
            tasks = null,
            pinnedPkgs = listOf("com.a"),
            displayW = 1920,
            displayH = 1080,
            dpi = 160,
            taskbarAutoHide = false,
            nowUptimeMs = 200L,
        )
        assertTrue(allMinimized.isDesktopClear)
        val (minAllAgain, idsToRestore) = DesktopStateReducer.planShowDesktopToggle(allMinimized)
        assertFalse(minAllAgain)
        assertEquals(listOf(1, 2), idsToRestore)
    }

    @Test
    fun navAssistPhysicalShortcutMatcherCoversAllDeXChords() {
        assertEquals(ShortcutAction.WINDOW_SWITCHER, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON))
        assertEquals(ShortcutAction.WINDOW_SWITCHER, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_F3, 0))
        assertEquals(ShortcutAction.APP_DRAWER, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.APP_DRAWER, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        assertEquals(ShortcutAction.SHOW_DESKTOP, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.SNAP_LEFT, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.SNAP_RIGHT, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.MAXIMIZE_TOGGLE, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.MINIMIZE_OR_RESTORE, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.META_META_ON))
        assertEquals(ShortcutAction.CLOSE_FOCUSED, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_F4, KeyEvent.META_ALT_ON))
        assertEquals(ShortcutAction.CLOSE_FOCUSED, NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_W, KeyEvent.META_META_ON))
        assertNull(NavAssistService.matchPhysicalShortcut(KeyEvent.KEYCODE_D, 0))
    }
}
