package com.extbridge.daemon.sink;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;

import com.extbridge.daemon.DisplayGeometryUtil;
import com.extbridge.daemon.stream.ExtCastProtocol;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Zero-copy MediaCodec hardware video encoder sink feeding ExtCast v1 VIDEO_CONFIG & VIDEO_FRAME.
 * Prefers HEVC (video/hevc) with graceful fallback to H.264 (video/avc), enforces 8/16 even
 * encoder alignment, ultra-low-latency flags, dynamic ABR bitrate, and on-demand IDR keyframes.
 */
public final class RemoteStreamSink implements DisplaySink {
    public static final int CODEC_PREF_AUTO_HEVC = 0;
    public static final int CODEC_PREF_H264 = 1;

    private static final String MIME_HEVC = "video/hevc";
    private static final String MIME_AVC = "video/avc";
    private static final int MIN_BITRATE_KBPS = 1500;
    private static final int MAX_BITRATE_KBPS = 40_000;
    private static final long DEQUEUE_TIMEOUT_US = 10_000L;
    private static final long ABR_CONGESTION_COOLDOWN_MS = 1800L;
    private static final long ABR_RECOVERY_INTERVAL_MS = 4000L;

    public interface FrameListener {
        void onVideoConfig(ExtCastProtocol.VideoConfig config, long timestampUs);
        void onVideoFrame(byte[] annexBData, int flags, long timestampUs);
        void onEncoderError(String reason);
    }

    private static final class EncoderSession {
        final MediaCodec codec;
        final Surface inputSurface;
        final int codecId;
        final String mime;
        final int width;
        final int height;
        final int dpi;
        final float fps;
        final int displayMode;
        final int rotation;
        volatile boolean running = true;
        Thread drainThread;
        byte[] lastCsd;

        EncoderSession(
                MediaCodec codec, Surface inputSurface, int codecId, String mime,
                int width, int height, int dpi, float fps, int displayMode, int rotation) {
            this.codec = codec;
            this.inputSurface = inputSurface;
            this.codecId = codecId;
            this.mime = mime;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.displayMode = displayMode;
            this.rotation = rotation;
        }
    }

    private final Object lock = new Object();
    private final FrameListener listener;
    private volatile int sinkType;
    private volatile EncoderSession session;
    private volatile int currentBitrateKbps = 12_000;
    private volatile int targetBitrateKbps = 12_000;
    private volatile int maxBitrateKbps = 24_000;
    private volatile int actualFpsX100 = 0;
    private volatile long lastCongestionMs = 0L;
    private volatile long lastRecoveryMs = 0L;

    public RemoteStreamSink(int sinkType, FrameListener listener) {
        this.sinkType = sinkType;
        this.listener = listener;
    }

    public void setSinkType(int sinkType) {
        this.sinkType = sinkType;
    }

    public void setMaxBitrateKbps(int maxKbps) {
        int clamped = DisplayGeometryUtil.clampBitrateKbps(maxKbps, MIN_BITRATE_KBPS, MAX_BITRATE_KBPS);
        this.maxBitrateKbps = clamped;
        if (currentBitrateKbps > clamped) {
            updateBitrateKbps(clamped);
        }
    }

    /**
     * Starts or reconfigures the hardware encoder and returns the active input {@link Surface}.
     * Caller should attach this Surface to {@code VirtualDisplay} before invoking {@link #releaseOldSession}
     * (handled cleanly inside when swapping, or atomically with fallback if encoder slots are scarce).
     */
    public Surface startOrReconfigure(
            int reqWidth,
            int reqHeight,
            int reqDpi,
            float reqFps,
            int reqBitrateKbps,
            int codecPref,
            int displayMode,
            int rotation) {
        synchronized (lock) {
            int clampedTarget = DisplayGeometryUtil.clampBitrateKbps(
                    reqBitrateKbps, MIN_BITRATE_KBPS, Math.max(MIN_BITRATE_KBPS, maxBitrateKbps));
            float clampedFps = DisplayGeometryUtil.clampFps(reqFps, 60f);
            int clampedRot = DisplayGeometryUtil.normalizeRotation(rotation);

            EncoderSession oldSession = this.session;
            EncoderSession nextSession = null;
            try {
                nextSession = createAndStartEncoder(
                        reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
            } catch (Throwable firstErr) {
                // Some SoCs limit concurrent HW encoders: tear down old session first and retry once.
                if (oldSession != null) {
                    stopSessionQuietly(oldSession);
                    this.session = null;
                    oldSession = null;
                }
                try {
                    nextSession = createAndStartEncoder(
                            reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
                } catch (Throwable secondErr) {
                    System.err.println("EXTBRIDGE_ENCODER_START_FAILED: " + secondErr);
                    secondErr.printStackTrace();
                    if (listener != null) {
                        listener.onEncoderError("Failed to start video encoder: " + secondErr.getMessage());
                    }
                    return null;
                }
            }

            this.targetBitrateKbps = clampedTarget;
            this.currentBitrateKbps = clampedTarget;
            this.session = nextSession;
            startDrainThread(nextSession);

            // Stop previous encoder session after new one is ready (caller will swap VD surface right away).
            if (oldSession != null) {
                final EncoderSession toStop = oldSession;
                // Delay brief teardown slightly or stop immediately? Stop its drain loop now; release surface/codec cleanly.
                stopSessionQuietly(toStop);
            }
            return nextSession.inputSurface;
        }
    }

    /**
     * Reconfigures and invokes {@code surfaceConsumer} with the new Surface BEFORE tearing down
     * the old Surface, ensuring seamless zero-flicker VirtualDisplay surface handover.
     */
    public interface SurfaceSwapper {
        void onNewSurfaceReady(Surface newSurface, int alignedW, int alignedH, int dpi);
    }

    public boolean reconfigureAndSwap(
            int reqWidth,
            int reqHeight,
            int reqDpi,
            float reqFps,
            int reqBitrateKbps,
            int codecPref,
            int displayMode,
            int rotation,
            SurfaceSwapper swapper) {
        synchronized (lock) {
            int clampedTarget = DisplayGeometryUtil.clampBitrateKbps(
                    reqBitrateKbps, MIN_BITRATE_KBPS, Math.max(MIN_BITRATE_KBPS, maxBitrateKbps));
            float clampedFps = DisplayGeometryUtil.clampFps(reqFps, 60f);
            int clampedRot = DisplayGeometryUtil.normalizeRotation(rotation);

            EncoderSession oldSession = this.session;
            EncoderSession nextSession;
            try {
                nextSession = createAndStartEncoder(
                        reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
            } catch (Throwable firstErr) {
                if (oldSession != null) {
                    stopSessionQuietly(oldSession);
                    this.session = null;
                    oldSession = null;
                }
                try {
                    nextSession = createAndStartEncoder(
                            reqWidth, reqHeight, reqDpi, clampedFps, clampedTarget, codecPref, displayMode, clampedRot);
                } catch (Throwable secondErr) {
                    System.err.println("EXTBRIDGE_ENCODER_RECONFIG_FAILED: " + secondErr);
                    if (listener != null) {
                        listener.onEncoderError("Encoder reconfigure failed: " + secondErr.getMessage());
                    }
                    return false;
                }
            }

            this.targetBitrateKbps = clampedTarget;
            this.currentBitrateKbps = clampedTarget;
            this.session = nextSession;
            startDrainThread(nextSession);

            if (swapper != null) {
                swapper.onNewSurfaceReady(
                        nextSession.inputSurface, nextSession.width, nextSession.height, nextSession.dpi);
            }
            if (oldSession != null) {
                stopSessionQuietly(oldSession);
            }
            requestKeyFrame();
            return true;
        }
    }

    @Override
    public int getSinkType() {
        return isActive() ? sinkType : SINK_NONE;
    }

    @Override
    public boolean isActive() {
        EncoderSession s = this.session;
        return s != null && s.running;
    }

    @Override
    public int getWidth() {
        EncoderSession s = this.session;
        return (s != null) ? s.width : 0;
    }

    @Override
    public int getHeight() {
        EncoderSession s = this.session;
        return (s != null) ? s.height : 0;
    }

    @Override
    public int getDpi() {
        EncoderSession s = this.session;
        return (s != null) ? s.dpi : 240;
    }

    @Override
    public float getFps() {
        EncoderSession s = this.session;
        return (s != null) ? s.fps : 60f;
    }

    @Override
    public int getBitrateKbps() {
        return currentBitrateKbps;
    }

    @Override
    public int getActualFpsX100() {
        return actualFpsX100;
    }

    public int getCodecId() {
        EncoderSession s = this.session;
        return (s != null) ? s.codecId : ExtCastProtocol.VIDEO_CODEC_H264;
    }

    public int getRotation() {
        EncoderSession s = this.session;
        return (s != null) ? s.rotation : 0;
    }

    public int getDisplayMode() {
        EncoderSession s = this.session;
        return (s != null) ? s.displayMode : ExtCastProtocol.DISPLAY_MODE_DEX;
    }

    @Override
    public Surface getInputSurface() {
        EncoderSession s = this.session;
        return (s != null && s.running) ? s.inputSurface : null;
    }

    @Override
    public void requestKeyFrame() {
        EncoderSession s = this.session;
        if (s == null || !s.running) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            s.codec.setParameters(params);
        } catch (Throwable ignored) {}
    }

    @Override
    public void updateBitrateKbps(int bitrateKbps) {
        int clamped = DisplayGeometryUtil.clampBitrateKbps(
                bitrateKbps, MIN_BITRATE_KBPS, Math.max(MIN_BITRATE_KBPS, maxBitrateKbps));
        this.targetBitrateKbps = clamped;
        applyBitrateToCodec(clamped);
    }

    /**
     * Called by RemoteStreamServer when outbound video queue drops frames due to network congestion.
     * Applies immediate ABR step-down (~25%) with cooldown and requests an IDR recovery frame.
     */
    public void onNetworkCongestion() {
        long now = SystemClock.uptimeMillis();
        if (now - lastCongestionMs < ABR_CONGESTION_COOLDOWN_MS) {
            requestKeyFrame();
            return;
        }
        lastCongestionMs = now;
        lastRecoveryMs = now;
        int reduced = Math.max(MIN_BITRATE_KBPS, (currentBitrateKbps * 3) / 4);
        if (reduced < currentBitrateKbps) {
            applyBitrateToCodec(reduced);
        }
        requestKeyFrame();
    }

    /**
     * Called periodically when outbound queue stays healthy so ABR can gradually climb back
     * toward {@code targetBitrateKbps}.
     */
    public void maybeRecoverBitrate() {
        int target = Math.min(targetBitrateKbps, maxBitrateKbps);
        if (currentBitrateKbps >= target) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastCongestionMs < ABR_RECOVERY_INTERVAL_MS
                || now - lastRecoveryMs < ABR_RECOVERY_INTERVAL_MS) {
            return;
        }
        lastRecoveryMs = now;
        int next = Math.min(target, currentBitrateKbps + Math.max(1000, (target - currentBitrateKbps) / 3));
        applyBitrateToCodec(next);
    }

    private void applyBitrateToCodec(int kbps) {
        EncoderSession s = this.session;
        this.currentBitrateKbps = kbps;
        if (s == null || !s.running) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000);
            s.codec.setParameters(params);
        } catch (Throwable ignored) {}
    }

    @Override
    public void stop() {
        synchronized (lock) {
            EncoderSession old = this.session;
            this.session = null;
            this.actualFpsX100 = 0;
            if (old != null) {
                stopSessionQuietly(old);
            }
        }
    }

    private EncoderSession createAndStartEncoder(
            int reqW, int reqH, int reqDpi, float fps, int bitrateKbps,
            int codecPref, int displayMode, int rotation) throws Exception {
        String[] mimes = (codecPref == CODEC_PREF_H264)
                ? new String[]{MIME_AVC}
                : new String[]{MIME_HEVC, MIME_AVC};

        Throwable lastErr = null;
        for (String mime : mimes) {
            int codecId = MIME_HEVC.equals(mime)
                    ? ExtCastProtocol.VIDEO_CODEC_HEVC
                    : ExtCastProtocol.VIDEO_CODEC_H264;
            int[] capAlign = queryEncoderAlignment(mime);
            int[][] dimCandidates = buildDimensionCandidates(reqW, reqH, capAlign[0], capAlign[1]);

            for (int[] dims : dimCandidates) {
                int w = dims[0];
                int h = dims[1];
                int dpi = DisplayGeometryUtil.clampDpi(reqDpi, w, h);
                // First try full low-latency params, then minimal safe params
                for (boolean lowLatency : new boolean[]{true, false}) {
                    MediaCodec codec = null;
                    Surface surf = null;
                    try {
                        codec = createPreferredHardwareEncoder(mime);
                        MediaFormat fmt = buildVideoFormat(mime, w, h, fps, bitrateKbps, lowLatency);
                        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        surf = codec.createInputSurface();
                        if (surf != null && Build.VERSION.SDK_INT >= 30) {
                            try {
                                surf.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                            } catch (Throwable ignored) {}
                        }
                        codec.start();
                        System.out.println("EXTBRIDGE_ENCODER_STARTED mime=" + mime
                                + " size=" + w + "x" + h + "@" + dpi + "dpi fps=" + fps
                                + " bitrate=" + bitrateKbps + "kbps lowLatency=" + lowLatency);
                        return new EncoderSession(codec, surf, codecId, mime, w, h, dpi, fps, displayMode, rotation);
                    } catch (Throwable t) {
                        lastErr = t;
                        if (surf != null) {
                            try { surf.release(); } catch (Throwable ignored) {}
                        }
                        if (codec != null) {
                            try { codec.release(); } catch (Throwable ignored) {}
                        }
                    }
                }
            }
        }
        if (lastErr instanceof Exception) {
            throw (Exception) lastErr;
        }
        throw new RuntimeException("Unable to configure video encoder", lastErr);
    }

    private static MediaCodec createPreferredHardwareEncoder(String mime) throws Exception {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String fallbackName = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                boolean matches = false;
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        matches = true;
                        break;
                    }
                }
                if (!matches) continue;
                String name = info.getName();
                boolean isSw = isSoftwareCodec(info, name);
                if (!isSw) {
                    return MediaCodec.createByCodecName(name);
                }
                if (fallbackName == null) {
                    fallbackName = name;
                }
            }
            if (fallbackName != null) {
                return MediaCodec.createByCodecName(fallbackName);
            }
        } catch (Throwable ignored) {}
        return MediaCodec.createEncoderByType(mime);
    }

    private static boolean isSoftwareCodec(MediaCodecInfo info, String name) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return info.isSoftwareOnly();
            } catch (Throwable ignored) {}
        }
        String lower = (name != null) ? name.toLowerCase(java.util.Locale.US) : "";
        return lower.startsWith("omx.google.") || lower.startsWith("c2.android.");
    }

    private static int[] queryEncoderAlignment(String mime) {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder()) continue;
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                        if (caps != null && caps.getVideoCapabilities() != null) {
                            int wa = Math.max(8, caps.getVideoCapabilities().getWidthAlignment());
                            int ha = Math.max(8, caps.getVideoCapabilities().getHeightAlignment());
                            return new int[]{wa, ha};
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new int[]{8, 8};
    }

    private static int[][] buildDimensionCandidates(int reqW, int reqH, int capAlignW, int capAlignH) {
        int[] primary = DisplayGeometryUtil.alignForEncoder(reqW, reqH, capAlignW, capAlignH);
        int[] strict16 = DisplayGeometryUtil.alignForEncoder(reqW, reqH, 16, 16);
        if (primary[0] == strict16[0] && primary[1] == strict16[1]) {
            return new int[][]{primary};
        }
        return new int[][]{primary, strict16};
    }

    private static MediaFormat buildVideoFormat(
            String mime, int width, int height, float fps, int bitrateKbps, boolean lowLatency) {
        MediaFormat fmt = MediaFormat.createVideoFormat(mime, width, height);
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000);
        int targetFps = Math.max(15, Math.round(fps));
        fmt.setInteger(MediaFormat.KEY_FRAME_RATE, targetFps);
        // Longer GOP interval avoids periodic giant IDR stutter spikes on desktop content;
        // on-demand REQUEST_IDR handles instant recovery when needed.
        fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        // Keepalive repeat at 2Hz on static desktop to save Wi-Fi/USB bandwidth.
        fmt.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L);
        if (lowLatency) {
            try { fmt.setInteger(MediaFormat.KEY_PRIORITY, 0); } catch (Throwable ignored) {}
            try { fmt.setInteger(MediaFormat.KEY_OPERATING_RATE, Math.max(60, targetFps)); } catch (Throwable ignored) {}
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    fmt.setInteger(MediaFormat.KEY_LATENCY, 0);
                }
            } catch (Throwable ignored) {}
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    fmt.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
                }
            } catch (Throwable ignored) {}
            try {
                fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Throwable ignored) {}
        }
        return fmt;
    }

    private void startDrainThread(final EncoderSession s) {
        Thread t = new Thread(() -> drainLoop(s), "ExtVideoEncoder");
        t.setDaemon(true);
        s.drainThread = t;
        t.start();
    }

    private void drainLoop(EncoderSession s) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long fpsWindowStartMs = SystemClock.uptimeMillis();
        int fpsWindowFrames = 0;

        while (s.running && this.session == s) {
            try {
                int outIndex = s.codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = s.codec.getOutputFormat();
                    byte[] csd = extractCsdAnnexB(outFmt);
                    if (csd != null && csd.length > 0) {
                        s.lastCsd = csd;
                        emitVideoConfig(s, csd, SystemClock.elapsedRealtimeNanos() / 1000L);
                    }
                    continue;
                }
                if (outIndex < 0) {
                    continue;
                }

                try {
                    ByteBuffer buf = s.codec.getOutputBuffer(outIndex);
                    if (buf != null && info.size > 0) {
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buf.get(data);

                        long ptsUs = (info.presentationTimeUs > 0)
                                ? info.presentationTimeUs
                                : (SystemClock.elapsedRealtimeNanos() / 1000L);
                        boolean isConfig = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                        boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;

                        if (isConfig) {
                            if (s.lastCsd == null || !Arrays.equals(s.lastCsd, data)) {
                                s.lastCsd = data;
                                emitVideoConfig(s, data, ptsUs);
                            }
                        } else {
                            int frameFlags = 0;
                            if (isKey) {
                                frameFlags |= ExtCastProtocol.FLAG_VIDEO_KEYFRAME;
                                if (hasInlineParameterSets(s.codecId, data)) {
                                    frameFlags |= ExtCastProtocol.FLAG_VIDEO_CONFIG_INLINE;
                                }
                            }
                            if (listener != null && s.running) {
                                listener.onVideoFrame(data, frameFlags, ptsUs);
                            }
                            fpsWindowFrames++;
                            long nowMs = SystemClock.uptimeMillis();
                            long elapsed = nowMs - fpsWindowStartMs;
                            if (elapsed >= 1000L) {
                                actualFpsX100 = Math.round(fpsWindowFrames * 100_000f / elapsed);
                                fpsWindowFrames = 0;
                                fpsWindowStartMs = nowMs;
                            }
                        }
                    }
                } finally {
                    s.codec.releaseOutputBuffer(outIndex, false);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } catch (IllegalStateException ise) {
                if (s.running && this.session == s) {
                    System.err.println("EXTBRIDGE_ENCODER_DRAIN_STATE_ERR: " + ise.getMessage());
                }
                break;
            } catch (Throwable t) {
                if (s.running && this.session == s) {
                    System.err.println("EXTBRIDGE_ENCODER_DRAIN_ERR: " + t);
                }
                break;
            }
        }
    }

    private void emitVideoConfig(EncoderSession s, byte[] csd, long ptsUs) {
        if (listener == null || !s.running) return;
        ExtCastProtocol.VideoConfig cfg = new ExtCastProtocol.VideoConfig(
                s.codecId,
                s.width,
                s.height,
                s.dpi,
                s.rotation,
                Math.round(s.fps * 100f),
                s.displayMode,
                csd);
        listener.onVideoConfig(cfg, ptsUs);
    }

    private static byte[] extractCsdAnnexB(MediaFormat fmt) {
        if (fmt == null) return ExtCastProtocol.EMPTY_BYTES;
        ByteBuffer csd0 = fmt.containsKey("csd-0") ? fmt.getByteBuffer("csd-0") : null;
        ByteBuffer csd1 = fmt.containsKey("csd-1") ? fmt.getByteBuffer("csd-1") : null;
        int len0 = (csd0 != null) ? csd0.remaining() : 0;
        int len1 = (csd1 != null) ? csd1.remaining() : 0;
        if (len0 + len1 <= 0) return ExtCastProtocol.EMPTY_BYTES;
        byte[] out = new byte[len0 + len1];
        if (len0 > 0) {
            ByteBuffer dup0 = csd0.duplicate();
            dup0.get(out, 0, len0);
        }
        if (len1 > 0) {
            ByteBuffer dup1 = csd1.duplicate();
            dup1.get(out, len0, len1);
        }
        return out;
    }

    private static boolean hasInlineParameterSets(int codecId, byte[] annexB) {
        if (annexB == null || annexB.length < 5) return false;
        int offset = 0;
        if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 0 && annexB[3] == 1) {
            offset = 4;
        } else if (annexB[0] == 0 && annexB[1] == 0 && annexB[2] == 1) {
            offset = 3;
        } else {
            return false;
        }
        int nalByte = annexB[offset] & 0xFF;
        if (codecId == ExtCastProtocol.VIDEO_CODEC_HEVC) {
            int nalType = (nalByte >> 1) & 0x3F;
            return nalType == 32 || nalType == 33 || nalType == 34; // VPS, SPS, PPS
        } else {
            int nalType = nalByte & 0x1F;
            return nalType == 7 || nalType == 8; // SPS, PPS
        }
    }

    private static void stopSessionQuietly(EncoderSession s) {
        if (s == null) return;
        s.running = false;
        try { s.codec.signalEndOfInputStream(); } catch (Throwable ignored) {}
        Thread t = s.drainThread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(350L); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try { s.codec.stop(); } catch (Throwable ignored) {}
        try { s.codec.release(); } catch (Throwable ignored) {}
        try { if (s.inputSurface != null) s.inputSurface.release(); } catch (Throwable ignored) {}
    }
}
