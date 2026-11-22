package io.github.lengsukq.opaldesk.daemon;

import io.github.lengsukq.opaldesk.daemon.compositor.WindowProfile;
import io.github.lengsukq.opaldesk.daemon.ipc.BridgeProtocol;

/**
 * Pure Java 11 geometry math for the Universal Per-Window Compositor:
 * dimension alignment, DPI clamping, work-area constraints, cascade placement, and snap zones.
 */
public final class DisplayGeometryUtil {
    public static final int MIN_WINDOW_EDGE_PX = 260;
    public static final int MAX_WINDOW_EDGE_PX = 4096;
    public static final int MIN_LONG_EDGE = 640;
    public static final int MIN_SHORT_EDGE = 480;
    public static final int MAX_ENCODER_LONG_EDGE = 4096;
    public static final int MAX_ENCODER_SHORT_EDGE = 2160;
    public static final int MIN_DPI = 120;
    public static final int MAX_DPI = 560;
    public static final float MIN_FPS = 24.0f;
    public static final float MAX_FPS = 165.0f;

    public static final int TOP_BAR_HEIGHT_DP = 28;
    public static final int WINDOW_CHROME_HEADER_DP = 36;
    public static final int DOCK_RESERVED_BOTTOM_DP = 70;
    public static final int SNAP_GAP_DP = 8;

    private DisplayGeometryUtil() {}

    public static final class IntRect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public IntRect(int left, int top, int right, int bottom) {
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

        public boolean isEmpty() {
            return width() <= 0 || height() <= 0;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IntRect)) return false;
            IntRect r = (IntRect) o;
            return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
        }

        @Override
        public int hashCode() {
            int res = left;
            res = 31 * res + top;
            res = 31 * res + right;
            res = 31 * res + bottom;
            return res;
        }
    }

    public static int dpToPx(float dp, int dpi) {
        int effDpi = clampDpi(dpi);
        return Math.max(1, Math.round(dp * (effDpi / 160.0f)));
    }

    public static int alignEven(int value, int minVal, int maxVal) {
        int clamped = Math.max(minVal, Math.min(maxVal, value));
        return (clamped & ~1);
    }

    public static int clampDpi(int dpi) {
        if (dpi <= 0) return 220;
        return Math.max(MIN_DPI, Math.min(MAX_DPI, dpi));
    }

    public static int computeAutoDpi(int w, int h) {
        int minEdge = Math.min(Math.max(1, w), Math.max(1, h));
        return Math.max(120, Math.min(280, Math.round(minEdge * 160f / 1440f)));
    }

    public static int clampDpi(int dpi, int fallbackW, int fallbackH) {
        if (dpi < 80 || dpi > 640) {
            return computeAutoDpi(fallbackW, fallbackH);
        }
        return dpi;
    }

    public static int normalizeRotation(int rot) {
        return ((rot % 4) + 4) % 4;
    }

    public static int clampBitrateKbps(int bitrateKbps, int minKbps, int maxKbps) {
        int lo = Math.max(500, minKbps);
        int hi = Math.max(lo, maxKbps);
        if (bitrateKbps <= 0) return Math.min(hi, Math.max(lo, 12_000));
        return Math.max(lo, Math.min(hi, bitrateKbps));
    }

    public static int alignDimension(int value, int alignment, int minVal, int maxVal) {
        int align = Math.max(2, alignment);
        if ((align & 1) != 0) align += 1;
        int clampedMin = ((minVal + align - 1) / align) * align;
        int clampedMax = (maxVal / align) * align;
        if (clampedMax < clampedMin) clampedMax = clampedMin;
        int v = Math.max(clampedMin, Math.min(clampedMax, value));
        int rounded = ((v + (align / 2)) / align) * align;
        return Math.max(clampedMin, Math.min(clampedMax, rounded));
    }

    public static int[] alignForEncoder(int width, int height, int alignment) {
        return alignForEncoder(width, height, alignment, alignment);
    }

    public static int[] alignForEncoder(int width, int height, int alignW, int alignH) {
        int safeW = width > 0 ? width : 1920;
        int safeH = height > 0 ? height : 1080;
        boolean portrait = safeH > safeW;
        int minW = portrait ? MIN_SHORT_EDGE : MIN_LONG_EDGE;
        int maxW = portrait ? MAX_ENCODER_SHORT_EDGE : MAX_ENCODER_LONG_EDGE;
        int minH = portrait ? MIN_LONG_EDGE : MIN_SHORT_EDGE;
        int maxH = portrait ? MAX_ENCODER_LONG_EDGE : MAX_ENCODER_SHORT_EDGE;
        if (safeW > maxW || safeH > maxH) {
            double scale = Math.min((double) maxW / safeW, (double) maxH / safeH);
            safeW = (int) Math.round(safeW * scale);
            safeH = (int) Math.round(safeH * scale);
        }
        return new int[]{
                alignDimension(safeW, alignW, minW, maxW),
                alignDimension(safeH, alignH, minH, maxH)
        };
    }

    public static float clampFps(float fps, float fallback) {
        if (!Float.isFinite(fps) || fps <= 0f) return fallback;
        return Math.max(MIN_FPS, Math.min(MAX_FPS, fps));
    }

    /**
     * Computes the usable desktop work area (excluding top menu bar and bottom floating dock).
     */
    public static IntRect computeWorkArea(int canvasW, int canvasH, int canvasDpi) {
        int w = Math.max(640, canvasW);
        int h = Math.max(480, canvasH);
        int topInset = dpToPx(TOP_BAR_HEIGHT_DP + 4, canvasDpi);
        int bottomInset = dpToPx(DOCK_RESERVED_BOTTOM_DP, canvasDpi);
        int padX = dpToPx(6, canvasDpi);
        int bottom = Math.max(topInset + MIN_WINDOW_EDGE_PX, h - bottomInset);
        return new IntRect(padX, topInset, Math.max(padX + MIN_WINDOW_EDGE_PX, w - padX), bottom);
    }

    /**
     * Computes staggered cascading initial frame for a newly opened window according to its profile.
     */
    public static IntRect computeInitialWindowFrame(
            WindowProfile profile, int cascadeIndex, int canvasW, int canvasH, int canvasDpi) {
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi);
        int workW = work.width();
        int workH = work.height();

        int targetW;
        int targetH;
        if (profile == WindowProfile.PORTRAIT) {
            targetH = Math.min(workH - dpToPx(16, canvasDpi), Math.max(dpToPx(420, canvasDpi), Math.round(workH * 0.86f)));
            int contentH = Math.max(240, targetH - dpToPx(WINDOW_CHROME_HEADER_DP, canvasDpi));
            targetW = Math.min(workW - dpToPx(24, canvasDpi), Math.max(dpToPx(280, canvasDpi), Math.round(contentH * 0.486f)));
        } else if (profile == WindowProfile.TABLET) {
            targetH = Math.min(workH - dpToPx(24, canvasDpi), Math.round(workH * 0.80f));
            targetW = Math.min(workW - dpToPx(32, canvasDpi), Math.round(targetH * 1.33f));
        } else {
            targetW = Math.min(workW - dpToPx(36, canvasDpi), Math.max(dpToPx(520, canvasDpi), Math.round(workW * 0.64f)));
            targetH = Math.min(workH - dpToPx(28, canvasDpi), Math.max(dpToPx(360, canvasDpi), Math.round(workH * 0.76f)));
        }

        targetW = alignEven(targetW, MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, workW));
        targetH = alignEven(targetH, MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, workH));

        int step = Math.max(0, cascadeIndex % 6);
        int offsetStepPx = dpToPx(26, canvasDpi);
        int baseLeft = work.left + Math.max(0, (workW - targetW) / 2) - dpToPx(40, canvasDpi);
        int baseTop = work.top + Math.max(0, (workH - targetH) / 3);

        int left = baseLeft + step * offsetStepPx;
        int top = baseTop + step * offsetStepPx;
        return clampWindowFrame(new IntRect(left, top, left + targetW, top + targetH), canvasW, canvasH, canvasDpi);
    }

    /**
     * Clamps a window frame so its title bar stays reachable inside the desktop canvas.
     */
    public static IntRect clampWindowFrame(IntRect raw, int canvasW, int canvasH, int canvasDpi) {
        int w = alignEven(raw.width(), MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, canvasW));
        int h = alignEven(raw.height(), MIN_WINDOW_EDGE_PX, Math.max(MIN_WINDOW_EDGE_PX, canvasH - dpToPx(TOP_BAR_HEIGHT_DP, canvasDpi)));
        int minTop = dpToPx(TOP_BAR_HEIGHT_DP, canvasDpi);
        int maxTop = Math.max(minTop, canvasH - dpToPx(WINDOW_CHROME_HEADER_DP + 24, canvasDpi));
        int minVisX = dpToPx(96, canvasDpi);
        int minLeft = -w + minVisX;
        int maxLeft = Math.max(0, canvasW - minVisX);

        int clampedLeft = Math.max(minLeft, Math.min(maxLeft, raw.left));
        int clampedTop = Math.max(minTop, Math.min(maxTop, raw.top));
        return new IntRect(clampedLeft, clampedTop, clampedLeft + w, clampedTop + h);
    }

    /**
     * Computes exact snap-zone bounds inside the desktop work area.
     */
    public static IntRect computeSnapFrame(String presentation, int canvasW, int canvasH, int canvasDpi) {
        IntRect work = computeWorkArea(canvasW, canvasH, canvasDpi);
        int gap = dpToPx(SNAP_GAP_DP, canvasDpi);
        int midX = work.left + work.width() / 2;
        int midY = work.top + work.height() / 2;

        if (BridgeProtocol.PRES_SNAP_LEFT.equals(presentation)) {
            return new IntRect(work.left, work.top, midX - gap / 2, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_RIGHT.equals(presentation)) {
            return new IntRect(midX + gap / 2, work.top, work.right, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_TL.equals(presentation)) {
            return new IntRect(work.left, work.top, midX - gap / 2, midY - gap / 2);
        }
        if (BridgeProtocol.PRES_SNAP_TR.equals(presentation)) {
            return new IntRect(midX + gap / 2, work.top, work.right, midY - gap / 2);
        }
        if (BridgeProtocol.PRES_SNAP_BL.equals(presentation)) {
            return new IntRect(work.left, midY + gap / 2, midX - gap / 2, work.bottom);
        }
        if (BridgeProtocol.PRES_SNAP_BR.equals(presentation)) {
            return new IntRect(midX + gap / 2, midY + gap / 2, work.right, work.bottom);
        }
        return work;
    }

    /**
     * Computes the VirtualDisplay pixel dimensions for a window's client content area (excluding header bar).
     */
    public static int[] computeContentBufferSize(IntRect windowFrame, int canvasDpi) {
        int headerPx = dpToPx(WINDOW_CHROME_HEADER_DP, canvasDpi);
        int cw = alignEven(windowFrame.width(), 240, MAX_WINDOW_EDGE_PX);
        int ch = alignEven(Math.max(200, windowFrame.height() - headerPx), 200, MAX_WINDOW_EDGE_PX);
        return new int[]{cw, ch};
    }
}
