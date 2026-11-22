package io.github.lengsukq.opaldesk.desktop

import io.github.lengsukq.opaldesk.desktop.model.DesktopGeometry
import io.github.lengsukq.opaldesk.desktop.model.DesktopWindow
import io.github.lengsukq.opaldesk.desktop.model.IntRect
import io.github.lengsukq.opaldesk.desktop.workbench.WorkbenchDropLayout
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.LayoutMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.WindowPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkbenchDropLayoutTest {
    private val stage = IntRect(260, 200, 1560, 940)

    @Test
    fun `center occupies the whole stage and the two sides mirror each other`() {
        val left = requireNotNull(WorkbenchDropLayout.at(stage, 160, 320f, 450f))
        val center = requireNotNull(WorkbenchDropLayout.at(stage, 160, 910f, 450f))
        val right = requireNotNull(WorkbenchDropLayout.at(stage, 160, 1510f, 450f))

        assertEquals(stage, center.bounds)
        assertNull(center.companionBounds)
        assertEquals(IntRect(260, 200, 906, 940), left.bounds)
        assertEquals(IntRect(914, 200, 1560, 940), right.bounds)
        assertEquals(left.bounds, right.companionBounds)
        assertEquals(right.bounds, left.companionBounds)
    }

    @Test
    fun `rail and taskbar reject drops and a narrow stage only accepts replacement`() {
        assertNull(WorkbenchDropLayout.at(stage, 160, 150f, 450f))
        assertNull(WorkbenchDropLayout.at(stage, 160, 500f, 150f))
        assertNull(WorkbenchDropLayout.at(stage, 160, 500f, 980f))
        val narrow = IntRect(0, 100, 390, 650)
        val fallback = requireNotNull(WorkbenchDropLayout.at(narrow, 160, 25f, 300f))
        assertEquals(narrow, fallback.bounds)
        assertNull(fallback.companionBounds)
    }

    @Test
    fun `split divider recognizes the inset stage and preserves the clear rail area`() {
        val left = requireNotNull(WorkbenchDropLayout.at(stage, 160, 320f, 450f))
        val windows = listOf(left.bounds, requireNotNull(left.companionBounds)).mapIndexed { index, bounds ->
            DesktopWindow(index + 1, "app.$index", LayoutMode.FREEFORM, WindowPresentation.FLOATING, bounds, null, false, false)
        }
        val pair = DesktopGeometry.detectPairedHorizontalSplit(windows, 1600, 1000, 160, false, workArea = stage)
        assertNotNull(pair)
        val (resizedLeft, resizedRight) = DesktopGeometry.computePairedSplitResizedBounds(requireNotNull(pair), 0, 160)
        assertEquals(stage.left, resizedLeft.left)
        assertEquals(320, resizedLeft.width)
        assertEquals(stage.right, resizedRight.right)
    }
}
