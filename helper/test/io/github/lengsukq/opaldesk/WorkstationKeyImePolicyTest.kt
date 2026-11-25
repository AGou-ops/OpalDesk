package io.github.lengsukq.opaldesk

import android.view.KeyEvent
import io.github.lengsukq.opaldesk.ui.WorkstationKeyDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkstationKeyImePolicyTest {

    @Test
    fun `recognizes dedicated IME language switch keys`() {
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_LANGUAGE_SWITCH, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SWITCH_CHARSET, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_KANA, 0))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_EISU, 0))
    }

    @Test
    fun `recognizes standard Ctrl Shift Meta plus Space IME switch chords`() {
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON))
        assertTrue(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_META_ON))
    }

    @Test
    fun `does not misclassify plain space or Alt Space or normal letters`() {
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, 0))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_ALT_ON))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON))
        assertFalse(WorkstationKeyDispatcher.isImeSwitchShortcut(KeyEvent.KEYCODE_ENTER, 0))
    }
}
