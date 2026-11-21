package com.extbridge.daemon;

import com.extbridge.daemon.ipc.BridgeProtocol;

/**
 * Pure-Java display geometry, encoder dimension alignment, rotation, and mirror
 * coordinate mapping utilities shared by ExtBridge, RemoteStreamSink, and RemoteInputBridge.
 */
public final class DisplayGeometryUtil {
    public static final int MIN_LONG_EDGE = 640;
    public static final int MAX_LONG_EDGE = 7680;
    public static final int MIN_SHORT_EDGE = 480;
    public static final int MAX_SHORT_EDGE = 4320;
    public static final int MAX_ENCODER_LONG_EDGE = 4096;
    public static final int MAX_ENCODER_SHORT_EDGE = 2160;
    public static final int MIN_DPI = 80;
    public static final int MAX_DPI = 640;
    public static final float MIN_FPS = 15.0f;
    public static final float MAX_FPS = 360.0f;
    public static final int EDGE_MARGIN_MIN_PX = 48;

    // Unified Samsung DeX Desktop WorkArea constants (SSOT shared with helper DesktopGeometry)
    public static final int MENU_BAR_HEIGHT_DP = 0;
    public static final int WORKAREA_TOP_INSET_DP = 8;
    public static final int TASKBAR_HEIGHT_DP = 56;
    public static final int TASKBAR_RESERVED_INSET_DP = 72;
    public static final int TASKBAR_AUTOHIDE_INSET_DP = 8;
    public static final int WINDOW_HEADER_HEIGHT_DP = 38;
    public static final int WINDOW_BOTTOM_CHIN_DP = 0;
    public static final int DOCK_RESERVED_INSET_DP = TASKBAR_RESERVED_INSET_DP;
    public static final int DOCK_AUTOHIDE_INSET_DP = TASKBAR_AUTOHIDE_INSET_DP;
    public static final int WORKAREA_SIDE_INSET_DP = 8;

    /** Immutable pixel bounds of the usable desktop area excluding Taskbar and safe margins. */
    public static final class WorkArea {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public WorkArea(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = Math.max(left + 1, right);
            this.bottom = Math.max(top + 1, bottom);
        }

        public int width() { return Math.max(1, right - left); }
        public int height() { return Math.max(1, bottom - top); }
    }

    private DisplayGeometryUtil() {}

    public static int dpToPx(int dp, int dpi) {
        int effDpi = clampDpi(dpi, 1920, 1080);
        return Math.max(0, Math.round(dp * (effDpi / 160f)));
    }

    /**
     * Computes the unified DeX desktop usable {@link WorkArea} for any USB or Mac canvas.
     * Reserves no top menu bar (pure bottom floating taskbar) and leaves clean breathing margins.
     */
    public static WorkArea computeDesktopWorkArea(int displayW, int displayH, int dpi, boolean taskbarAutoHide) {
        int w = Math.max(320, displayW);
        int h = Math.max(240, displayH);
        int topPx = Math.min(h / 4, dpToPx(WORKAREA_TOP_INSET_DP, dpi));
        int bottomInsetPx = Math.min(h / 3, dpToPx(taskbarAutoHide ? TASKBAR_AUTOHIDE_INSET_DP : TASKBAR_RESERVED_INSET_DP, dpi));
        int sidePx = Math.min(w / 16, dpToPx(WORKAREA_SIDE_INSET_DP, dpi));
        return new WorkArea(sidePx, topPx, Math.max(sidePx + 1, w - sidePx), Math.max(topPx + 1, h - bottomInsetPx));
    }

    public static boolean isValidDims(int w, int h) {
        int longEdge = Math.max(w, h);
        int shortEdge = Math.min(w, h);
        return longEdge >= MIN_LONG_EDGE && longEdge <= MAX_LONG_EDGE
                && shortEdge >= MIN_SHORT_EDGE && shortEdge <= MAX_SHORT_EDGE;
    }

    public static int normalizeRotation(int rot) {
        return ((rot % 4) + 4) % 4;
    }

    public static boolean isPortraitRotation(int rot) {
        return (normalizeRotation(rot) & 1) == 1;
    }

    /**
     * Returns true when swapping from (prevW, prevH) to (newW, newH) flips
     * landscape <-> portrait orientation.
     */
    public static boolean isOrientationAspectFlipped(int prevW, int prevH, int newW, int newH) {
        if (prevW <= 0 || prevH <= 0 || newW <= 0 || newH <= 0) {
            return false;
        }
        if (prevW == prevH || newW == newH) {
            return false;
        }
        return (prevW > prevH) != (newW > newH);
    }

    /**
     * Aligns a single dimension to a positive even multiple of {@code alignment} (minimum 2),
     * rounding to the nearest multiple and clamping within [minVal, maxVal].
     */
    public static int alignDimension(int value, int alignment, int minVal, int maxVal) {
        int align = Math.max(2, alignment);
        if ((align & 1) != 0) {
            align += 1;
        }
        int clampedMin = ((minVal + align - 1) / align) * align;
        int clampedMax = (maxVal / align) * align;
        if (clampedMax < clampedMin) {
            clampedMax = clampedMin;
        }
        int v = Math.max(clampedMin, Math.min(clampedMax, value));
        int rounded = ((v + (align / 2)) / align) * align;
        if (rounded < clampedMin) {
            rounded = clampedMin;
        }
        if (rounded > clampedMax) {
            rounded = clampedMax;
        }
        return rounded;
    }

    /**
     * Aligns (width, height) for hardware MediaCodec video encoders to even multiples of
     * {@code alignment} (typically 8 or 16; 8 preserves 1920x1080 exactly while keeping
     * all common HEVC/AVC encoders happy, and 16 serves as strict fallback).
     */
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

        // Preserve aspect ratio if dimensions exceed encoder limits before rounding.
        if (safeW > maxW || safeH > maxH) {
            double scale = Math.min((double) maxW / safeW, (double) maxH / safeH);
            safeW = (int) Math.round(safeW * scale);
            safeH = (int) Math.round(safeH * scale);
        }

        int outW = alignDimension(safeW, alignW, minW, maxW);
        int outH = alignDimension(safeH, alignH, minH, maxH);
        return new int[]{outW, outH};
    }

    /**
     * Ensures dimensions match the requested rotation orientation (0/2=landscape, 1/3=portrait).
     */
    public static int[] orientDimensionsForRotation(int width, int height, int rot) {
        boolean wantPortrait = isPortraitRotation(rot);
        if (wantPortrait && width > height) {
            return new int[]{height, width};
        }
        if (!wantPortrait && height > width) {
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }

    public static int computeAutoDpi(int w, int h) {
        int minEdge = Math.min(Math.max(1, w), Math.max(1, h));
        return Math.max(120, Math.min(280, Math.round(minEdge * 160f / 1440f)));
    }

    public static int clampDpi(int dpi, int fallbackW, int fallbackH) {
        if (dpi < MIN_DPI || dpi > MAX_DPI) {
            return computeAutoDpi(fallbackW, fallbackH);
        }
        return dpi;
    }

    public static float clampFps(float fps, float def) {
        if (!Float.isFinite(fps) || fps < MIN_FPS) {
            return def;
        }
        return Math.min(MAX_FPS, fps);
    }

    public static int clampBitrateKbps(int bitrateKbps, int minKbps, int maxKbps) {
        int lo = Math.max(500, minKbps);
        int hi = Math.max(lo, maxKbps);
        if (bitrateKbps <= 0) {
            return Math.min(hi, Math.max(lo, 12000));
        }
        return Math.max(lo, Math.min(hi, bitrateKbps));
    }

    /**
     * Computes centered aspect-preserving letterbox rectangle {x, y, w, h} of source (srcW, srcH)
     * rendered inside destination (dstW, dstH).
     */
    public static int[] computeLetterboxRect(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return new int[]{0, 0, Math.max(0, dstW), Math.max(0, dstH)};
        }
        double scale = Math.min((double) dstW / srcW, (double) dstH / srcH);
        int scaledW = Math.max(1, Math.min(dstW, (int) Math.round(srcW * scale)));
        int scaledH = Math.max(1, Math.min(dstH, (int) Math.round(srcH * scale)));
        int x = (dstW - scaledW) / 2;
        int y = (dstH - scaledH) / 2;
        return new int[]{x, y, scaledW, scaledH};
    }

    /**
     * Computes unified Display 0 mirror placement {@code {mirX, mirY, mirW, mirH}} inside an external
     * or remote canvas {@code (outW, outH)} for any {@code sideMode} ({@code OFF}, {@code LEFT},
     * {@code RIGHT}, {@code CENTER_DUAL}), identical across USB physical monitors and Mac streams.
     */
    public static int[] computeMirrorPlacement(int d0W, int d0H, int outW, int outH, int sideMode) {
        int safeD0W = d0W > 0 ? d0W : 1920;
        int safeD0H = d0H > 0 ? d0H : 1200;
        int safeOutW = Math.max(1, outW);
        int safeOutH = Math.max(1, outH);
        float fit = Math.min(safeOutW / (float) safeD0W, safeOutH / (float) safeD0H);
        int mirW = Math.max(1, Math.round(safeD0W * fit));
        int mirH = Math.max(1, Math.round(safeD0H * fit));
        int mirX = Math.max(0, (safeOutW - mirW) / 2);
        int mirY = Math.max(0, (safeOutH - mirH) / 2);
        if (safeOutW - mirW >= EDGE_MARGIN_MIN_PX) {
            if (sideMode == BridgeProtocol.MIRROR_SIDE_ALIGN_LEFT) {
                mirX = 0;
            } else if (sideMode == BridgeProtocol.MIRROR_SIDE_ALIGN_RIGHT) {
                mirX = Math.max(0, safeOutW - mirW);
            }
        } else if (safeOutH - mirH >= EDGE_MARGIN_MIN_PX) {
            if (sideMode == BridgeProtocol.MIRROR_SIDE_ALIGN_LEFT) {
                mirY = 0;
            } else if (sideMode == BridgeProtocol.MIRROR_SIDE_ALIGN_RIGHT) {
                mirY = Math.max(0, safeOutH - mirH);
            }
        }
        return new int[]{mirX, mirY, mirW, mirH};
    }

    /**
     * Maps a pointer coordinate (streamX, streamY) from a letterboxed remote mirror stream
     * (streamW x streamH) into Display 0 coordinates (d0W x d0H).
     */
    public static float[] mapStreamPointToDisplay(
            float streamX, float streamY,
            int streamW, int streamH,
            int targetW, int targetH,
            boolean letterboxed) {
        if (targetW <= 0 || targetH <= 0) {
            return new float[]{Math.max(0f, streamX), Math.max(0f, streamY)};
        }
        if (!letterboxed || streamW <= 0 || streamH <= 0) {
            float sx = (streamW > 0 && streamW != targetW)
                    ? (streamX * targetW / (float) streamW) : streamX;
            float sy = (streamH > 0 && streamH != targetH)
                    ? (streamY * targetH / (float) streamH) : streamY;
            return new float[]{
                    clampCoord(sx, targetW - 1f),
                    clampCoord(sy, targetH - 1f)
            };
        }
        int[] rect = computeLetterboxRect(targetW, targetH, streamW, streamH);
        float rx = (streamX - rect[0]) * targetW / (float) Math.max(1, rect[2]);
        float ry = (streamY - rect[1]) * targetH / (float) Math.max(1, rect[3]);
        return new float[]{
                clampCoord(rx, targetW - 1f),
                clampCoord(ry, targetH - 1f)
        };
    }

    private static float clampCoord(float value, float maxInclusive) {
        if (!Float.isFinite(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(Math.max(0f, maxInclusive), value));
    }
}
