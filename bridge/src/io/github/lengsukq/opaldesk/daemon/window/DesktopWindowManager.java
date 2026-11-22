package io.github.lengsukq.opaldesk.daemon.window;

import io.github.lengsukq.opaldesk.daemon.DisplayGeometryUtil;
import io.github.lengsukq.opaldesk.daemon.stream.SimpleJson;
import io.github.lengsukq.opaldesk.daemon.util.AtomicFiles;
import io.github.lengsukq.opaldesk.daemon.window.TilingLayoutEngine.WindowRect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure-Java desktop window manager state & geometry engine for OpalDesk v4.1.
 * Manages:
 * - Adaptive single-app golden centered bounds (default enabled=true, defaultWidthDp=460)
 * - 5-tier width snapping presets + per-app preference persistence
 * - Freeform multi-window staggered cascade placement (managed via Android MULTI_WINDOW mode 6)
 * - Multi-slot tiling state (SPLIT_2_H / SPLIT_2_V / SPLIT_3_MASTER / SPLIT_3_COLS / GRID_2X2)
 * - Binder code 50 JSON serialization
 */
public final class DesktopWindowManager {
    public static final String DEFAULT_PREFS_PATH = "/data/local/tmp/opaldesk_win_prefs.conf";

    public static final int DEFAULT_SINGLE_WIDTH_DP = 460;
    public static final int MIN_SINGLE_WIDTH_DP = 320;
    public static final int MAX_SINGLE_WIDTH_DP = 1200;
    public static final int MIN_WIDTH_PCT = 28;
    public static final int MAX_WIDTH_PCT = 100;
    public static final int MAX_SAVED_APP_PREFS = 256;
    public static final int MAX_VISIBLE_FREEFORM_WINDOWS = 10;
    public static final float FREEFORM_TITLE_BAR_DP = DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP;
    public static final float FREEFORM_BOTTOM_CHIN_DP = DisplayGeometryUtil.WINDOW_BOTTOM_CHIN_DP;
    private static final float ADAPTIVE_VERTICAL_MARGIN_DP = 16f;
    private static final int MIN_ADAPTIVE_VERTICAL_MARGIN_PX = 12;
    private static final int MAX_ADAPTIVE_VERTICAL_MARGIN_PX = 32;

    // 5-tier width snapping presets (% of display width)
    public static final int[] SNAP_WIDTH_PCTS = new int[]{32, 42, 55, 72, 100};
    public static final int DEFAULT_SNAP_TOLERANCE_PCT = 3;

    // Layout / window mode constants (aligned with OpalCast v1.1 & Binder IPC code 15/50/51/52)
    public static final String MODE_FULLSCREEN = "FULLSCREEN";
    public static final String MODE_ADAPTIVE_SINGLE = "ADAPTIVE_SINGLE";
    public static final String MODE_FREEFORM = "FREEFORM";
    public static final String MODE_TILING = "TILING";
    public static final String MODE_STANDALONE_APP = "STANDALONE_APP";

    // Semantic DeX window presentation states
    public static final String PRES_FLOATING = "FLOATING";
    public static final String PRES_MAXIMIZED = "MAXIMIZED";
    public static final String PRES_SNAPPED_LEFT = "SNAPPED_LEFT";
    public static final String PRES_SNAPPED_RIGHT = "SNAPPED_RIGHT";
    public static final String PRES_SNAPPED_TL = "SNAPPED_TL";
    public static final String PRES_SNAPPED_TR = "SNAPPED_TR";
    public static final String PRES_SNAPPED_BL = "SNAPPED_BL";
    public static final String PRES_SNAPPED_BR = "SNAPPED_BR";
    public static final String PRES_IMMERSIVE = "IMMERSIVE";

    // Binder code 51 & 52 integer modes
    public static final int INT_MODE_AUTO = 0;
    public static final int INT_MODE_FULLSCREEN = 1;
    public static final int INT_MODE_ADAPTIVE_SINGLE = 2;
    public static final int INT_MODE_FREEFORM = 3;

    // Desktop Aero Snap zones within WorkArea
    public static final int SNAP_NONE = 0;
    public static final int SNAP_MAXIMIZE_WORKAREA = 1;
    public static final int SNAP_LEFT_HALF = 2;
    public static final int SNAP_RIGHT_HALF = 3;
    public static final int SNAP_TOP_LEFT = 4;
    public static final int SNAP_TOP_RIGHT = 5;
    public static final int SNAP_BOTTOM_LEFT = 6;
    public static final int SNAP_BOTTOM_RIGHT = 7;

    public static final class ManagedWindow {
        public final int taskId;
        public final String pkg;
        public String mode;
        public String presentation;
        public WindowRect bounds;
        public WindowRect restoreBounds;
        public boolean focused;
        public boolean minimized;
        public int slotIndex;
        public long updatedAtMs;

        public ManagedWindow(
                int taskId,
                String pkg,
                String mode,
                WindowRect bounds,
                boolean focused,
                boolean minimized,
                int slotIndex) {
            this(taskId, pkg, mode, MODE_FULLSCREEN.equals(mode) ? PRES_IMMERSIVE : PRES_FLOATING,
                    bounds, null, focused, minimized, slotIndex, System.currentTimeMillis());
        }

        private ManagedWindow(
                int taskId,
                String pkg,
                String mode,
                String presentation,
                WindowRect bounds,
                WindowRect restoreBounds,
                boolean focused,
                boolean minimized,
                int slotIndex,
                long updatedAtMs) {
            this.taskId = taskId;
            this.pkg = (pkg != null) ? pkg : "";
            this.mode = (mode != null && !mode.isEmpty()) ? mode : MODE_ADAPTIVE_SINGLE;
            this.presentation = (presentation != null && !presentation.isEmpty())
                    ? presentation : (MODE_FULLSCREEN.equals(this.mode) ? PRES_IMMERSIVE : PRES_FLOATING);
            this.bounds = (bounds != null) ? bounds : new WindowRect(0, 0, 0, 0);
            this.restoreBounds = (restoreBounds != null && !restoreBounds.isEmpty()) ? restoreBounds : null;
            this.focused = focused;
            this.minimized = minimized;
            this.slotIndex = slotIndex;
            this.updatedAtMs = updatedAtMs;
        }

        public ManagedWindow copy() {
            return new ManagedWindow(taskId, pkg, mode, presentation, bounds, restoreBounds, focused, minimized, slotIndex, updatedAtMs);
        }
    }

    public static final class AppWindowPref {
        public final String pkg;
        public final String mode;     // "" | FULLSCREEN | ADAPTIVE_SINGLE | FREEFORM
        public final int widthPct;    // 0 if unset, else 28..100
        public final int widthDp;     // 0 if unset, else 320..1200
        public final WindowRect freeformBounds; // nullable

        public AppWindowPref(String pkg, String mode, int widthPct, int widthDp, WindowRect freeformBounds) {
            this.pkg = (pkg != null) ? pkg : "";
            this.mode = (mode != null) ? mode : "";
            this.widthPct = (widthPct >= MIN_WIDTH_PCT && widthPct <= MAX_WIDTH_PCT) ? widthPct : 0;
            this.widthDp = (widthDp >= MIN_SINGLE_WIDTH_DP && widthDp <= MAX_SINGLE_WIDTH_DP) ? widthDp : 0;
            this.freeformBounds = (freeformBounds != null && !freeformBounds.isEmpty()) ? freeformBounds : null;
        }
    }

    private final Object lock = new Object();
    private final File prefsFile;

    private boolean adaptiveSingleEnabled = true;
    private int defaultSingleWidthDp = DEFAULT_SINGLE_WIDTH_DP;
    private String defaultLaunchMode = MODE_FREEFORM;
    private boolean taskbarAutoHide = false;
    private long stateSeq = 0L;

    // taskId -> ManagedWindow (insertion order preserved; most recently focused moved to end)
    private final LinkedHashMap<Integer, ManagedWindow> managedWindows = new LinkedHashMap<>();

    // Multi-slot tiling state
    private String tilingTopology = TilingLayoutEngine.TOPOLOGY_NONE;
    private int[] tilingRatios = new int[]{50, 50};
    private final String[] tilingSlotPkgs = new String[]{"", "", "", ""};
    private final int[] tilingSlotTaskIds = new int[]{-1, -1, -1, -1};

    // Per-app remembered window geometry/mode
    private final LinkedHashMap<String, AppWindowPref> appPrefs = new LinkedHashMap<>();

    public DesktopWindowManager() {
        this(new File(DEFAULT_PREFS_PATH));
    }

    public DesktopWindowManager(File prefsFile) {
        this.prefsFile = prefsFile;
        loadPrefs();
    }

    // ---- Config & Snap Utilities ----

    public boolean isAdaptiveSingleEnabled() {
        synchronized (lock) {
            return adaptiveSingleEnabled;
        }
    }

    public int getDefaultSingleWidthDp() {
        synchronized (lock) {
            return defaultSingleWidthDp;
        }
    }

    public String getDefaultLaunchMode() {
        synchronized (lock) {
            return defaultLaunchMode;
        }
    }

    public boolean isTaskbarAutoHide() {
        synchronized (lock) {
            return taskbarAutoHide;
        }
    }

    public void setTaskbarAutoHide(boolean autoHide) {
        synchronized (lock) {
            if (this.taskbarAutoHide != autoHide) {
                this.taskbarAutoHide = autoHide;
                bumpSeqLocked();
                savePrefsLocked();
            }
        }
    }

    public long getStateSeq() {
        synchronized (lock) {
            return stateSeq;
        }
    }

    private long bumpSeqLocked() {
        return ++stateSeq;
    }

    public int computeActiveTaskbarInsetPx(int screenH, int dpi) {
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 240));
        int dp = isTaskbarAutoHide()
                ? DisplayGeometryUtil.TASKBAR_AUTOHIDE_INSET_DP
                : DisplayGeometryUtil.TASKBAR_RESERVED_INSET_DP;
        return Math.min(Math.max(0, screenH / 3), DisplayGeometryUtil.dpToPx(dp, effDpi));
    }

    public static String normalizeLaunchMode(String raw) {
        if (raw == null) return MODE_FREEFORM;
        String m = raw.trim().toUpperCase(Locale.US);
        if (MODE_FULLSCREEN.equals(m) || "FULL".equals(m)) return MODE_FULLSCREEN;
        if (MODE_ADAPTIVE_SINGLE.equals(m) || "ADAPTIVE".equals(m) || "SINGLE".equals(m)) return MODE_ADAPTIVE_SINGLE;
        return MODE_FREEFORM;
    }

    public void setAdaptiveSingleConfig(boolean enabled, int widthDp) {
        synchronized (lock) {
            this.adaptiveSingleEnabled = enabled;
            if (widthDp > 0) {
                this.defaultSingleWidthDp = clampWidthDp(widthDp);
            }
            if (!enabled) {
                this.defaultLaunchMode = MODE_FULLSCREEN;
            } else if (MODE_FULLSCREEN.equals(this.defaultLaunchMode)) {
                this.defaultLaunchMode = MODE_FREEFORM;
            }
            savePrefsLocked();
        }
    }

    public void setDefaultLaunchMode(String mode) {
        String normalized = normalizeLaunchMode(mode);
        synchronized (lock) {
            this.defaultLaunchMode = normalized;
            this.adaptiveSingleEnabled = !MODE_FULLSCREEN.equals(normalized);
            savePrefsLocked();
        }
    }

    public static int clampWidthDp(int dp) {
        return Math.max(MIN_SINGLE_WIDTH_DP, Math.min(MAX_SINGLE_WIDTH_DP, dp));
    }

    public static int clampWidthPct(int pct) {
        return Math.max(MIN_WIDTH_PCT, Math.min(MAX_WIDTH_PCT, pct));
    }

    /**
     * Snaps a raw width percentage to the nearest of the 5 golden tiers (32/42/55/72/100%)
     * if within {@code tolerancePct}; otherwise returns the clamped percentage unchanged.
     */
    public static int snapWidthPct(int rawPct, int tolerancePct) {
        int clamped = clampWidthPct(rawPct);
        int tol = Math.max(0, tolerancePct);
        int best = clamped;
        int bestDist = tol + 1;
        for (int tier : SNAP_WIDTH_PCTS) {
            int d = Math.abs(clamped - tier);
            if (d <= tol && d < bestDist) {
                best = tier;
                bestDist = d;
            }
        }
        return best;
    }

    public static int parseModeString(String modeStr) {
        if (modeStr == null) return INT_MODE_AUTO;
        String m = modeStr.trim().toLowerCase(Locale.US);
        switch (m) {
            case "fullscreen":
            case "full":
                return INT_MODE_FULLSCREEN;
            case "adaptive":
            case "adaptive_single":
            case "single":
            case "centered":
                return INT_MODE_ADAPTIVE_SINGLE;
            case "freeform":
            case "window":
            case "floating":
                return INT_MODE_FREEFORM;
            default:
                return INT_MODE_AUTO;
        }
    }

    // ---- Layout Geometry Calculations ----

    /**
     * Computes centered golden single-app bounds for {@code pkg} on a virtual display
     * ({@code screenW x screenH @ dpi}) with bottom Dock avoidance.
     * Passing {@code overrideWidthPct} (28..100) or {@code overrideWidthDp} (320..1200)
     * takes priority over saved per-app preferences and the global default width.
     */
    public WindowRect computeAdaptiveSingleBounds(
            int screenW,
            int screenH,
            int dpi,
            int bottomDockPx,
            String pkg,
            int overrideWidthPct,
            int overrideWidthDp) {
        int sw = Math.max(480, screenW);
        int sh = Math.max(480, screenH);
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 240));
        int dock = Math.max(0, Math.min(sh / 3, bottomDockPx));
        int workBottom = Math.max(320, sh - dock);

        AppWindowPref pref;
        int defDp;
        synchronized (lock) {
            pref = (pkg != null && !pkg.isEmpty()) ? appPrefs.get(pkg) : null;
            defDp = defaultSingleWidthDp;
        }

        int desiredW;
        if (overrideWidthPct >= MIN_WIDTH_PCT && overrideWidthPct <= MAX_WIDTH_PCT) {
            if (overrideWidthPct >= 98) {
                return new WindowRect(0, 0, sw, workBottom);
            }
            desiredW = Math.round(sw * (overrideWidthPct / 100f));
        } else if (overrideWidthDp >= MIN_SINGLE_WIDTH_DP && overrideWidthDp <= MAX_SINGLE_WIDTH_DP) {
            desiredW = Math.round(overrideWidthDp * (effDpi / 160f));
        } else if (pref != null && pref.widthPct >= MIN_WIDTH_PCT) {
            if (pref.widthPct >= 98) {
                return new WindowRect(0, 0, sw, workBottom);
            }
            desiredW = Math.round(sw * (pref.widthPct / 100f));
        } else if (pref != null && pref.widthDp >= MIN_SINGLE_WIDTH_DP) {
            desiredW = Math.round(pref.widthDp * (effDpi / 160f));
        } else {
            desiredW = Math.round(defDp * (effDpi / 160f));
        }

        int minPx = Math.min(sw, Math.max(360, Math.round(sw * (MIN_WIDTH_PCT / 100f))));
        int clampedW = Math.max(minPx, Math.min(sw, desiredW));
        if (clampedW >= sw - 24 || (sh > sw && clampedW >= Math.round(sw * 0.92f))) {
            return new WindowRect(0, 0, sw, workBottom);
        }

        // Keep symmetrical top/bottom breathing margins even when dock == 0 (Mac remote DeX mode)
        // so centered apps float cleanly inside the virtual desktop canvas.
        int breathMargin = Math.max(MIN_ADAPTIVE_VERTICAL_MARGIN_PX,
                Math.min(MAX_ADAPTIVE_VERTICAL_MARGIN_PX,
                        Math.round(ADAPTIVE_VERTICAL_MARGIN_DP * effDpi / 160f)));
        int topMargin = (dock > 0) ? Math.min(16, Math.max(8, Math.round(sh * 0.01f))) : breathMargin;
        int bottomMargin = (dock > 0) ? Math.min(12, Math.max(6, Math.round(sh * 0.008f))) : breathMargin;
        int maxMarginEach = Math.max(0, (workBottom - 320) / 2);
        topMargin = Math.min(topMargin, maxMarginEach);
        bottomMargin = Math.min(bottomMargin, maxMarginEach);
        int top = topMargin;
        int bottom = workBottom - bottomMargin;
        int left = Math.max(0, (sw - clampedW) / 2);
        return new WindowRect(left, top, left + clampedW, bottom);
    }

    /**
     * Computes staggered cascading initial bounds for a new freeform window so multiple
     * windows do not stack identically on top of each other.
     * Enforces the strict DeX invariant: {@code top >= workArea.top + headerPx} and
     * {@code bottom <= workArea.bottom} so headers never clip off-screen and windows never
     * hide behind the bottom Taskbar.
     */
    public WindowRect computeNextFreeformBounds(
            int screenW, int screenH, int dpi, int bottomDockPx, String pkg) {
        int sw = Math.max(480, screenW);
        int sh = Math.max(480, screenH);
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 240));
        int dock = Math.max(0, Math.min(sh / 3, bottomDockPx));
        int topSafeMarginPx = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WORKAREA_TOP_INSET_DP, effDpi);
        int headerPx = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, effDpi);
        int workBottom = Math.max(360, sh - dock);
        int minTitleTop = Math.min(Math.max(0, workBottom - 280), topSafeMarginPx + headerPx);

        synchronized (lock) {
            AppWindowPref pref = (pkg != null && !pkg.isEmpty()) ? appPrefs.get(pkg) : null;
            if (pref != null && pref.freeformBounds != null) {
                WindowRect candidate = clampBoundsToWorkArea(pref.freeformBounds, sw, workBottom, minTitleTop);
                return avoidExactOriginCollisionLocked(candidate, sw, workBottom, minTitleTop);
            }

            int baseW = Math.round(680f * effDpi / 160f);
            int w = Math.max(440, Math.min(Math.round(sw * 0.56f), Math.max(Math.round(sw * 0.38f), baseW)));
            if (sw < 900) w = Math.max(360, Math.round(sw * 0.72f));
            w = Math.min(sw - 32, w);

            int availH = Math.max(300, workBottom - minTitleTop - 16);
            int h = Math.max(360, Math.min(availH, Math.round((workBottom - minTitleTop) * 0.84f)));

            int visibleFreeformCount = 0;
            for (ManagedWindow mw : managedWindows.values()) {
                if (!mw.minimized && MODE_FREEFORM.equals(mw.mode)) {
                    visibleFreeformCount++;
                }
            }

            int stepIdx = visibleFreeformCount % 6;
            int stepX = Math.max(40, Math.min(96, (sw - w) / 5));
            int stepY = Math.max(28, Math.min(52, Math.max(28, (availH - h + 36) / 3)));

            int baseLeft = Math.max(24, (sw - w) / 2 - stepX);
            int baseTop = Math.max(minTitleTop, minTitleTop + Math.max(0, (workBottom - minTitleTop - h) / 2));

            int left = baseLeft + stepIdx * stepX;
            int top = baseTop + stepIdx * stepY;
            if (left + w > sw - 16) {
                left = Math.max(16, 24 + (stepIdx * 36) % Math.max(40, sw - w - 32));
            }
            if (top + h > workBottom - 12) {
                top = Math.max(minTitleTop, minTitleTop + (stepIdx * 28) % Math.max(32, workBottom - h - minTitleTop - 12));
            }
            WindowRect initial = clampBoundsToWorkArea(new WindowRect(left, top, left + w, top + h), sw, workBottom, minTitleTop);
            return avoidExactOriginCollisionLocked(initial, sw, workBottom, minTitleTop);
        }
    }

    /**
     * Computes pixel bounds for Desktop Aero Snap zones inside the usable {@link DisplayGeometryUtil.WorkArea}
     * (pure bottom DeX Taskbar, no top menu bar, 38dp header seated flush above {@code top}).
     */
    public static WindowRect computeSnapBounds(
            int snapZone, int screenW, int screenH, int dpi, boolean taskbarAutoHide) {
        DisplayGeometryUtil.WorkArea wa = DisplayGeometryUtil.computeDesktopWorkArea(
                screenW, screenH, dpi, taskbarAutoHide);
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 160));
        int headerPx = DisplayGeometryUtil.dpToPx(DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, effDpi);
        int top = Math.min(Math.max(0, wa.bottom - 240), wa.top + headerPx);
        int bottom = Math.max(top + 220, wa.bottom);
        int left = wa.left;
        int right = wa.right;
        int gap = Math.max(4, DisplayGeometryUtil.dpToPx(8, effDpi));
        int halfGap = gap / 2;
        int midX = (left + right) / 2;
        int midY = (top + bottom) / 2;
        int quarterBottomTop = Math.min(bottom - 180, midY + halfGap + headerPx);

        switch (snapZone) {
            case SNAP_LEFT_HALF:
                return new WindowRect(left, top, Math.max(left + 200, midX - halfGap), bottom);
            case SNAP_RIGHT_HALF:
                return new WindowRect(Math.min(right - 200, midX + halfGap), top, right, bottom);
            case SNAP_TOP_LEFT:
                return new WindowRect(left, top, Math.max(left + 200, midX - halfGap), Math.max(top + 180, midY - halfGap));
            case SNAP_TOP_RIGHT:
                return new WindowRect(Math.min(right - 200, midX + halfGap), top, right, Math.max(top + 180, midY - halfGap));
            case SNAP_BOTTOM_LEFT:
                return new WindowRect(left, quarterBottomTop, Math.max(left + 200, midX - halfGap), bottom);
            case SNAP_BOTTOM_RIGHT:
                return new WindowRect(Math.min(right - 200, midX + halfGap), quarterBottomTop, right, bottom);
            case SNAP_MAXIMIZE_WORKAREA:
            default:
                return new WindowRect(left, top, right, bottom);
        }
    }

    public static String classifyPresentation(
            String mode, WindowRect bounds, int screenW, int screenH, int dpi, boolean taskbarAutoHide) {
        if (MODE_FULLSCREEN.equals(mode)) return PRES_IMMERSIVE;
        if (bounds == null || bounds.isEmpty() || screenW <= 0 || screenH <= 0) return PRES_FLOATING;
        int tol = Math.max(16, DisplayGeometryUtil.dpToPx(12, dpi > 0 ? dpi : 160));
        if (approxEquals(bounds, computeSnapBounds(SNAP_MAXIMIZE_WORKAREA, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_MAXIMIZED;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_LEFT_HALF, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_LEFT;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_RIGHT_HALF, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_RIGHT;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_TOP_LEFT, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_TL;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_TOP_RIGHT, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_TR;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_BOTTOM_LEFT, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_BL;
        }
        if (approxEquals(bounds, computeSnapBounds(SNAP_BOTTOM_RIGHT, screenW, screenH, dpi, taskbarAutoHide), tol)) {
            return PRES_SNAPPED_BR;
        }
        return PRES_FLOATING;
    }

    private static boolean approxEquals(WindowRect a, WindowRect b, int tol) {
        if (a == null || b == null) return false;
        return Math.abs(a.left - b.left) <= tol
                && Math.abs(a.top - b.top) <= tol
                && Math.abs(a.right - b.right) <= tol
                && Math.abs(a.bottom - b.bottom) <= tol;
    }

    private WindowRect avoidExactOriginCollisionLocked(WindowRect rect, int sw, int workBottom, int minTop) {
        WindowRect cur = rect;
        for (int attempt = 0; attempt < 6; attempt++) {
            boolean collision = false;
            for (ManagedWindow mw : managedWindows.values()) {
                if (mw.minimized || !MODE_FREEFORM.equals(mw.mode) || mw.bounds == null) continue;
                if (Math.abs(mw.bounds.left - cur.left) < 24 && Math.abs(mw.bounds.top - cur.top) < 24) {
                    collision = true;
                    break;
                }
            }
            if (!collision) return cur;
            int w = cur.width();
            int h = cur.height();
            int nl = cur.left + 48;
            int nt = cur.top + 40;
            if (nl + w > sw - 12) nl = 24;
            if (nt + h > workBottom - 12) nt = Math.max(24, minTop);
            cur = clampBoundsToWorkArea(new WindowRect(nl, nt, nl + w, nt + h), sw, workBottom, minTop);
        }
        return cur;
    }

    public static WindowRect clampBoundsToWorkArea(WindowRect rect, int screenW, int workBottom) {
        return clampBoundsToWorkArea(rect, screenW, workBottom, 0);
    }

    public static WindowRect clampBoundsToWorkArea(WindowRect rect, int screenW, int workBottom, int minTop) {
        int sw = Math.max(480, screenW);
        int wb = Math.max(360, workBottom);
        int safeMinTop = Math.max(0, Math.min(minTop, Math.max(0, wb - 280)));
        if (rect == null || rect.isEmpty()) {
            return new WindowRect(0, safeMinTop, sw, wb);
        }
        int minW = Math.min(280, sw);
        int minH = Math.min(280, Math.max(160, wb - safeMinTop));
        int w = Math.max(minW, Math.min(sw, rect.width()));
        int h = Math.max(minH, Math.min(wb - safeMinTop, rect.height()));
        int left = Math.max(0, Math.min(sw - w, rect.left));
        int top = Math.max(safeMinTop, Math.min(wb - h, rect.top));
        return new WindowRect(left, top, left + w, top + h);
    }

    // ---- Effective launch mode & per-app preferences ----

    public String resolveLaunchMode(String pkg, int requestedIntMode) {
        if (requestedIntMode == INT_MODE_FULLSCREEN) return MODE_FULLSCREEN;
        if (requestedIntMode == INT_MODE_ADAPTIVE_SINGLE) return MODE_ADAPTIVE_SINGLE;
        if (requestedIntMode == INT_MODE_FREEFORM) return MODE_FREEFORM;
        synchronized (lock) {
            if (pkg != null && !pkg.isEmpty()) {
                AppWindowPref pref = appPrefs.get(pkg);
                if (pref != null && !pref.mode.isEmpty()) {
                    if (MODE_FULLSCREEN.equals(pref.mode)) return MODE_FULLSCREEN;
                    if (MODE_ADAPTIVE_SINGLE.equals(pref.mode)) return MODE_ADAPTIVE_SINGLE;
                    if (MODE_FREEFORM.equals(pref.mode)) return MODE_FREEFORM;
                }
            }
            return defaultLaunchMode;
        }
    }

    public AppWindowPref getAppPref(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        synchronized (lock) {
            return appPrefs.get(pkg);
        }
    }

    public void rememberAppPref(String pkg, String mode, int widthPct, int widthDp, WindowRect freeformBounds) {
        if (pkg == null || pkg.trim().isEmpty()) return;
        String cleanPkg = pkg.trim();
        synchronized (lock) {
            AppWindowPref prev = appPrefs.get(cleanPkg);
            String nextMode = (mode != null && !mode.isEmpty())
                    ? mode : (prev != null ? prev.mode : "");
            int nextPct = (widthPct >= MIN_WIDTH_PCT && widthPct <= MAX_WIDTH_PCT)
                    ? widthPct : (prev != null ? prev.widthPct : 0);
            int nextDp = (widthDp >= MIN_SINGLE_WIDTH_DP && widthDp <= MAX_SINGLE_WIDTH_DP)
                    ? widthDp : (prev != null ? prev.widthDp : 0);
            if (MODE_ADAPTIVE_SINGLE.equals(nextMode) && widthPct >= MIN_WIDTH_PCT) {
                // Explicit percentage overrides previously remembered dp for this app
                nextDp = 0;
            }
            WindowRect nextFf = (freeformBounds != null && !freeformBounds.isEmpty())
                    ? freeformBounds : (prev != null ? prev.freeformBounds : null);
            if (!appPrefs.containsKey(cleanPkg) && appPrefs.size() >= MAX_SAVED_APP_PREFS) {
                String oldest = appPrefs.keySet().iterator().next();
                appPrefs.remove(oldest);
            }
            appPrefs.put(cleanPkg, new AppWindowPref(cleanPkg, nextMode, nextPct, nextDp, nextFf));
            savePrefsLocked();
        }
    }

    // ---- Managed Window Tracking ----

    public ManagedWindow putManagedWindow(
            int taskId, String pkg, String mode, WindowRect bounds, boolean focused, int slotIndex) {
        return putManagedWindow(taskId, pkg, mode, null, bounds, focused, slotIndex);
    }

    public ManagedWindow putManagedWindow(
            int taskId, String pkg, String mode, String presentation, WindowRect bounds, boolean focused, int slotIndex) {
        if (taskId <= 0) return null;
        synchronized (lock) {
            if (focused) {
                for (ManagedWindow mw : managedWindows.values()) {
                    mw.focused = false;
                }
            }
            if (MODE_ADAPTIVE_SINGLE.equals(mode) || MODE_FULLSCREEN.equals(mode)) {
                for (ManagedWindow existing : managedWindows.values()) {
                    if (existing.taskId != taskId && !MODE_FREEFORM.equals(existing.mode)) {
                        existing.focused = false;
                        existing.minimized = true;
                    }
                }
            }
            ManagedWindow mw = managedWindows.remove(taskId);
            String resolvedMode = (mode != null && !mode.isEmpty()) ? mode : (mw != null ? mw.mode : MODE_FREEFORM);
            String resolvedPres = (presentation != null && !presentation.isEmpty())
                    ? presentation
                    : (MODE_FULLSCREEN.equals(resolvedMode) ? PRES_IMMERSIVE : PRES_FLOATING);
            if (mw == null) {
                mw = new ManagedWindow(taskId, pkg, resolvedMode, resolvedPres, bounds, null, focused, false, slotIndex, System.currentTimeMillis());
            } else {
                // Save prior floating bounds before transitioning into maximized/snapped/immersive.
                if (PRES_FLOATING.equals(mw.presentation) && !PRES_FLOATING.equals(resolvedPres)
                        && mw.bounds != null && !mw.bounds.isEmpty()) {
                    mw.restoreBounds = mw.bounds;
                }
                mw.mode = resolvedMode;
                mw.presentation = resolvedPres;
                if (bounds != null && !bounds.isEmpty()) mw.bounds = bounds;
                mw.focused = focused;
                mw.minimized = false;
                mw.slotIndex = slotIndex;
                mw.updatedAtMs = System.currentTimeMillis();
            }
            managedWindows.put(taskId, mw);
            if (MODE_FREEFORM.equals(mw.mode)) {
                enforceFreeformWindowCapLocked(taskId);
            }
            bumpSeqLocked();
            return mw.copy();
        }
    }

    /**
     * Resolves the next target bounds when toggling Maximize <-> Restore on {@code taskId},
     * persisting prior floating geometry so restore always returns to the exact user frame.
     */
    public WindowRect resolveToggleMaximizeTarget(int taskId, String pkg, int screenW, int screenH, int dpi) {
        WindowRect maxRect = computeSnapBounds(SNAP_MAXIMIZE_WORKAREA, screenW, screenH, dpi, isTaskbarAutoHide());
        synchronized (lock) {
            ManagedWindow mw = managedWindows.get(taskId);
            boolean currentlyMax = mw != null && (PRES_MAXIMIZED.equals(mw.presentation)
                    || approxEquals(mw.bounds, maxRect, Math.max(20, DisplayGeometryUtil.dpToPx(14, dpi))));
            if (currentlyMax) {
                WindowRect restore = (mw != null) ? mw.restoreBounds : null;
                if (restore != null && !restore.isEmpty()) {
                    int workBottom = Math.max(360, screenH - computeActiveTaskbarInsetPx(screenH, dpi));
                    int minTop = DisplayGeometryUtil.dpToPx(
                            DisplayGeometryUtil.WORKAREA_TOP_INSET_DP + DisplayGeometryUtil.WINDOW_HEADER_HEIGHT_DP, dpi);
                    return clampBoundsToWorkArea(restore, screenW, workBottom, minTop);
                }
                return computeNextFreeformBounds(screenW, screenH, dpi, computeActiveTaskbarInsetPx(screenH, dpi), pkg);
            }
            if (mw != null && mw.bounds != null && !mw.bounds.isEmpty() && PRES_FLOATING.equals(mw.presentation)) {
                mw.restoreBounds = mw.bounds;
            }
            return maxRect;
        }
    }

    private void enforceFreeformWindowCapLocked(int keepTaskId) {
        int visibleCount = 0;
        for (ManagedWindow w : managedWindows.values()) {
            if (!w.minimized && MODE_FREEFORM.equals(w.mode)) visibleCount++;
        }
        if (visibleCount <= MAX_VISIBLE_FREEFORM_WINDOWS) return;
        for (ManagedWindow w : managedWindows.values()) {
            if (visibleCount <= MAX_VISIBLE_FREEFORM_WINDOWS) break;
            if (w.taskId != keepTaskId && !w.minimized && MODE_FREEFORM.equals(w.mode)) {
                w.minimized = true;
                w.focused = false;
                visibleCount--;
            }
        }
    }

    public ManagedWindow getManagedWindow(int taskId) {
        synchronized (lock) {
            ManagedWindow mw = managedWindows.get(taskId);
            return (mw != null) ? mw.copy() : null;
        }
    }

    public void setFocusedTask(int taskId) {
        synchronized (lock) {
            ManagedWindow target = managedWindows.remove(taskId);
            boolean changed = false;
            for (ManagedWindow mw : managedWindows.values()) {
                if (mw.focused) {
                    mw.focused = false;
                    changed = true;
                }
            }
            if (target != null) {
                if (!target.focused || target.minimized) changed = true;
                target.focused = true;
                target.minimized = false;
                target.updatedAtMs = System.currentTimeMillis();
                managedWindows.put(taskId, target);
                changed = true;
            }
            if (changed) bumpSeqLocked();
        }
    }

    public void setMinimizedTask(int taskId, boolean minimized) {
        synchronized (lock) {
            ManagedWindow mw = managedWindows.get(taskId);
            if (mw != null) {
                if (mw.minimized != minimized || (minimized && mw.focused)) {
                    mw.minimized = minimized;
                    if (minimized) mw.focused = false;
                    mw.updatedAtMs = System.currentTimeMillis();
                    bumpSeqLocked();
                }
            }
        }
    }

    public void minimizeAllSingleWindows() {
        synchronized (lock) {
            boolean changed = false;
            for (ManagedWindow mw : managedWindows.values()) {
                if (!MODE_FREEFORM.equals(mw.mode)) {
                    if (mw.focused || !mw.minimized) changed = true;
                    mw.focused = false;
                    mw.minimized = true;
                }
            }
            if (changed) bumpSeqLocked();
        }
    }

    public void minimizeAllWindows() {
        synchronized (lock) {
            boolean changed = false;
            for (ManagedWindow mw : managedWindows.values()) {
                if (mw.focused || !mw.minimized) changed = true;
                mw.focused = false;
                mw.minimized = true;
            }
            if (changed) bumpSeqLocked();
        }
    }

    public ManagedWindow removeManagedWindow(int taskId) {
        synchronized (lock) {
            ManagedWindow removed = managedWindows.remove(taskId);
            if (isTilingActiveLocked()) {
                for (int i = 0; i < tilingSlotTaskIds.length; i++) {
                    if (tilingSlotTaskIds[i] == taskId) {
                        tilingSlotTaskIds[i] = -1;
                        tilingSlotPkgs[i] = "";
                    }
                }
                if (countAssignedTilingSlotsLocked() == 0) {
                    clearTilingStateLocked();
                }
            }
            if (removed != null) bumpSeqLocked();
            return removed;
        }
    }

    public void removeManagedWindowByPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (lock) {
            Iterator<Map.Entry<Integer, ManagedWindow>> it = managedWindows.entrySet().iterator();
            while (it.hasNext()) {
                if (pkg.equals(it.next().getValue().pkg)) {
                    it.remove();
                }
            }
            if (isTilingActiveLocked()) {
                for (int i = 0; i < tilingSlotPkgs.length; i++) {
                    if (pkg.equals(tilingSlotPkgs[i])) {
                        tilingSlotPkgs[i] = "";
                        tilingSlotTaskIds[i] = -1;
                    }
                }
                if (countAssignedTilingSlotsLocked() == 0) {
                    clearTilingStateLocked();
                }
            }
        }
    }

    public void clearAllManagedWindows() {
        synchronized (lock) {
            managedWindows.clear();
            clearTilingStateLocked();
        }
    }

    /**
     * Prunes managed windows whose taskIds are no longer alive on the virtual display.
     */
    public void pruneDeadTasks(Set<Integer> liveTaskIdsOnDisplay) {
        pruneDeadTasks(liveTaskIdsOnDisplay, 0L);
    }

    /**
     * Prunes managed windows whose taskIds are not on the virtual display, protecting entries
     * updated within {@code launchGraceMs} so cold/migrating tasks are not evicted mid-launch.
     */
    public void pruneDeadTasks(Set<Integer> liveTaskIdsOnDisplay, long launchGraceMs) {
        if (liveTaskIdsOnDisplay == null) return;
        long nowMs = (launchGraceMs > 0L) ? System.currentTimeMillis() : 0L;
        synchronized (lock) {
            Iterator<Map.Entry<Integer, ManagedWindow>> it = managedWindows.entrySet().iterator();
            while (it.hasNext()) {
                ManagedWindow mw = it.next().getValue();
                if (!liveTaskIdsOnDisplay.contains(mw.taskId)) {
                    if (launchGraceMs > 0L && (nowMs - mw.updatedAtMs) < launchGraceMs) {
                        continue;
                    }
                    it.remove();
                }
            }
        }
    }

    /**
     * Returns true when this task is actively managed in MULTI_WINDOW (6) mode
     * (ADAPTIVE_SINGLE, FREEFORM, or TILING) and MUST be exempted from
     * enforceRunningExtTasksFullscreen pulling it back to FULLSCREEN (1).
     */
    public boolean isTaskManagedInMultiWindow(int taskId, String pkg) {
        synchronized (lock) {
            ManagedWindow mw = managedWindows.get(taskId);
            if (mw != null && !MODE_FULLSCREEN.equals(mw.mode)) {
                return true;
            }
            if (isTilingActiveLocked()) {
                for (int i = 0; i < tilingSlotTaskIds.length; i++) {
                    if (taskId > 0 && tilingSlotTaskIds[i] == taskId) return true;
                    if (pkg != null && !pkg.isEmpty() && pkg.equals(tilingSlotPkgs[i])) return true;
                }
            }
            return false;
        }
    }

    /**
     * Returns true when an untracked non-launcher task appearing on the virtual display
     * should be auto-adopted into multi-window bounds (FREEFORM or ADAPTIVE_SINGLE).
     */
    public boolean shouldAutoApplyAdaptiveSingle(int taskId, String pkg) {
        if (taskId <= 0 || pkg == null || pkg.isEmpty()) return false;
        synchronized (lock) {
            if (isTilingActiveLocked()) return false;
            ManagedWindow existing = managedWindows.get(taskId);
            if (existing != null) {
                return false; // Already tracked (explicit FULLSCREEN, FREEFORM, or ADAPTIVE_SINGLE)
            }
            AppWindowPref pref = appPrefs.get(pkg);
            if (pref != null && MODE_FULLSCREEN.equals(pref.mode)) {
                return false;
            }
            if (pref != null && (MODE_FREEFORM.equals(pref.mode) || MODE_ADAPTIVE_SINGLE.equals(pref.mode))) {
                return true;
            }
            if (MODE_FULLSCREEN.equals(defaultLaunchMode) || !adaptiveSingleEnabled) {
                return false;
            }
            if (MODE_ADAPTIVE_SINGLE.equals(defaultLaunchMode)) {
                for (ManagedWindow mw : managedWindows.values()) {
                    if (!mw.minimized && MODE_FREEFORM.equals(mw.mode)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }

    // ---- Tiling State ----

    /**
     * Immutable snapshot of the 2-slot split view derived from active {@code SPLIT_2_H}/{@code SPLIT_2_V}
     * tiling state, guaranteeing atomic non-torn reads for Binder codes 15 & 35.
     */
    public static final class TwoSlotSplitSnapshot {
        public final boolean active;
        public final String topology;
        public final int ratioPct;
        public final String slot0Pkg;
        public final String slot1Pkg;
        public final int slot0TaskId;
        public final int slot1TaskId;

        TwoSlotSplitSnapshot(
                boolean active, String topology, int ratioPct,
                String slot0Pkg, String slot1Pkg, int slot0TaskId, int slot1TaskId) {
            this.active = active;
            this.topology = topology;
            this.ratioPct = ratioPct;
            this.slot0Pkg = slot0Pkg;
            this.slot1Pkg = slot1Pkg;
            this.slot0TaskId = slot0TaskId;
            this.slot1TaskId = slot1TaskId;
        }

        public int emptySlotIndex() {
            if (!active) return -1;
            boolean has0 = slot0Pkg != null && !slot0Pkg.isEmpty();
            boolean has1 = slot1Pkg != null && !slot1Pkg.isEmpty();
            if (has0 && !has1) return 1;
            if (!has0 && has1) return 0;
            return -1;
        }
    }

    public TwoSlotSplitSnapshot getTwoSlotSplitSnapshot(int fallbackRatioPct) {
        int clampedFallback = Math.max(25, Math.min(75, fallbackRatioPct));
        synchronized (lock) {
            boolean twoSlot = TilingLayoutEngine.TOPOLOGY_SPLIT_2_H.equals(tilingTopology)
                    || TilingLayoutEngine.TOPOLOGY_SPLIT_2_V.equals(tilingTopology);
            if (!twoSlot) {
                return new TwoSlotSplitSnapshot(
                        false, TilingLayoutEngine.TOPOLOGY_NONE, clampedFallback, "", "", -1, -1);
            }
            int ratio = (tilingRatios != null && tilingRatios.length > 0)
                    ? Math.max(25, Math.min(75, tilingRatios[0]))
                    : clampedFallback;
            return new TwoSlotSplitSnapshot(
                    true, tilingTopology, ratio,
                    tilingSlotPkgs[0] != null ? tilingSlotPkgs[0] : "",
                    tilingSlotPkgs[1] != null ? tilingSlotPkgs[1] : "",
                    tilingSlotTaskIds[0], tilingSlotTaskIds[1]);
        }
    }

    public boolean isPkgInActiveTiling(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        synchronized (lock) {
            if (!isTilingActiveLocked()) return false;
            for (String p : tilingSlotPkgs) {
                if (pkg.equals(p)) return true;
            }
            return false;
        }
    }

    public boolean isTilingActive() {
        synchronized (lock) {
            return isTilingActiveLocked();
        }
    }

    private boolean isTilingActiveLocked() {
        return TilingLayoutEngine.isTilingTopology(tilingTopology);
    }

    private int countAssignedTilingSlotsLocked() {
        int c = 0;
        for (int i = 0; i < tilingSlotPkgs.length; i++) {
            if ((tilingSlotPkgs[i] != null && !tilingSlotPkgs[i].isEmpty()) || tilingSlotTaskIds[i] > 0) {
                c++;
            }
        }
        return c;
    }

    /** Immutable, lock-consistent snapshot of active tiling topology, slots, and ratios. */
    public static final class TilingSnapshot {
        public final boolean active;
        public final String topology;
        public final List<String> slots;
        public final List<Integer> ratios;

        TilingSnapshot(boolean active, String topology, List<String> slots, List<Integer> ratios) {
            this.active = active;
            this.topology = topology;
            this.slots = slots;
            this.ratios = ratios;
        }
    }

    public TilingSnapshot getTilingSnapshot() {
        synchronized (lock) {
            if (!isTilingActiveLocked()) {
                return new TilingSnapshot(
                        false, TilingLayoutEngine.TOPOLOGY_NONE,
                        Collections.emptyList(), Collections.emptyList());
            }
            int slotCount = Math.min(tilingSlotPkgs.length, TilingLayoutEngine.slotCountForTopology(tilingTopology));
            List<String> slots = new ArrayList<>(slotCount);
            for (int i = 0; i < slotCount; i++) {
                slots.add(tilingSlotPkgs[i] != null ? tilingSlotPkgs[i] : "");
            }
            List<Integer> ratios = new ArrayList<>(tilingRatios.length);
            for (int r : tilingRatios) {
                ratios.add(r);
            }
            return new TilingSnapshot(
                    true, tilingTopology,
                    Collections.unmodifiableList(slots),
                    Collections.unmodifiableList(ratios));
        }
    }

    public String getTilingTopology() {
        synchronized (lock) {
            return tilingTopology;
        }
    }

    public int[] getTilingRatios() {
        synchronized (lock) {
            return tilingRatios.clone();
        }
    }

    public String[] getTilingSlotPkgs() {
        synchronized (lock) {
            return tilingSlotPkgs.clone();
        }
    }

    public int[] getTilingSlotTaskIds() {
        synchronized (lock) {
            return tilingSlotTaskIds.clone();
        }
    }

    public void setTilingState(String topology, List<String> pkgs, int[] taskIds, int[] ratios, WindowRect[] slots) {
        String norm = TilingLayoutEngine.normalizeTopology(topology);
        synchronized (lock) {
            if (TilingLayoutEngine.TOPOLOGY_NONE.equals(norm)) {
                clearTilingStateLocked();
                return;
            }
            // Remove previous non-tiling single windows so managed list reflects active tiling cleanly
            managedWindows.clear();
            tilingTopology = norm;
            tilingRatios = (ratios != null && ratios.length > 0)
                    ? ratios.clone()
                    : TilingLayoutEngine.defaultRatiosForTopology(norm);
            Arrays.fill(tilingSlotPkgs, "");
            Arrays.fill(tilingSlotTaskIds, -1);

            int slotCount = Math.min(4, TilingLayoutEngine.slotCountForTopology(norm));
            for (int i = 0; i < slotCount; i++) {
                String p = (pkgs != null && i < pkgs.size() && pkgs.get(i) != null) ? pkgs.get(i).trim() : "";
                int tid = (taskIds != null && i < taskIds.length) ? taskIds[i] : -1;
                tilingSlotPkgs[i] = p;
                tilingSlotTaskIds[i] = tid;
                if (tid > 0 && slots != null && i < slots.length && slots[i] != null) {
                    managedWindows.put(tid, new ManagedWindow(tid, p, MODE_TILING, slots[i], i == 0, false, i));
                }
            }
        }
    }

    public void updateTilingSlotTaskId(int slotIdx, int taskId, String pkg, WindowRect bounds) {
        if (slotIdx < 0 || slotIdx >= 4) return;
        synchronized (lock) {
            if (!isTilingActiveLocked()) return;
            tilingSlotTaskIds[slotIdx] = taskId;
            if (pkg != null && !pkg.isEmpty()) tilingSlotPkgs[slotIdx] = pkg;
            if (taskId > 0 && bounds != null && !bounds.isEmpty()) {
                managedWindows.put(taskId, new ManagedWindow(
                        taskId, tilingSlotPkgs[slotIdx], MODE_TILING, bounds, slotIdx == 0, false, slotIdx));
            }
        }
    }

    public void clearTilingState() {
        synchronized (lock) {
            clearTilingStateLocked();
        }
    }

    private void clearTilingStateLocked() {
        tilingTopology = TilingLayoutEngine.TOPOLOGY_NONE;
        tilingRatios = new int[]{50, 50};
        Arrays.fill(tilingSlotPkgs, "");
        Arrays.fill(tilingSlotTaskIds, -1);
        Iterator<Map.Entry<Integer, ManagedWindow>> it = managedWindows.entrySet().iterator();
        while (it.hasNext()) {
            if (MODE_TILING.equals(it.next().getValue().mode)) {
                it.remove();
            }
        }
    }

    // ---- Layout Mode & Binder Code 50 JSON Snapshot ----

    public String resolveActiveLayoutMode() {
        synchronized (lock) {
            if (isTilingActiveLocked()) {
                return tilingTopology;
            }
            ManagedWindow focusedWin = null;
            boolean hasVisibleFreeform = false;
            boolean hasVisibleAdaptive = false;
            for (ManagedWindow mw : managedWindows.values()) {
                if (mw.minimized) continue;
                if (mw.focused) focusedWin = mw;
                if (MODE_FREEFORM.equals(mw.mode)) hasVisibleFreeform = true;
                else if (MODE_ADAPTIVE_SINGLE.equals(mw.mode)) hasVisibleAdaptive = true;
            }
            if (hasVisibleFreeform) return MODE_FREEFORM;
            if (focusedWin != null) {
                if (MODE_FULLSCREEN.equals(focusedWin.mode)) return MODE_FULLSCREEN;
                if (MODE_ADAPTIVE_SINGLE.equals(focusedWin.mode)) return MODE_ADAPTIVE_SINGLE;
            }
            if (hasVisibleAdaptive) return MODE_ADAPTIVE_SINGLE;
            return defaultLaunchMode;
        }
    }

    public List<ManagedWindow> getManagedWindowsSnapshot() {
        synchronized (lock) {
            List<ManagedWindow> list = new ArrayList<>(managedWindows.size());
            for (ManagedWindow mw : managedWindows.values()) {
                list.add(mw.copy());
            }
            return Collections.unmodifiableList(list);
        }
    }

    /**
     * Serializes the exact JSON payload required by Binder code 50 (getManagedWindows).
     */
    public String toManagedWindowsJson() {
        synchronized (lock) {
            SimpleJson.ArrayBuilder ratiosArr = new SimpleJson.ArrayBuilder();
            for (int r : tilingRatios) {
                ratiosArr.add(r);
            }
            SimpleJson.ArrayBuilder winsArr = new SimpleJson.ArrayBuilder();
            for (ManagedWindow mw : managedWindows.values()) {
                WindowRect b = (mw.bounds != null) ? mw.bounds : new WindowRect(0, 0, 0, 0);
                SimpleJson.ObjectBuilder wb = new SimpleJson.ObjectBuilder()
                        .put("taskId", mw.taskId)
                        .put("pkg", mw.pkg)
                        .put("mode", mw.mode)
                        .put("presentation", mw.presentation != null ? mw.presentation : PRES_FLOATING)
                        .put("left", b.left)
                        .put("top", b.top)
                        .put("right", b.right)
                        .put("bottom", b.bottom)
                        .put("focused", mw.focused)
                        .put("minimized", mw.minimized);
                if (mw.restoreBounds != null && !mw.restoreBounds.isEmpty()) {
                    wb.put("restoreLeft", mw.restoreBounds.left)
                      .put("restoreTop", mw.restoreBounds.top)
                      .put("restoreRight", mw.restoreBounds.right)
                      .put("restoreBottom", mw.restoreBounds.bottom);
                }
                winsArr.addRaw(wb.build());
            }
            return new SimpleJson.ObjectBuilder()
                    .put("stateSeq", stateSeq)
                    .put("taskbarAutoHide", taskbarAutoHide)
                    .put("layoutMode", resolveActiveLayoutMode())
                    .put("adaptiveSingleEnabled", adaptiveSingleEnabled)
                    .put("defaultSingleWidthDp", defaultSingleWidthDp)
                    .put("tilingTopology", tilingTopology)
                    .putRaw("ratios", ratiosArr.build())
                    .putRaw("windows", winsArr.build())
                    .build();
        }
    }

    // ---- Atomic Disk Persistence (/data/local/tmp/opaldesk_win_prefs.conf) ----

    private void loadPrefs() {
        if (prefsFile == null || !prefsFile.isFile()) return;
        synchronized (lock) {
            appPrefs.clear();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(prefsFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.startsWith("GLOBAL\t")) {
                        String[] p = t.split("\t", -1);
                        if (p.length >= 3) {
                            adaptiveSingleEnabled = !"0".equals(p[1].trim());
                            try {
                                defaultSingleWidthDp = clampWidthDp(Integer.parseInt(p[2].trim()));
                            } catch (NumberFormatException nfe) {
                                System.err.println("OPALDESK_WIN_PREFS_BAD_WIDTH raw=" + p[2].trim());
                            }
                            if (p.length >= 4 && !p[3].trim().isEmpty()) {
                                defaultLaunchMode = normalizeLaunchMode(p[3].trim());
                            } else {
                                defaultLaunchMode = adaptiveSingleEnabled ? MODE_FREEFORM : MODE_FULLSCREEN;
                            }
                            if (p.length >= 5) {
                                taskbarAutoHide = "1".equals(p[4].trim());
                            }
                        }
                        continue;
                    }
                    if (t.startsWith("#")) continue;
                    String[] p = t.split("\t", -1);
                    if (p.length < 4) continue;
                    String pkg = p[0].trim();
                    if (pkg.isEmpty()) continue;
                    String mode = p[1].trim();
                    int wPct = parseIntSafe(p[2], 0);
                    int wDp = parseIntSafe(p[3], 0);
                    WindowRect ff = null;
                    if (p.length >= 8) {
                        int l = parseIntSafe(p[4], 0);
                        int top = parseIntSafe(p[5], 0);
                        int r = parseIntSafe(p[6], 0);
                        int b = parseIntSafe(p[7], 0);
                        if (r > l && b > top) {
                            ff = new WindowRect(l, top, r, b);
                        }
                    }
                    if (appPrefs.size() < MAX_SAVED_APP_PREFS) {
                        appPrefs.put(pkg, new AppWindowPref(pkg, mode, wPct, wDp, ff));
                    }
                }
            } catch (IOException | RuntimeException e) {
                System.err.println("OPALDESK_WIN_PREFS_LOAD_ERR path=" + prefsFile.getAbsolutePath()
                        + ": " + e.getMessage());
            }
        }
    }

    private void savePrefsLocked() {
        if (prefsFile == null) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append("GLOBAL\t").append(adaptiveSingleEnabled ? 1 : 0)
          .append('\t').append(defaultSingleWidthDp)
          .append('\t').append(defaultLaunchMode)
          .append('\t').append(taskbarAutoHide ? 1 : 0).append('\n');
        for (AppWindowPref p : appPrefs.values()) {
            sb.append(p.pkg).append('\t')
              .append(p.mode).append('\t')
              .append(p.widthPct).append('\t')
              .append(p.widthDp);
            if (p.freeformBounds != null) {
                sb.append('\t').append(p.freeformBounds.left)
                  .append('\t').append(p.freeformBounds.top)
                  .append('\t').append(p.freeformBounds.right)
                  .append('\t').append(p.freeformBounds.bottom);
            }
            sb.append('\n');
        }
        try {
            AtomicFiles.writeAtomicUtf8(prefsFile, sb.toString(), AtomicFiles.MODE_OWNER_RW);
        } catch (IOException | RuntimeException e) {
            System.err.println("OPALDESK_WIN_PREFS_SAVE_ERR path=" + prefsFile.getAbsolutePath()
                    + ": " + e.getMessage());
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }
}
