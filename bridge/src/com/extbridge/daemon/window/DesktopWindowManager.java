package com.extbridge.daemon.window;

import com.extbridge.daemon.stream.SimpleJson;
import com.extbridge.daemon.window.TilingLayoutEngine.WindowRect;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
 * Pure-Java desktop window manager state & geometry engine for ExtBridge v4.1.
 * Manages:
 * - Adaptive single-app golden centered bounds (default enabled=true, defaultWidthDp=460)
 * - 5-tier width snapping presets + per-app preference persistence
 * - Freeform multi-window staggered cascade placement (managed via Android MULTI_WINDOW mode 6)
 * - Multi-slot tiling state (SPLIT_2_H / SPLIT_2_V / SPLIT_3_MASTER / SPLIT_3_COLS / GRID_2X2)
 * - Binder code 50 JSON serialization
 */
public final class DesktopWindowManager {
    public static final String DEFAULT_PREFS_PATH = "/data/local/tmp/extbridge_win_prefs.conf";

    public static final int DEFAULT_SINGLE_WIDTH_DP = 460;
    public static final int MIN_SINGLE_WIDTH_DP = 320;
    public static final int MAX_SINGLE_WIDTH_DP = 1200;
    public static final int MIN_WIDTH_PCT = 28;
    public static final int MAX_WIDTH_PCT = 100;
    public static final int MAX_FREEFORM_WINDOWS = 4;
    public static final int MAX_SAVED_APP_PREFS = 256;

    // 5-tier width snapping presets (% of display width)
    public static final int[] SNAP_WIDTH_PCTS = new int[]{32, 42, 55, 72, 100};
    public static final int DEFAULT_SNAP_TOLERANCE_PCT = 3;

    // Layout / window mode constants (aligned with ExtCast v1.1 & Binder IPC code 15/50/51/52)
    public static final String MODE_FULLSCREEN = "FULLSCREEN";
    public static final String MODE_ADAPTIVE_SINGLE = "ADAPTIVE_SINGLE";
    public static final String MODE_FREEFORM = "FREEFORM";
    public static final String MODE_TILING = "TILING";
    public static final String MODE_STANDALONE_APP = "STANDALONE_APP";

    // Binder code 51 & 52 integer modes
    public static final int INT_MODE_AUTO = 0;
    public static final int INT_MODE_FULLSCREEN = 1;
    public static final int INT_MODE_ADAPTIVE_SINGLE = 2;
    public static final int INT_MODE_FREEFORM = 3;

    public static final class ManagedWindow {
        public final int taskId;
        public final String pkg;
        public String mode;
        public WindowRect bounds;
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
            this.taskId = taskId;
            this.pkg = (pkg != null) ? pkg : "";
            this.mode = (mode != null && !mode.isEmpty()) ? mode : MODE_ADAPTIVE_SINGLE;
            this.bounds = (bounds != null) ? bounds : new WindowRect(0, 0, 0, 0);
            this.focused = focused;
            this.minimized = minimized;
            this.slotIndex = slotIndex;
            this.updatedAtMs = System.currentTimeMillis();
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

    public void setAdaptiveSingleConfig(boolean enabled, int widthDp) {
        synchronized (lock) {
            this.adaptiveSingleEnabled = enabled;
            if (widthDp > 0) {
                this.defaultSingleWidthDp = clampWidthDp(widthDp);
            }
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

    public static String intModeToString(int intMode) {
        switch (intMode) {
            case INT_MODE_FULLSCREEN:
                return MODE_FULLSCREEN;
            case INT_MODE_ADAPTIVE_SINGLE:
                return MODE_ADAPTIVE_SINGLE;
            case INT_MODE_FREEFORM:
                return MODE_FREEFORM;
            default:
                return "";
        }
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

        int topMargin = (dock > 0) ? Math.min(16, Math.max(8, Math.round(sh * 0.01f))) : 0;
        int bottomMargin = (dock > 0) ? Math.min(12, Math.max(6, Math.round(sh * 0.008f))) : 0;
        int top = topMargin;
        int bottom = Math.max(top + 320, workBottom - bottomMargin);
        int left = Math.max(0, (sw - clampedW) / 2);
        return new WindowRect(left, top, left + clampedW, bottom);
    }

    /**
     * Computes staggered cascading initial bounds for a new freeform window so multiple
     * windows do not stack identically on top of each other.
     */
    public WindowRect computeNextFreeformBounds(
            int screenW, int screenH, int dpi, int bottomDockPx, String pkg) {
        int sw = Math.max(480, screenW);
        int sh = Math.max(480, screenH);
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 240));
        int dock = Math.max(0, Math.min(sh / 3, bottomDockPx));
        int workBottom = Math.max(360, sh - dock);

        synchronized (lock) {
            AppWindowPref pref = (pkg != null && !pkg.isEmpty()) ? appPrefs.get(pkg) : null;
            if (pref != null && pref.freeformBounds != null) {
                WindowRect candidate = clampBoundsToWorkArea(pref.freeformBounds, sw, workBottom);
                return avoidExactOriginCollisionLocked(candidate, sw, workBottom);
            }

            int baseW = Math.round(500f * effDpi / 160f);
            int w = Math.max(400, Math.min(Math.round(sw * 0.52f), Math.max(Math.round(sw * 0.34f), baseW)));
            if (sw < 900) w = Math.max(360, Math.round(sw * 0.72f));
            w = Math.min(sw - 32, w);

            int h = Math.max(440, Math.min(workBottom - 40, Math.round(workBottom * 0.80f)));

            int visibleFreeformCount = 0;
            for (ManagedWindow mw : managedWindows.values()) {
                if (!mw.minimized && MODE_FREEFORM.equals(mw.mode)) {
                    visibleFreeformCount++;
                }
            }

            int stepIdx = visibleFreeformCount % 5;
            int stepX = Math.max(40, Math.min(96, (sw - w) / 5));
            int stepY = Math.max(32, Math.min(64, Math.max(32, (workBottom - h) / 4)));

            int baseLeft = Math.max(24, (sw - w) / 2 - stepX);
            int baseTop = Math.max(24, (workBottom - h) / 2 - stepY / 2);

            int left = baseLeft + stepIdx * stepX;
            int top = baseTop + stepIdx * stepY;
            if (left + w > sw - 16) {
                left = Math.max(16, 24 + (stepIdx * 36) % Math.max(40, sw - w - 32));
            }
            if (top + h > workBottom - 12) {
                top = Math.max(16, 24 + (stepIdx * 28) % Math.max(32, workBottom - h - 24));
            }
            WindowRect initial = clampBoundsToWorkArea(new WindowRect(left, top, left + w, top + h), sw, workBottom);
            return avoidExactOriginCollisionLocked(initial, sw, workBottom);
        }
    }

    private WindowRect avoidExactOriginCollisionLocked(WindowRect rect, int sw, int workBottom) {
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
            if (nt + h > workBottom - 12) nt = 24;
            cur = clampBoundsToWorkArea(new WindowRect(nl, nt, nl + w, nt + h), sw, workBottom);
        }
        return cur;
    }

    public static WindowRect clampBoundsToWorkArea(WindowRect rect, int screenW, int workBottom) {
        int sw = Math.max(480, screenW);
        int wb = Math.max(360, workBottom);
        if (rect == null || rect.isEmpty()) {
            return new WindowRect(0, 0, sw, wb);
        }
        int minW = Math.min(280, sw);
        int minH = Math.min(280, wb);
        int w = Math.max(minW, Math.min(sw, rect.width()));
        int h = Math.max(minH, Math.min(wb, rect.height()));
        int left = Math.max(0, Math.min(sw - w, rect.left));
        int top = Math.max(0, Math.min(wb - h, rect.top));
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
            return adaptiveSingleEnabled ? MODE_ADAPTIVE_SINGLE : MODE_FULLSCREEN;
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
        if (taskId <= 0) return null;
        synchronized (lock) {
            if (focused) {
                for (ManagedWindow mw : managedWindows.values()) {
                    mw.focused = false;
                }
            }
            // When focusing a single-app window (ADAPTIVE_SINGLE or FULLSCREEN), keep other
            // single-app entries tracked as minimized rather than deleting them so background
            // tasks in MULTI_WINDOW (6) remain exempted from fullscreen rescue yanking them to front.
            if (MODE_ADAPTIVE_SINGLE.equals(mode) || MODE_FULLSCREEN.equals(mode)) {
                for (ManagedWindow existing : managedWindows.values()) {
                    if (existing.taskId != taskId && !MODE_FREEFORM.equals(existing.mode)) {
                        existing.focused = false;
                        existing.minimized = true;
                    }
                }
            }
            ManagedWindow mw = managedWindows.remove(taskId);
            if (mw == null) {
                mw = new ManagedWindow(taskId, pkg, mode, bounds, focused, false, slotIndex);
            } else {
                mw.mode = (mode != null && !mode.isEmpty()) ? mode : mw.mode;
                if (bounds != null && !bounds.isEmpty()) mw.bounds = bounds;
                mw.focused = focused;
                mw.minimized = false;
                mw.slotIndex = slotIndex;
                mw.updatedAtMs = System.currentTimeMillis();
            }
            managedWindows.put(taskId, mw);
            // Bound total freeform windows softly by marking oldest non-focused if > MAX_FREEFORM_WINDOWS
            return mw;
        }
    }

    public ManagedWindow getManagedWindow(int taskId) {
        synchronized (lock) {
            return managedWindows.get(taskId);
        }
    }

    public ManagedWindow findManagedWindowByPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        synchronized (lock) {
            ManagedWindow found = null;
            for (ManagedWindow mw : managedWindows.values()) {
                if (pkg.equals(mw.pkg)) found = mw;
            }
            return found;
        }
    }

    public void setFocusedTask(int taskId) {
        synchronized (lock) {
            ManagedWindow target = managedWindows.remove(taskId);
            for (ManagedWindow mw : managedWindows.values()) {
                mw.focused = false;
            }
            if (target != null) {
                target.focused = true;
                target.minimized = false;
                target.updatedAtMs = System.currentTimeMillis();
                managedWindows.put(taskId, target);
            }
        }
    }

    public void setMinimizedTask(int taskId, boolean minimized) {
        synchronized (lock) {
            ManagedWindow mw = managedWindows.get(taskId);
            if (mw != null) {
                mw.minimized = minimized;
                if (minimized) mw.focused = false;
                mw.updatedAtMs = System.currentTimeMillis();
            }
        }
    }

    public void minimizeAllSingleWindows() {
        synchronized (lock) {
            for (ManagedWindow mw : managedWindows.values()) {
                if (!MODE_FREEFORM.equals(mw.mode)) {
                    mw.focused = false;
                    mw.minimized = true;
                }
            }
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
        if (liveTaskIdsOnDisplay == null) return;
        synchronized (lock) {
            Iterator<Map.Entry<Integer, ManagedWindow>> it = managedWindows.entrySet().iterator();
            while (it.hasNext()) {
                ManagedWindow mw = it.next().getValue();
                if (!liveTaskIdsOnDisplay.contains(mw.taskId)) {
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
     * should be auto-adopted into ADAPTIVE_SINGLE golden centered bounds.
     */
    public boolean shouldAutoApplyAdaptiveSingle(int taskId, String pkg) {
        if (taskId <= 0 || pkg == null || pkg.isEmpty()) return false;
        synchronized (lock) {
            if (!adaptiveSingleEnabled) return false;
            if (isTilingActiveLocked()) return false;
            ManagedWindow existing = managedWindows.get(taskId);
            if (existing != null) {
                return false; // Already tracked (explicit FULLSCREEN, FREEFORM, or ADAPTIVE_SINGLE)
            }
            for (ManagedWindow mw : managedWindows.values()) {
                if (!mw.minimized && MODE_FREEFORM.equals(mw.mode)) {
                    return false;
                }
            }
            AppWindowPref pref = appPrefs.get(pkg);
            if (pref != null && MODE_FULLSCREEN.equals(pref.mode)) {
                return false;
            }
            return true;
        }
    }

    // ---- Tiling State ----

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
            return adaptiveSingleEnabled ? MODE_ADAPTIVE_SINGLE : MODE_FULLSCREEN;
        }
    }

    public List<ManagedWindow> getManagedWindowsSnapshot() {
        synchronized (lock) {
            List<ManagedWindow> list = new ArrayList<>(managedWindows.size());
            for (ManagedWindow mw : managedWindows.values()) {
                list.add(new ManagedWindow(
                        mw.taskId, mw.pkg, mw.mode, mw.bounds, mw.focused, mw.minimized, mw.slotIndex));
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
                winsArr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("taskId", mw.taskId)
                        .put("pkg", mw.pkg)
                        .put("mode", mw.mode)
                        .put("left", b.left)
                        .put("top", b.top)
                        .put("right", b.right)
                        .put("bottom", b.bottom)
                        .put("focused", mw.focused)
                        .put("minimized", mw.minimized)
                        .build());
            }
            return new SimpleJson.ObjectBuilder()
                    .put("layoutMode", resolveActiveLayoutMode())
                    .put("adaptiveSingleEnabled", adaptiveSingleEnabled)
                    .put("defaultSingleWidthDp", defaultSingleWidthDp)
                    .put("tilingTopology", tilingTopology)
                    .putRaw("ratios", ratiosArr.build())
                    .putRaw("windows", winsArr.build())
                    .build();
        }
    }

    // ---- Atomic Disk Persistence (/data/local/tmp/extbridge_win_prefs.conf) ----

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
                            } catch (Throwable ignored) {}
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
            } catch (Throwable ignored) {}
        }
    }

    private void savePrefsLocked() {
        if (prefsFile == null) return;
        File parent = prefsFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(prefsFile.getAbsolutePath() + ".tmp");
        try {
            if (tmp.exists()) tmp.delete();
            StringBuilder sb = new StringBuilder(256);
            sb.append("GLOBAL\t").append(adaptiveSingleEnabled ? 1 : 0)
              .append('\t').append(defaultSingleWidthDp).append('\n');
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
            try (FileOutputStream fos = new FileOutputStream(tmp, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
            if (!tmp.renameTo(prefsFile)) {
                if (prefsFile.exists()) prefsFile.delete();
                tmp.renameTo(prefsFile);
            }
        } catch (Throwable ignored) {
            try { tmp.delete(); } catch (Throwable ignored2) {}
        }
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }
}
