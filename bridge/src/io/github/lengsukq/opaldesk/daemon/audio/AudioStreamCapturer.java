package io.github.lengsukq.opaldesk.daemon.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;

import io.github.lengsukq.opaldesk.daemon.stream.OpalCastProtocol;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Best-effort Android system playback audio capturer (uid=2000 REMOTE_SUBMIX) encoding
 * 48 kHz stereo PCM into AAC-LC raw AUs for OpalCast v1 AUDIO_CONFIG + AUDIO_FRAME.
 * Gracefully degrades to audioEnabled=false without throwing if OEM ROM denies capture.
 */
public final class AudioStreamCapturer {
    public static final int SAMPLE_RATE = 48_000;
    public static final int CHANNELS = 2;
    public static final int AAC_BITRATE = 128_000;
    private static final String MIME_AAC = "audio/mp4a-latm";
    // Standard 2-byte AudioSpecificConfig: AAC-LC (2), 48kHz (idx 3), stereo (2) -> 0x11 0x90
    private static final byte[] FALLBACK_AAC_LC_48K_STEREO_ASC = new byte[]{0x11, (byte) 0x90};

    public interface AudioListener {
        void onAudioConfig(OpalCastProtocol.AudioConfig config, long timestampUs);
        void onAudioFrame(byte[] aacAu, long timestampUs);
        void onAudioStateChanged(boolean active);
    }

    private final Context ctx;
    private final AudioListener listener;
    private final Object lock = new Object();

    private volatile boolean running = false;
    private volatile boolean active = false;
    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private Thread captureThread;
    private Thread drainThread;

    public AudioStreamCapturer(Context ctx, AudioListener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public boolean isActive() {
        return running && active;
    }

    public boolean start() {
        synchronized (lock) {
            if (running && active) return true;
            stopLocked(false);

            AudioRecord rec = null;
            MediaCodec enc = null;
            try {
                rec = createAudioRecord();
                if (rec == null || rec.getState() != AudioRecord.STATE_INITIALIZED) {
                    if (rec != null) {
                        try { rec.release(); } catch (RuntimeException e) {
                            System.err.println("OPALDESK_AUDIO_REC_RELEASE_WARN: " + e.getMessage());
                        }
                    }
                    System.out.println("OPALDESK_AUDIO_UNAVAILABLE AudioRecord not initialized; degrading gracefully");
                    notifyState(false);
                    return false;
                }

                enc = MediaCodec.createEncoderByType(MIME_AAC);
                MediaFormat fmt = MediaFormat.createAudioFormat(MIME_AAC, SAMPLE_RATE, CHANNELS);
                fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                fmt.setInteger(MediaFormat.KEY_BIT_RATE, AAC_BITRATE);
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                rec.startRecording();
                if (rec.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IllegalStateException("AudioRecord did not enter RECORDSTATE_RECORDING");
                }
                enc.start();

                this.audioRecord = rec;
                this.encoder = enc;
                this.running = true;
                this.active = true;

                final AudioRecord finalRec = rec;
                final MediaCodec finalEnc = enc;
                this.drainThread = new Thread(() -> drainLoop(finalEnc), "ExtAudioDrain");
                this.drainThread.setDaemon(true);
                this.drainThread.start();

                this.captureThread = new Thread(() -> captureLoop(finalRec, finalEnc), "ExtAudioCapture");
                this.captureThread.setDaemon(true);
                this.captureThread.start();

                System.out.println("OPALDESK_AUDIO_STARTED 48kHz stereo AAC-LC");
                notifyState(true);
                return true;
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_FALLBACK_OFF: " + t.getMessage());
                if (enc != null) {
                    try { enc.release(); } catch (RuntimeException e) {
                        System.err.println("OPALDESK_AUDIO_ENC_CLEANUP_WARN: " + e.getMessage());
                    }
                }
                if (rec != null) {
                    try { rec.release(); } catch (RuntimeException e) {
                        System.err.println("OPALDESK_AUDIO_REC_CLEANUP_WARN: " + e.getMessage());
                    }
                }
                this.audioRecord = null;
                this.encoder = null;
                this.running = false;
                this.active = false;
                notifyState(false);
                return false;
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            stopLocked(true);
        }
    }

    private void stopLocked(boolean notifyIfWasActive) {
        boolean wasActive = this.active;
        this.running = false;
        this.active = false;

        AudioRecord rec = this.audioRecord;
        MediaCodec enc = this.encoder;
        this.audioRecord = null;
        this.encoder = null;

        if (rec != null) {
            try { rec.stop(); } catch (RuntimeException ignored) {}
            try { rec.release(); } catch (RuntimeException e) {
                System.err.println("OPALDESK_AUDIO_REC_RELEASE_WARN: " + e.getMessage());
            }
        }
        Thread ct = this.captureThread;
        this.captureThread = null;
        if (ct != null && ct != Thread.currentThread()) {
            try { ct.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (enc != null) {
            try { enc.stop(); } catch (RuntimeException ignored) {}
            try { enc.release(); } catch (RuntimeException e) {
                System.err.println("OPALDESK_AUDIO_ENC_RELEASE_WARN: " + e.getMessage());
            }
        }
        Thread dt = this.drainThread;
        this.drainThread = null;
        if (dt != null && dt != Thread.currentThread()) {
            try { dt.join(250L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (notifyIfWasActive && wasActive) {
            notifyState(false);
        }
    }

    private AudioRecord createAudioRecord() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            minBuf = 8192;
        }
        int bufSize = Math.max(minBuf * 2, 16_384);
        AudioFormat af = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        // Attempt 1: internal capture preset REMOTE_SUBMIX + shell Context;
        // Attempt 2: standard AudioRecord.Builder with REMOTE_SUBMIX + shell Context.
        AudioRecord rec = tryBuildAudioRecord(af, bufSize, true);
        if (rec != null) return rec;
        return tryBuildAudioRecord(af, bufSize, false);
    }

    private AudioRecord tryBuildAudioRecord(AudioFormat af, int bufSize, boolean useInternalPreset) {
        try {
            AudioRecord.Builder recB = new AudioRecord.Builder()
                    .setAudioFormat(af)
                    .setBufferSizeInBytes(bufSize);

            if (useInternalPreset) {
                Method setInternal;
                try {
                    setInternal = AudioAttributes.Builder.class.getMethod("setInternalCapturePreset", int.class);
                } catch (NoSuchMethodException ignored) {
                    return null;
                }
                AudioAttributes.Builder attrB = new AudioAttributes.Builder();
                setInternal.invoke(attrB, MediaRecorder.AudioSource.REMOTE_SUBMIX);
                try {
                    AudioRecord.Builder.class.getMethod("setAudioAttributes", AudioAttributes.class)
                            .invoke(recB, attrB.build());
                } catch (NoSuchMethodException ignored) {
                    recB.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    recB.setPrivacySensitive(false);
                }
            } else {
                recB.setAudioSource(MediaRecorder.AudioSource.REMOTE_SUBMIX);
            }

            applyShellContext(recB);

            AudioRecord r = recB.build();
            if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                return r;
            }
            r.release();
        } catch (Throwable t) {
            System.err.println("OPALDESK_AUDIO_RECORD_ATTEMPT_WARN internalPreset="
                    + useInternalPreset + ": " + t.getMessage());
        }
        return null;
    }

    private void applyShellContext(AudioRecord.Builder recB) {
        if (ctx == null || Build.VERSION.SDK_INT < 31) return;
        try {
            AudioRecord.Builder.class.getMethod("setContext", Context.class).invoke(recB, ctx);
        } catch (NoSuchMethodException ignored) {
            // Optional hidden API.
        } catch (ReflectiveOperationException e) {
            System.err.println("OPALDESK_AUDIO_SET_CTX_WARN: " + e.getMessage());
        }
    }

    private void captureLoop(AudioRecord rec, MediaCodec enc) {
        // 1024 stereo 16-bit PCM samples per AAC-LC frame = 4096 bytes (~21.3 ms)
        final int chunkBytes = 1024 * CHANNELS * 2;
        byte[] pcm = new byte[chunkBytes];
        int consecutiveErrors = 0;

        while (running) {
            try {
                int read = rec.read(pcm, 0, pcm.length);
                if (read < 0) {
                    consecutiveErrors++;
                    if (consecutiveErrors >= 5) {
                        System.err.println("OPALDESK_AUDIO_READ_FAILED err=" + read + "; disabling audio");
                        failAndDisableAsync();
                        return;
                    }
                    SystemClock.sleep(20);
                    continue;
                }
                consecutiveErrors = 0;
                if (read == 0) continue;

                int inIdx = enc.dequeueInputBuffer(20_000L);
                if (inIdx >= 0) {
                    ByteBuffer inBuf = enc.getInputBuffer(inIdx);
                    if (inBuf != null) {
                        inBuf.clear();
                        int toCopy = Math.min(read, inBuf.remaining());
                        inBuf.put(pcm, 0, toCopy);
                        long ptsUs = SystemClock.elapsedRealtimeNanos() / 1000L;
                        enc.queueInputBuffer(inIdx, 0, toCopy, ptsUs, 0);
                    }
                }
            } catch (IllegalStateException ise) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_CAPTURE_STATE_ERR: " + ise.getMessage());
                    failAndDisableAsync();
                }
                return;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_CAPTURE_ERR: " + t.getMessage());
                    failAndDisableAsync();
                }
                return;
            }
        }
    }

    private void drainLoop(MediaCodec enc) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean configSent = false;

        while (running) {
            try {
                int outIdx = enc.dequeueOutputBuffer(info, 25_000L);
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = enc.getOutputFormat();
                    byte[] asc = extractAsc(outFmt);
                    emitAudioConfig(asc, SystemClock.elapsedRealtimeNanos() / 1000L);
                    configSent = true;
                    continue;
                }
                if (outIdx < 0) continue;

                try {
                    ByteBuffer outBuf = enc.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        outBuf.get(data);
                        long ptsUs = (info.presentationTimeUs > 0)
                                ? info.presentationTimeUs
                                : (SystemClock.elapsedRealtimeNanos() / 1000L);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            emitAudioConfig(data, ptsUs);
                            configSent = true;
                        } else {
                            if (!configSent) {
                                emitAudioConfig(FALLBACK_AAC_LC_48K_STEREO_ASC, ptsUs);
                                configSent = true;
                            }
                            if (listener != null && running) {
                                listener.onAudioFrame(data, ptsUs);
                            }
                        }
                    }
                } finally {
                    enc.releaseOutputBuffer(outIdx, false);
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (running) {
                        System.err.println("OPALDESK_AUDIO_DRAIN_UNEXPECTED_EOS; disabling audio");
                        failAndDisableAsync();
                    }
                    break;
                }
            } catch (IllegalStateException ise) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_DRAIN_STATE_ERR: " + ise.getMessage());
                    failAndDisableAsync();
                }
                break;
            } catch (Throwable t) {
                if (running) {
                    System.err.println("OPALDESK_AUDIO_DRAIN_ERR: " + t);
                    failAndDisableAsync();
                }
                break;
            }
        }
    }

    private byte[] extractAsc(MediaFormat fmt) {
        if (fmt != null && fmt.containsKey("csd-0")) {
            ByteBuffer csd0 = fmt.getByteBuffer("csd-0");
            if (csd0 != null && csd0.remaining() > 0) {
                ByteBuffer dup = csd0.duplicate();
                byte[] out = new byte[dup.remaining()];
                dup.get(out);
                return out;
            }
        }
        return FALLBACK_AAC_LC_48K_STEREO_ASC;
    }

    private void emitAudioConfig(byte[] asc, long ptsUs) {
        if (listener == null || !running) return;
        OpalCastProtocol.AudioConfig cfg = new OpalCastProtocol.AudioConfig(
                OpalCastProtocol.AUDIO_CODEC_AAC_LC,
                SAMPLE_RATE,
                CHANNELS,
                (asc != null && asc.length > 0) ? asc : FALLBACK_AAC_LC_48K_STEREO_ASC);
        listener.onAudioConfig(cfg, ptsUs);
    }

    private void failAndDisableAsync() {
        this.running = false;
        Thread t = new Thread(this::stop, "ExtAudioStop");
        t.setDaemon(true);
        t.start();
    }

    private void notifyState(boolean isActive) {
        if (listener != null) {
            try {
                listener.onAudioStateChanged(isActive);
            } catch (Throwable t) {
                System.err.println("OPALDESK_AUDIO_STATE_CALLBACK_ERR: " + t.getMessage());
            }
        }
    }
}
