package io.github.lengsukq.opaldesk.daemon.input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

/**
 * Pure-JVM parser and decision helper for {@code InputMethodManagerService} dump state.
 *
 * <p>On Android 17+ ({@code FLAG_REFACTOR_INSETS_CONTROLLER}), an app inside an isolated
 * {@code VirtualDisplay} with {@code DISPLAY_IME_POLICY_FALLBACK_DISPLAY} records a genuine
 * {@code SHOW_SOFT_INPUT} request in {@code mSoftInputShowHideHistory}, yet Display 0's
 * {@code RemoteInsetsControlTarget} may abort {@code showImePostLayout} within milliseconds,
 * leaving {@code mInputShown=false}. This inspector detects that exact condition after a user
 * tap so {@code OpalDeskDaemon} can seamlessly summon the host's Display 0 IME bridge while
 * {@code VIRTUAL_DISPLAY_FLAG_OWN_FOCUS} keeps the managed app's cursor active.
 */
public final class ImmsDumpInspector {
    public static final int TYPE_MASK_CLASS = 0x0000000f;
    public static final int TYPE_MASK_VARIATION = 0x00000ff0;
    public static final int TYPE_CLASS_TEXT = 0x00000001;
    public static final int TYPE_CLASS_NUMBER = 0x00000002;
    public static final int TYPE_CLASS_PHONE = 0x00000003;
    public static final int TYPE_TEXT_FLAG_MULTI_LINE = 0x00020000;
    public static final int TYPE_TEXT_FLAG_NO_SUGGESTIONS = 0x00080000;
    public static final int TYPE_TEXT_VARIATION_PASSWORD = 0x00000080;
    public static final int TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090;
    public static final int TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0;

    private static final long TAP_BACK_TOLERANCE_MS = 350L;
    private static final long TAP_FORWARD_TOLERANCE_MS = 600L;

    public static final class Snapshot {
        public final boolean inputShown;
        public final int curClientDisplayId;
        public final int curEditorInputType;
        public final int latestShowSeq;
        public final long latestShowTimestampMs;
        public final int latestShowInputType;
        public final String latestShowReason;

        public Snapshot(
                boolean inputShown,
                int curClientDisplayId,
                int curEditorInputType,
                int latestShowSeq,
                long latestShowTimestampMs,
                int latestShowInputType,
                String latestShowReason) {
            this.inputShown = inputShown;
            this.curClientDisplayId = curClientDisplayId;
            this.curEditorInputType = curEditorInputType;
            this.latestShowSeq = latestShowSeq;
            this.latestShowTimestampMs = latestShowTimestampMs;
            this.latestShowInputType = latestShowInputType;
            this.latestShowReason = latestShowReason != null ? latestShowReason : "";
        }

        public int effectiveInputType(int targetDisplayId) {
            if (latestShowInputType != 0) return latestShowInputType;
            if (curClientDisplayId == targetDisplayId && curEditorInputType != 0) {
                return curEditorInputType;
            }
            return TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE;
        }
    }

    private ImmsDumpInspector() {}

    public static Snapshot parseDump(String dumpText, int targetDisplayId) {
        if (dumpText == null || dumpText.isEmpty()) return null;
        try {
            return parseReader(new BufferedReader(new StringReader(dumpText)), targetDisplayId);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Single-pass streaming parser over {@code dumpsys input_method} output. Drains past
     * {@code View Hierarchy:} to EOF without retaining the view dump in memory.
     */
    public static Snapshot parseReader(BufferedReader br, int targetDisplayId) throws IOException {
        boolean inputShown = false;
        int curClientDisplayId = -1;
        int curEditorInputType = 0;

        int latestShowSeq = -1;
        long latestShowTs = -1L;
        int latestShowInputType = 0;
        String latestShowReason = "";

        boolean inCurEditorInfo = false;
        boolean inShowHideHistory = false;
        boolean inViewHierarchy = false;

        int entrySeq = -1;
        long entryTs = -1L;
        int entryDisplayId = -1;
        int entryInputType = 0;
        String entryReason = "";

        String line;
        while ((line = br.readLine()) != null) {
            if (inViewHierarchy) {
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("View Hierarchy:")) {
                inViewHierarchy = true;
                inShowHideHistory = false;
                inCurEditorInfo = false;
                continue;
            }

            if (trimmed.equals("mSoftInputShowHideHistory:")) {
                inShowHideHistory = true;
                inCurEditorInfo = false;
                entrySeq = -1;
                entryTs = -1L;
                entryDisplayId = -1;
                entryInputType = 0;
                entryReason = "";
                continue;
            }

            if (inShowHideHistory) {
                if (!line.startsWith("    ") && !trimmed.isEmpty()) {
                    // End of mSoftInputShowHideHistory section — flush pending entry
                    if (entryDisplayId == targetDisplayId && isShowReason(entryReason) && entrySeq > latestShowSeq) {
                        latestShowSeq = entrySeq;
                        latestShowTs = entryTs;
                        latestShowInputType = entryInputType;
                        latestShowReason = entryReason;
                    }
                    inShowHideHistory = false;
                    entrySeq = -1;
                } else if (trimmed.startsWith("SoftInputShowHide[")) {
                    if (entryDisplayId == targetDisplayId && isShowReason(entryReason) && entrySeq > latestShowSeq) {
                        latestShowSeq = entrySeq;
                        latestShowTs = entryTs;
                        latestShowInputType = entryInputType;
                        latestShowReason = entryReason;
                    }
                    entrySeq = parseHashInt(trimmed);
                    entryTs = -1L;
                    entryDisplayId = -1;
                    entryInputType = 0;
                    entryReason = "";
                    continue;
                } else if (entrySeq >= 0) {
                    if (trimmed.startsWith("time=")) {
                        entryTs = parseLongAfter(trimmed, "timestamp=");
                    } else if (trimmed.startsWith("reason=")) {
                        entryReason = parseTokenAfter(trimmed, "reason=");
                    } else if (trimmed.startsWith("requestClient=")) {
                        entryDisplayId = parseIntAfter(trimmed, "mSelfReportedDisplayId=");
                    } else if (trimmed.startsWith("editorInfo:")) {
                        int parsed = parseIntAutoRadixAfter(trimmed, "inputType=");
                        if (parsed > 0) entryInputType = parsed;
                    }
                    continue;
                }
            }

            if (trimmed.equals("mInputShown=true")) {
                inputShown = true;
                continue;
            }
            if (trimmed.startsWith("curClient=ClientState{") || trimmed.startsWith("mCurClient=ClientState{")) {
                int disp = parseIntAfter(trimmed, "mSelfReportedDisplayId=");
                if (disp >= 0 && (curClientDisplayId < 0 || disp == targetDisplayId)) {
                    curClientDisplayId = disp;
                }
                inCurEditorInfo = false;
                continue;
            }
            if (trimmed.equals("curEditorInfo:") || trimmed.equals("mCurAttribute:")) {
                inCurEditorInfo = true;
                continue;
            }
            if (inCurEditorInfo) {
                if (trimmed.startsWith("inputType=")) {
                    int parsed = parseIntAutoRadixAfter(trimmed, "inputType=");
                    if (parsed > 0 && (curEditorInputType == 0 || curClientDisplayId == targetDisplayId)) {
                        curEditorInputType = parsed;
                    }
                    inCurEditorInfo = false;
                } else if (!line.startsWith("      ")) {
                    inCurEditorInfo = false;
                }
            }
        }

        if (entrySeq >= 0 && entryDisplayId == targetDisplayId && isShowReason(entryReason) && entrySeq > latestShowSeq) {
            latestShowSeq = entrySeq;
            latestShowTs = entryTs;
            latestShowInputType = entryInputType;
            latestShowReason = entryReason;
        }

        return new Snapshot(
                inputShown,
                curClientDisplayId,
                curEditorInputType,
                latestShowSeq,
                latestShowTs,
                latestShowInputType,
                latestShowReason);
    }

    /**
     * Decides whether to auto-summon the Display 0 host IME bridge after a tap on {@code targetDisplayId}.
     * Never triggers when the system IME is already visible ({@code snap.inputShown == true}),
     * nor for stale history entries predating the tap.
     */
    public static boolean shouldTriggerHostImeBridge(
            Snapshot snap,
            int targetDisplayId,
            int lastHandledShowSeq,
            long tapDownUptimeMs,
            long nowUptimeMs) {
        if (snap == null || targetDisplayId <= 0 || snap.inputShown) {
            return false;
        }
        return matchesFreshShowRequest(snap, targetDisplayId, lastHandledShowSeq, tapDownUptimeMs, nowUptimeMs);
    }

    /**
     * Decides whether to forward an IME open request to an active OpalCast remote receiver
     * (e.g. Tablet viewing Phone's desktop or standalone floating app). Unlike Display 0 local bridge,
     * this ALSO fires when {@code snap.inputShown == true} (Android 16 popped the keyboard on the
     * sender phone's screen while the user is actually typing on the remote tablet!).
     */
    public static boolean shouldTriggerRemoteCastIme(
            Snapshot snap,
            int targetDisplayId,
            int lastHandledShowSeq,
            long tapDownUptimeMs,
            long nowUptimeMs) {
        if (snap == null || targetDisplayId <= 0) {
            return false;
        }
        return matchesFreshShowRequest(snap, targetDisplayId, lastHandledShowSeq, tapDownUptimeMs, nowUptimeMs);
    }

    private static boolean matchesFreshShowRequest(
            Snapshot snap,
            int targetDisplayId,
            int lastHandledShowSeq,
            long tapDownUptimeMs,
            long nowUptimeMs) {
        if (snap.latestShowSeq <= 0 || snap.latestShowSeq <= lastHandledShowSeq) {
            return false;
        }
        if (snap.latestShowTimestampMs > 0L && tapDownUptimeMs > 0L) {
            if (snap.latestShowTimestampMs < tapDownUptimeMs - TAP_BACK_TOLERANCE_MS) {
                return false;
            }
            if (nowUptimeMs > 0L && snap.latestShowTimestampMs > nowUptimeMs + TAP_FORWARD_TOLERANCE_MS) {
                return false;
            }
        }
        if ("ATTACH_NEW_INPUT".equals(snap.latestShowReason)) {
            return snap.latestShowInputType != 0
                    || (snap.curClientDisplayId == targetDisplayId && snap.curEditorInputType != 0);
        }
        return isShowReason(snap.latestShowReason);
    }

    /**
     * Maps a remote app's {@code EditorInfo.inputType} into a safe Display 0 host bridge inputType:
     * preserves number/phone keypads for OTP & numeric fields while stripping password variation bits
     * so OEM {@code FLAG_SECURE} security keyboards never interfere.
     */
    public static int sanitizeHostInputType(int rawInputType) {
        int cls = rawInputType & TYPE_MASK_CLASS;
        int variation = rawInputType & TYPE_MASK_VARIATION;
        if (cls == TYPE_CLASS_NUMBER) {
            return TYPE_CLASS_NUMBER;
        }
        if (cls == TYPE_CLASS_PHONE) {
            return TYPE_CLASS_PHONE;
        }
        boolean isPassword = (cls == TYPE_CLASS_TEXT)
                && (variation == TYPE_TEXT_VARIATION_PASSWORD
                || variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == TYPE_TEXT_VARIATION_WEB_PASSWORD);
        if (isPassword) {
            return TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        return TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_MULTI_LINE;
    }

    static boolean isShowReason(String reason) {
        if (reason == null || reason.isEmpty()) return false;
        return reason.startsWith("SHOW_") || "ATTACH_NEW_INPUT".equals(reason);
    }

    private static int parseHashInt(String s) {
        int idx = s.indexOf('#');
        if (idx < 0 || idx + 1 >= s.length()) return -1;
        int end = idx + 1;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == idx + 1) return -1;
        try {
            return Integer.parseInt(s.substring(idx + 1, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int parseIntAfter(String s, String key) {
        long val = parseLongAfter(s, key);
        return (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) ? (int) val : -1;
    }

    private static long parseLongAfter(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return -1L;
        int start = idx + key.length();
        int end = start;
        if (end < s.length() && s.charAt(end) == '-') end++;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == start || (end == start + 1 && s.charAt(start) == '-')) return -1L;
        try {
            return Long.parseLong(s.substring(start, end));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static int parseIntAutoRadixAfter(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int radix = 10;
        if (start + 2 <= s.length() && s.charAt(start) == '0'
                && (s.charAt(start + 1) == 'x' || s.charAt(start + 1) == 'X')) {
            radix = 16;
            start += 2;
        }
        int end = start;
        while (end < s.length()) {
            char c = s.charAt(end);
            boolean ok = (radix == 16)
                    ? (Character.isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))
                    : Character.isDigit(c);
            if (!ok) break;
            end++;
        }
        if (end == start) return 0;
        try {
            return (int) Long.parseLong(s.substring(start, end), radix);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String parseTokenAfter(String s, String key) {
        int idx = s.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = start;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) end++;
        return s.substring(start, end);
    }
}
