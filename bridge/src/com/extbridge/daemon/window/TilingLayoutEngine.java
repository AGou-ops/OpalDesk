package com.extbridge.daemon.window;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java multi-slot tiling layout engine for ExtBridge v4.1.
 * Computes exact non-overlapping slot rectangles for 5 topologies with configurable
 * multi-axis ratios, 8px safe inter-tile gap, and bottom Dock inset avoidance.
 */
public final class TilingLayoutEngine {
    public static final String TOPOLOGY_NONE = "NONE";
    public static final String TOPOLOGY_SPLIT_2_H = "SPLIT_2_H";
    public static final String TOPOLOGY_SPLIT_2_V = "SPLIT_2_V";
    public static final String TOPOLOGY_SPLIT_3_MASTER = "SPLIT_3_MASTER";
    public static final String TOPOLOGY_SPLIT_3_COLS = "SPLIT_3_COLS";
    public static final String TOPOLOGY_GRID_2X2 = "GRID_2X2";

    public static final int DEFAULT_GAP_PX = 8;
    public static final int MIN_SLOT_SIZE_PX = 200;
    public static final float MIN_AXIS_RATIO = 0.20f;
    public static final float MAX_AXIS_RATIO = 0.80f;
    public static final float MIN_COL3_RATIO = 0.18f;

    private TilingLayoutEngine() {}

    /**
     * Pure-Java immutable rectangle mirroring android.graphics.Rect without requiring
     * Android framework runtime classes during JVM unit tests.
     */
    public static final class WindowRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public WindowRect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = Math.max(left, right);
            this.bottom = Math.max(top, bottom);
        }

        public int width() {
            return Math.max(0, right - left);
        }

        public int height() {
            return Math.max(0, bottom - top);
        }

        public int centerX() {
            return left + width() / 2;
        }

        public boolean isEmpty() {
            return left >= right || top >= bottom;
        }

        public boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        public boolean intersects(WindowRect other) {
            if (other == null || isEmpty() || other.isEmpty()) return false;
            return this.left < other.right && other.left < this.right
                    && this.top < other.bottom && other.top < this.bottom;
        }

        public Rect toAndroidRect() {
            return new Rect(left, top, right, bottom);
        }

        public static WindowRect fromAndroidRect(Rect r) {
            if (r == null) return new WindowRect(0, 0, 0, 0);
            return new WindowRect(r.left, r.top, r.right, r.bottom);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WindowRect)) return false;
            WindowRect that = (WindowRect) o;
            return left == that.left && top == that.top
                    && right == that.right && bottom == that.bottom;
        }

        @Override
        public int hashCode() {
            int r = left;
            r = 31 * r + top;
            r = 31 * r + right;
            r = 31 * r + bottom;
            return r;
        }

        @Override
        public String toString() {
            return "WindowRect(" + left + ", " + top + " - " + right + ", " + bottom
                    + " [" + width() + "x" + height() + "])";
        }
    }

    public static String normalizeTopology(String raw) {
        if (raw == null) return TOPOLOGY_NONE;
        String t = raw.trim().toUpperCase(Locale.US);
        switch (t) {
            case TOPOLOGY_SPLIT_2_H:
            case TOPOLOGY_SPLIT_2_V:
            case TOPOLOGY_SPLIT_3_MASTER:
            case TOPOLOGY_SPLIT_3_COLS:
            case TOPOLOGY_GRID_2X2:
                return t;
            case "EXIT":
            case "NONE":
            case "":
            default:
                return TOPOLOGY_NONE;
        }
    }

    public static boolean isTilingTopology(String raw) {
        return !TOPOLOGY_NONE.equals(normalizeTopology(raw));
    }

    public static int slotCountForTopology(String topology) {
        switch (normalizeTopology(topology)) {
            case TOPOLOGY_SPLIT_2_H:
            case TOPOLOGY_SPLIT_2_V:
                return 2;
            case TOPOLOGY_SPLIT_3_MASTER:
            case TOPOLOGY_SPLIT_3_COLS:
                return 3;
            case TOPOLOGY_GRID_2X2:
                return 4;
            default:
                return 0;
        }
    }

    public static int[] defaultRatiosForTopology(String topology) {
        switch (normalizeTopology(topology)) {
            case TOPOLOGY_SPLIT_3_COLS:
                return new int[]{33, 34, 33};
            case TOPOLOGY_SPLIT_2_H:
            case TOPOLOGY_SPLIT_2_V:
            case TOPOLOGY_SPLIT_3_MASTER:
            case TOPOLOGY_GRID_2X2:
            default:
                return new int[]{50, 50};
        }
    }

    public static int computeDefaultDockInsetPx(int screenH, int dpi) {
        if (screenH <= 0) return 0;
        int effDpi = Math.max(120, Math.min(640, dpi > 0 ? dpi : 240));
        int dockPx = Math.round(72f * effDpi / 160f);
        int maxAllowed = Math.max(48, screenH / 7);
        return Math.max(48, Math.min(maxAllowed, Math.min(128, dockPx)));
    }

    public static int[] parseRatiosCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) return new int[0];
        String[] parts = csv.split("[,;\\s]+");
        List<Integer> vals = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            try {
                vals.add(Integer.parseInt(t));
            } catch (NumberFormatException ignored) {
                // Skip non-numeric CSV tokens.
            }
        }
        int[] out = new int[vals.size()];
        for (int i = 0; i < vals.size(); i++) out[i] = vals.get(i);
        return out;
    }

    public static List<String> parsePkgsCsv(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptyList();
        String[] parts = csv.split("[,;]+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static WindowRect[] computeSlots(
            String topology, int screenW, int screenH, int bottomDockInsetPx, int[] ratios) {
        return computeSlots(topology, screenW, screenH, bottomDockInsetPx, DEFAULT_GAP_PX, ratios);
    }

    public static WindowRect[] computeSlots(
            String topology,
            int screenW,
            int screenH,
            int bottomDockInsetPx,
            int gapPx,
            int[] ratios) {
        String norm = normalizeTopology(topology);
        if (TOPOLOGY_NONE.equals(norm)) {
            return new WindowRect[0];
        }
        int sw = Math.max(480, screenW);
        int sh = Math.max(480, screenH);
        int dock = Math.max(0, Math.min(sh / 3, bottomDockInsetPx));
        int gap = Math.max(0, Math.min(48, gapPx));
        int workBottom = Math.max(MIN_SLOT_SIZE_PX, sh - dock);

        switch (norm) {
            case TOPOLOGY_SPLIT_2_H:
                return computeSplit2H(sw, workBottom, gap, ratios);
            case TOPOLOGY_SPLIT_2_V:
                return computeSplit2V(sw, workBottom, gap, ratios);
            case TOPOLOGY_SPLIT_3_MASTER:
                return computeSplit3Master(sw, workBottom, gap, ratios);
            case TOPOLOGY_SPLIT_3_COLS:
                return computeSplit3Cols(sw, workBottom, gap, ratios);
            case TOPOLOGY_GRID_2X2:
                return computeGrid2x2(sw, workBottom, gap, ratios);
            default:
                return new WindowRect[0];
        }
    }

    private static WindowRect[] computeSplit2H(int sw, int workBottom, int gap, int[] ratios) {
        float leftFrac = resolveBinaryRatio(ratios, 0.50f);
        int availW = Math.max(2, sw - gap);
        int minSlotW = Math.min(MIN_SLOT_SIZE_PX, availW / 3);
        int w0 = clampInt(Math.round(availW * leftFrac), minSlotW, availW - minSlotW);
        return new WindowRect[]{
                new WindowRect(0, 0, w0, workBottom),
                new WindowRect(w0 + gap, 0, sw, workBottom)
        };
    }

    private static WindowRect[] computeSplit2V(int sw, int workBottom, int gap, int[] ratios) {
        float topFrac = resolveBinaryRatio(ratios, 0.50f);
        int availH = Math.max(2, workBottom - gap);
        int minSlotH = Math.min(MIN_SLOT_SIZE_PX, availH / 3);
        int h0 = clampInt(Math.round(availH * topFrac), minSlotH, availH - minSlotH);
        return new WindowRect[]{
                new WindowRect(0, 0, sw, h0),
                new WindowRect(0, h0 + gap, sw, workBottom)
        };
    }

    private static WindowRect[] computeSplit3Master(int sw, int workBottom, int gap, int[] ratios) {
        float masterX = 0.50f;
        float rightTopY = 0.50f;
        if (ratios != null && ratios.length == 1) {
            masterX = clampRatio(ratios[0] / 100f);
        } else if (ratios != null && ratios.length == 2) {
            masterX = clampRatio(ratios[0] / 100f);
            rightTopY = clampRatio(ratios[1] / 100f);
        } else if (ratios != null && ratios.length >= 3) {
            int r0 = Math.max(1, ratios[0]);
            int r1 = Math.max(1, ratios[1]);
            int r2 = Math.max(1, ratios[2]);
            masterX = clampRatio(r0 / (float) (r0 + r1 + r2));
            rightTopY = clampRatio(r1 / (float) (r1 + r2));
        }

        int availW = Math.max(2, sw - gap);
        int minW = Math.min(MIN_SLOT_SIZE_PX, availW / 3);
        int w0 = clampInt(Math.round(availW * masterX), minW, availW - minW);

        int availH = Math.max(2, workBottom - gap);
        int minH = Math.min(MIN_SLOT_SIZE_PX, availH / 3);
        int hTop = clampInt(Math.round(availH * rightTopY), minH, availH - minH);

        int rightLeft = w0 + gap;
        return new WindowRect[]{
                new WindowRect(0, 0, w0, workBottom),
                new WindowRect(rightLeft, 0, sw, hTop),
                new WindowRect(rightLeft, hTop + gap, sw, workBottom)
        };
    }

    private static WindowRect[] computeSplit3Cols(int sw, int workBottom, int gap, int[] ratios) {
        float[] fracs = new float[]{1f / 3f, 1f / 3f, 1f / 3f};
        if (ratios != null && ratios.length >= 3) {
            fracs = normalizeThreeColRatios(Math.max(1, ratios[0]), Math.max(1, ratios[1]), Math.max(1, ratios[2]));
        } else if (ratios != null && ratios.length == 2
                && ratios[0] > 0 && ratios[1] > 0 && ratios[0] + ratios[1] < 95) {
            fracs = normalizeThreeColRatios(ratios[0], ratios[1], 100 - ratios[0] - ratios[1]);
        }

        int availW = Math.max(3, sw - 2 * gap);
        int minColW = Math.min(MIN_SLOT_SIZE_PX, availW / 4);
        int w0 = clampInt(Math.round(availW * fracs[0]), minColW, availW - 2 * minColW);
        int w1 = clampInt(Math.round(availW * fracs[1]), minColW, availW - w0 - minColW);
        int x1 = w0 + gap;
        int x2 = x1 + w1 + gap;
        return new WindowRect[]{
                new WindowRect(0, 0, w0, workBottom),
                new WindowRect(x1, 0, x1 + w1, workBottom),
                new WindowRect(x2, 0, sw, workBottom)
        };
    }

    private static float[] normalizeThreeColRatios(int r0, int r1, int r2) {
        float sum = r0 + r1 + r2;
        float f0 = Math.max(MIN_COL3_RATIO, r0 / sum);
        float f1 = Math.max(MIN_COL3_RATIO, r1 / sum);
        float f2 = Math.max(MIN_COL3_RATIO, r2 / sum);
        float nSum = f0 + f1 + f2;
        f0 /= nSum;
        f1 /= nSum;
        return new float[]{f0, f1, 1f - f0 - f1};
    }

    private static WindowRect[] computeGrid2x2(int sw, int workBottom, int gap, int[] ratios) {
        float xFrac = 0.50f;
        float yFrac = 0.50f;
        if (ratios != null && ratios.length == 1) {
            xFrac = clampRatio(ratios[0] / 100f);
        } else if (ratios != null && ratios.length == 2) {
            xFrac = clampRatio(ratios[0] / 100f);
            yFrac = clampRatio(ratios[1] / 100f);
        } else if (ratios != null && ratios.length >= 4) {
            int x0 = Math.max(1, ratios[0]);
            int x1 = Math.max(1, ratios[1]);
            int y0 = Math.max(1, ratios[2]);
            int y1 = Math.max(1, ratios[3]);
            xFrac = clampRatio(x0 / (float) (x0 + x1));
            yFrac = clampRatio(y0 / (float) (y0 + y1));
        }

        int availW = Math.max(2, sw - gap);
        int minW = Math.min(MIN_SLOT_SIZE_PX, availW / 3);
        int wLeft = clampInt(Math.round(availW * xFrac), minW, availW - minW);

        int availH = Math.max(2, workBottom - gap);
        int minH = Math.min(MIN_SLOT_SIZE_PX, availH / 3);
        int hTop = clampInt(Math.round(availH * yFrac), minH, availH - minH);

        int xRight = wLeft + gap;
        int yBottom = hTop + gap;
        return new WindowRect[]{
                new WindowRect(0, 0, wLeft, hTop),
                new WindowRect(xRight, 0, sw, hTop),
                new WindowRect(0, yBottom, wLeft, workBottom),
                new WindowRect(xRight, yBottom, sw, workBottom)
        };
    }

    private static float resolveBinaryRatio(int[] ratios, float def) {
        if (ratios == null || ratios.length == 0) return def;
        if (ratios.length == 1) {
            return clampRatio(ratios[0] / 100f);
        }
        int a = Math.max(0, ratios[0]);
        int b = Math.max(0, ratios[1]);
        if (a + b <= 0) return def;
        return clampRatio(a / (float) (a + b));
    }

    private static float clampRatio(float r) {
        if (!Float.isFinite(r)) return 0.50f;
        return Math.max(MIN_AXIS_RATIO, Math.min(MAX_AXIS_RATIO, r));
    }

    private static int clampInt(int v, int lo, int hi) {
        if (hi < lo) return lo;
        return Math.max(lo, Math.min(hi, v));
    }
}
