package com.extbridge.daemon;

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

    private DisplayGeometryUtil() {}

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
