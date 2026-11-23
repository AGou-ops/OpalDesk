package io.github.lengsukq.opaldesk.daemon.input;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class ImmsDumpInspectorTest {

    private static final String ANDROID_17_SMS_ABORTED_DUMP =
            "  mUserDataRepository:\n"
            + "    userId=0\n"
            + "      curClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      curEditorInfo:\n"
            + "        inputType=0x200003 imeOptions=0x6 privateImeOptions=null\n"
            + "  mSoftInputShowHideHistory:\n"
            + "    SoftInputShowHide[0] #804:\n"
            + "      time=2026-11-23 11:43:24.098 (timestamp=371506893)\n"
            + "      reason=HIDE_SOFT_INPUT inFullscreenMode=false\n"
            + "      requestClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      editorInfo: inputType=2097155 privateImeOptions=null fieldId (viewId)=2131369015\n"
            + "    SoftInputShowHide[0] #805:\n"
            + "      time=2026-11-23 11:43:24.667 (timestamp=371507463)\n"
            + "      reason=SHOW_SOFT_INPUT inFullscreenMode=false\n"
            + "      requestClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      editorInfo: inputType=2097155 privateImeOptions=null fieldId (viewId)=2131369015\n"
            + "    SoftInputShowHide[0] #806:\n"
            + "      time=2026-11-23 11:43:24.697 (timestamp=371507493)\n"
            + "      reason=HIDE_SOFT_INPUT inFullscreenMode=false\n"
            + "      requestClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      editorInfo: inputType=2097155 privateImeOptions=null fieldId (viewId)=2131369015\n"
            + "  mVisibilityStateComputer:\n"
            + "      mInputShown=false\n"
            + "    View Hierarchy:\n"
            + "      mInputShown=true\n";

    private static final String ANDROID_17_PASSWORD_ABORTED_DUMP =
            "  mUserDataRepository:\n"
            + "    userId=0\n"
            + "      curClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      curEditorInfo:\n"
            + "        inputType=0x200081 imeOptions=0x6 privateImeOptions=null\n"
            + "  mSoftInputShowHideHistory:\n"
            + "    SoftInputShowHide[0] #791:\n"
            + "      time=2026-11-23 11:31:29.792 (timestamp=370792587)\n"
            + "      reason=SHOW_SOFT_INPUT inFullscreenMode=false\n"
            + "      requestClient=ClientState{19d42d7 mUid=10340 mPid=4723 mSelfReportedDisplayId=62}\n"
            + "      editorInfo: inputType=0 privateImeOptions=null fieldId (viewId)=0\n"
            + "  mVisibilityStateComputer:\n"
            + "      mInputShown=false\n";

    private static final String ANDROID_16_NATIVE_SHOWN_DUMP =
            "  mUserDataRepository:\n"
            + "    userId=0\n"
            + "      curClient=ClientState{90cf5c0 mUid=10439 mPid=26035 mSelfReportedDisplayId=119}\n"
            + "      curEditorInfo:\n"
            + "        inputType=0x24001 imeOptions=0x40000006\n"
            + "  mSoftInputShowHideHistory:\n"
            + "    SoftInputShowHide[0] #3208:\n"
            + "      time=2026-11-23 11:30:56.836 (timestamp=468521032)\n"
            + "      reason=SHOW_SOFT_INPUT inFullscreenMode=false\n"
            + "      requestClient=ClientState{90cf5c0 mUid=10439 mPid=26035 mSelfReportedDisplayId=119}\n"
            + "      editorInfo: inputType=147457 privateImeOptions=null fieldId (viewId)=0\n"
            + "  mVisibilityStateComputer:\n"
            + "      mInputShown=true\n";

    @Test
    public void detectsAndroid17AbortedShowEvenWhenFollowedByAutoHide() {
        ImmsDumpInspector.Snapshot snap = ImmsDumpInspector.parseDump(ANDROID_17_SMS_ABORTED_DUMP, 62);
        assertNotNull(snap);
        assertFalse(snap.inputShown);
        assertEquals(62, snap.curClientDisplayId);
        assertEquals(0x200003, snap.curEditorInputType);
        assertEquals(805, snap.latestShowSeq);
        assertEquals(371507463L, snap.latestShowTimestampMs);
        assertEquals(2097155, snap.latestShowInputType);

        assertTrue(ImmsDumpInspector.shouldTriggerHostImeBridge(snap, 62, 804, 371507440L, 371507570L));
        // Already handled seq must never re-trigger on a subsequent non-text tap
        assertFalse(ImmsDumpInspector.shouldTriggerHostImeBridge(snap, 62, 805, 371507440L, 371507570L));
        // Stale show timestamp predating a new tap must never trigger
        assertFalse(ImmsDumpInspector.shouldTriggerHostImeBridge(snap, 62, 800, 371509000L, 371509120L));
    }

    @Test
    public void handlesPasswordFieldWithZeroHistoryEditorInfoAndSanitizesAwayPasswordFlag() {
        ImmsDumpInspector.Snapshot snap = ImmsDumpInspector.parseDump(ANDROID_17_PASSWORD_ABORTED_DUMP, 62);
        assertNotNull(snap);
        assertTrue(ImmsDumpInspector.shouldTriggerHostImeBridge(snap, 62, 790, 370792550L, 370792700L));
        assertEquals(0x200081, snap.effectiveInputType(62));
        int sanitized = ImmsDumpInspector.sanitizeHostInputType(snap.effectiveInputType(62));
        assertEquals(ImmsDumpInspector.TYPE_CLASS_TEXT | ImmsDumpInspector.TYPE_TEXT_FLAG_NO_SUGGESTIONS, sanitized);
        assertEquals(0, sanitized & ImmsDumpInspector.TYPE_TEXT_VARIATION_PASSWORD);
    }

    @Test
    public void neverInterferesWhenNativeFallbackImeIsAlreadyShowing() {
        ImmsDumpInspector.Snapshot snap = ImmsDumpInspector.parseDump(ANDROID_16_NATIVE_SHOWN_DUMP, 119);
        assertNotNull(snap);
        assertTrue(snap.inputShown);
        assertFalse(ImmsDumpInspector.shouldTriggerHostImeBridge(snap, 119, 3200, 468521000L, 468521150L));
        // However during remote casting (Phone -> Tablet), MUST forward IME open to the remote receiver!
        assertTrue(ImmsDumpInspector.shouldTriggerRemoteCastIme(snap, 119, 3200, 468521000L, 468521150L));
        // Never double-fire on already-handled sequence
        assertFalse(ImmsDumpInspector.shouldTriggerRemoteCastIme(snap, 119, snap.latestShowSeq, 468521000L, 468521150L));
    }

    @Test
    public void preservesNumericAndPhoneInputTypesForHostBridge() {
        assertEquals(ImmsDumpInspector.TYPE_CLASS_PHONE, ImmsDumpInspector.sanitizeHostInputType(0x200003));
        assertEquals(ImmsDumpInspector.TYPE_CLASS_NUMBER, ImmsDumpInspector.sanitizeHostInputType(0x12));
        assertEquals(
                ImmsDumpInspector.TYPE_CLASS_TEXT | ImmsDumpInspector.TYPE_TEXT_FLAG_MULTI_LINE,
                ImmsDumpInspector.sanitizeHostInputType(0x24001));
    }
}
