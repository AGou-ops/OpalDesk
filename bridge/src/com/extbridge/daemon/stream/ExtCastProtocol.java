package com.extbridge.daemon.stream;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ExtCast v1.1 binary framing and payload codecs (Big-Endian network byte order).
 * Byte-for-byte compatible with ExtBridgeMac (Swift ExtCastFrame).
 */
public final class ExtCastProtocol {
    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_SIZE = 18;
    public static final int MAGIC = 0x45584231; // "EXB1"
    public static final byte MAGIC_0 = 0x45; // 'E'
    public static final byte MAGIC_1 = 0x58; // 'X'
    public static final byte MAGIC_2 = 0x42; // 'B'
    public static final byte MAGIC_3 = 0x31; // '1'
    public static final int MAX_PAYLOAD_LEN = 16 * 1024 * 1024; // 16 MiB OOM guard
    public static final int MAX_TOUCH_POINTERS = 16;
    public static final int MAX_CLIPBOARD_IMAGE_BYTES = 8 * 1024 * 1024; // 8 MiB PNG clipboard cap

    // Downlink frame types (Android -> Mac)
    public static final int TYPE_VIDEO_CONFIG = 0x01;
    public static final int TYPE_VIDEO_FRAME = 0x02;
    public static final int TYPE_AUDIO_CONFIG = 0x03;
    public static final int TYPE_AUDIO_FRAME = 0x04;
    public static final int TYPE_DEVICE_STATE = 0x10;
    public static final int TYPE_CLIPBOARD_PUSH = 0x11;
    public static final int TYPE_AUTH_RESULT = 0x12;
    public static final int TYPE_FILE_TRANSFER_ACK = 0x13;
    public static final int TYPE_WIFI_UPGRADE_READY = 0x14;
    public static final int TYPE_APP_LIST = 0x15;
    public static final int TYPE_PONG = 0x1F;

    // Uplink frame types (Mac -> Android)
    public static final int TYPE_HELLO = 0x20;
    public static final int TYPE_RESIZE_CONFIG = 0x21;
    public static final int TYPE_REQUEST_IDR = 0x22;
    public static final int TYPE_INPUT_MOUSE = 0x23;
    public static final int TYPE_INPUT_TOUCH = 0x24;
    public static final int TYPE_INPUT_KEY = 0x25;
    public static final int TYPE_INPUT_TEXT = 0x26;
    public static final int TYPE_SYSTEM_ACTION = 0x27;
    public static final int TYPE_FILE_TRANSFER_CHUNK = 0x28;
    public static final int TYPE_REQUEST_WIFI_UPGRADE = 0x29;
    public static final int TYPE_LIST_APPS = 0x2A;
    public static final int TYPE_WINDOW_ACTION = 0x2B;
    public static final int TYPE_PING = 0x2F;

    // Video codec IDs
    public static final int VIDEO_CODEC_H264 = 1;
    public static final int VIDEO_CODEC_HEVC = 2;

    // Video frame flags
    public static final int FLAG_VIDEO_KEYFRAME = 0x01;
    public static final int FLAG_VIDEO_CONFIG_INLINE = 0x02;

    // Clipboard / INPUT_TEXT flags
    public static final int FLAG_CLIPBOARD_IMAGE_PNG = 0x01;

    // Session roles & transport hints (ExtCast v1.1 HELLO)
    public static final String SESSION_ROLE_DESKTOP = "desktop";
    public static final String SESSION_ROLE_STANDALONE_APP = "standalone_app";
    public static final String TRANSPORT_HINT_USB = "usb";
    public static final String TRANSPORT_HINT_WIFI_ADB = "wifi_adb";
    public static final String TRANSPORT_HINT_WIFI_TLS = "wifi_tls";

    // Audio codec IDs
    public static final int AUDIO_CODEC_AAC_LC = 1;
    public static final int AUDIO_CODEC_OPUS = 2;
    public static final int AUDIO_CODEC_PCM_16 = 3;

    // Display modes
    public static final int DISPLAY_MODE_DEX = 0;
    public static final int DISPLAY_MODE_MIRROR = 1;
    public static final int DISPLAY_MODE_UNCHANGED = 0xFF;
    public static final int ROTATION_UNCHANGED = 0xFF;

    // Mouse actions
    public static final int MOUSE_MOVE_ABS = 0;
    public static final int MOUSE_BUTTON_DOWN = 1;
    public static final int MOUSE_BUTTON_UP = 2;
    public static final int MOUSE_SCROLL = 3;
    public static final int MOUSE_HOVER_EXIT = 4;

    // Mouse button masks (1:1 with Android MotionEvent button masks)
    public static final int BUTTON_PRIMARY = 1;
    public static final int BUTTON_SECONDARY = 2;
    public static final int BUTTON_TERTIARY = 4;
    public static final int BUTTON_BACK = 8;
    public static final int BUTTON_FORWARD = 16;

    // Touch actions
    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_CANCEL = 3;
    public static final int TOUCH_POINTER_DOWN = 5;
    public static final int TOUCH_POINTER_UP = 6;

    // Key actions
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;

    // Text input modes
    public static final int TEXT_MODE_COMMIT_PASTE = 0;
    public static final int TEXT_MODE_SET_CLIPBOARD_ONLY = 1;

    // System actions
    public static final int SYS_HOME = 1;
    public static final int SYS_BACK = 2;
    public static final int SYS_RECENTS = 3;
    public static final int SYS_TOGGLE_DISPLAY_MODE = 4;
    public static final int SYS_TOGGLE_BLACKOUT = 5;
    public static final int SYS_ROTATE_CW = 6;
    public static final int SYS_SET_AUDIO_ENABLED = 7;
    public static final int SYS_DISCONNECT = 8;

    // File transfer chunk flags & ACK statuses
    public static final int FILE_FLAG_START = 0x01;
    public static final int FILE_FLAG_DATA = 0x02;
    public static final int FILE_FLAG_END = 0x04;
    public static final int FILE_FLAG_ABORT = 0x08;
    public static final int FILE_ACK_OK = 0;
    public static final int FILE_ACK_ERR = 1;

    public static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private ExtCastProtocol() {}

    public static boolean isValidPngHeader(byte[] data) {
        if (data == null || data.length < PNG_MAGIC.length) return false;
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (data[i] != PNG_MAGIC[i]) return false;
        }
        return true;
    }

    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    public static final class Frame {
        public final int type;
        public final int flags;
        public final long timestampUs;
        public final byte[] payload;

        public Frame(int type, int flags, long timestampUs, byte[] payload) {
            this.type = type & 0xFF;
            this.flags = flags & 0xFF;
            this.timestampUs = timestampUs;
            this.payload = (payload != null) ? payload : EMPTY_BYTES;
        }
    }

    // ---- Low-level 18-byte BE frame I/O ----

    public static void writeFrame(OutputStream out, Frame frame) throws IOException {
        writeFrame(out, frame.type, frame.flags, frame.timestampUs, frame.payload, 0, frame.payload.length);
    }

    public static void writeFrame(
            OutputStream out,
            int type,
            int flags,
            long timestampUs,
            byte[] payload,
            int offset,
            int length) throws IOException {
        if (length < 0 || length > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Invalid payload length: " + length);
        }
        if (length > 0 && (payload == null || offset < 0 || offset + length > payload.length)) {
            throw new ProtocolException("Payload bounds out of range");
        }
        byte[] header = new byte[HEADER_SIZE];
        encodeHeader(header, type, flags, length, timestampUs);
        out.write(header, 0, HEADER_SIZE);
        if (length > 0) {
            out.write(payload, offset, length);
        }
    }

    public static void encodeHeader(byte[] header, int type, int flags, int payloadLen, long timestampUs) {
        header[0] = MAGIC_0;
        header[1] = MAGIC_1;
        header[2] = MAGIC_2;
        header[3] = MAGIC_3;
        header[4] = (byte) (type & 0xFF);
        header[5] = (byte) (flags & 0xFF);
        header[6] = (byte) ((payloadLen >>> 24) & 0xFF);
        header[7] = (byte) ((payloadLen >>> 16) & 0xFF);
        header[8] = (byte) ((payloadLen >>> 8) & 0xFF);
        header[9] = (byte) (payloadLen & 0xFF);
        header[10] = (byte) ((timestampUs >>> 56) & 0xFF);
        header[11] = (byte) ((timestampUs >>> 48) & 0xFF);
        header[12] = (byte) ((timestampUs >>> 40) & 0xFF);
        header[13] = (byte) ((timestampUs >>> 32) & 0xFF);
        header[14] = (byte) ((timestampUs >>> 24) & 0xFF);
        header[15] = (byte) ((timestampUs >>> 16) & 0xFF);
        header[16] = (byte) ((timestampUs >>> 8) & 0xFF);
        header[17] = (byte) (timestampUs & 0xFF);
    }

    /**
     * Reads one ExtCast v1 frame from {@code in}. Returns {@code null} on clean EOF
     * before the first byte of a header; throws {@link EOFException} on mid-frame EOF;
     * throws {@link ProtocolException} on invalid magic or payloadLen > 16 MiB.
     */
    public static Frame readFrame(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_SIZE];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(in, header, 1, HEADER_SIZE - 1);

        if (header[0] != MAGIC_0 || header[1] != MAGIC_1
                || header[2] != MAGIC_2 || header[3] != MAGIC_3) {
            throw new ProtocolException(String.format(
                    "Invalid ExtCast magic: %02X %02X %02X %02X",
                    header[0] & 0xFF, header[1] & 0xFF, header[2] & 0xFF, header[3] & 0xFF));
        }

        int type = header[4] & 0xFF;
        int flags = header[5] & 0xFF;
        long payloadLenUnsigned =
                ((long) (header[6] & 0xFF) << 24)
                | ((long) (header[7] & 0xFF) << 16)
                | ((long) (header[8] & 0xFF) << 8)
                | ((long) (header[9] & 0xFF));
        if (payloadLenUnsigned > MAX_PAYLOAD_LEN) {
            throw new ProtocolException("Payload exceeds 16 MiB limit: " + payloadLenUnsigned);
        }
        int payloadLen = (int) payloadLenUnsigned;

        long timestampUs =
                ((long) (header[10] & 0xFF) << 56)
                | ((long) (header[11] & 0xFF) << 48)
                | ((long) (header[12] & 0xFF) << 40)
                | ((long) (header[13] & 0xFF) << 32)
                | ((long) (header[14] & 0xFF) << 24)
                | ((long) (header[15] & 0xFF) << 16)
                | ((long) (header[16] & 0xFF) << 8)
                | ((long) (header[17] & 0xFF));

        byte[] payload = (payloadLen == 0) ? EMPTY_BYTES : new byte[payloadLen];
        if (payloadLen > 0) {
            readFully(in, payload, 0, payloadLen);
        }
        return new Frame(type, flags, timestampUs, payload);
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int remaining = len;
        int pos = off;
        while (remaining > 0) {
            int n = in.read(buf, pos, remaining);
            if (n < 0) {
                throw new EOFException("Unexpected EOF while reading ExtCast frame (" + remaining + " bytes remaining)");
            }
            pos += n;
            remaining -= n;
        }
    }

    // ---- Downlink payload structures & encoders/decoders ----

    public static final class VideoConfig {
        public final int codec;
        public final int width;
        public final int height;
        public final int dpi;
        public final int rotation;
        public final int fpsX100;
        public final int displayMode;
        public final byte[] csdAnnexB;

        public VideoConfig(
                int codec, int width, int height, int dpi,
                int rotation, int fpsX100, int displayMode, byte[] csdAnnexB) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.rotation = rotation;
            this.fpsX100 = fpsX100;
            this.displayMode = displayMode;
            this.csdAnnexB = (csdAnnexB != null) ? csdAnnexB : EMPTY_BYTES;
        }
    }

    public static byte[] encodeVideoConfig(VideoConfig cfg) {
        byte[] csd = (cfg.csdAnnexB != null) ? cfg.csdAnnexB : EMPTY_BYTES;
        ByteBuffer bb = ByteBuffer.allocate(11 + csd.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (cfg.codec & 0xFF));
        bb.putShort((short) (cfg.width & 0xFFFF));
        bb.putShort((short) (cfg.height & 0xFFFF));
        bb.putShort((short) (cfg.dpi & 0xFFFF));
        bb.put((byte) (cfg.rotation & 0xFF));
        bb.putShort((short) (cfg.fpsX100 & 0xFFFF));
        bb.put((byte) (cfg.displayMode & 0xFF));
        if (csd.length > 0) bb.put(csd);
        return bb.array();
    }

    public static VideoConfig decodeVideoConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 11) {
            throw new ProtocolException("VIDEO_CONFIG payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int codec = bb.get() & 0xFF;
        int width = bb.getShort() & 0xFFFF;
        int height = bb.getShort() & 0xFFFF;
        int dpi = bb.getShort() & 0xFFFF;
        int rotation = bb.get() & 0xFF;
        int fpsX100 = bb.getShort() & 0xFFFF;
        int displayMode = bb.get() & 0xFF;
        byte[] csd = new byte[bb.remaining()];
        bb.get(csd);
        return new VideoConfig(codec, width, height, dpi, rotation, fpsX100, displayMode, csd);
    }

    public static final class AudioConfig {
        public final int codec;
        public final int sampleRate;
        public final int channels;
        public final byte[] audioSpecificConfig;

        public AudioConfig(int codec, int sampleRate, int channels, byte[] audioSpecificConfig) {
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.audioSpecificConfig = (audioSpecificConfig != null) ? audioSpecificConfig : EMPTY_BYTES;
        }
    }

    public static byte[] encodeAudioConfig(AudioConfig cfg) {
        byte[] asc = (cfg.audioSpecificConfig != null) ? cfg.audioSpecificConfig : EMPTY_BYTES;
        ByteBuffer bb = ByteBuffer.allocate(6 + asc.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (cfg.codec & 0xFF));
        bb.putInt(cfg.sampleRate);
        bb.put((byte) (cfg.channels & 0xFF));
        if (asc.length > 0) bb.put(asc);
        return bb.array();
    }

    public static AudioConfig decodeAudioConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 6) {
            throw new ProtocolException("AUDIO_CONFIG payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int codec = bb.get() & 0xFF;
        int sampleRate = bb.getInt();
        int channels = bb.get() & 0xFF;
        byte[] asc = new byte[bb.remaining()];
        bb.get(asc);
        return new AudioConfig(codec, sampleRate, channels, asc);
    }

    public static final class DeviceState {
        public final String deviceName;
        public final int sinkType;
        public final int displayMode;
        public final int width;
        public final int height;
        public final int dpi;
        public final double fps;
        public final int rotation;
        public final int bitrateKbps;
        public final int batteryPct;
        public final boolean blackout;
        public final boolean audioEnabled;
        // ExtCast v1.1 optional fields
        public final int injectStatus; // 1=INJECT_EVENTS, 2=UHID, -1=denied
        public final boolean adaptiveSingleEnabled;
        public final int defaultSingleWidthDp;
        public final String activeLayoutMode;
        public final String topAppPackage;
        public final String topAppLabel;

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled) {
            this(deviceName, sinkType, displayMode, width, height, dpi, fps, rotation,
                    bitrateKbps, batteryPct, blackout, audioEnabled,
                    1, true, 460, "FULLSCREEN", "", "");
        }

        public DeviceState(
                String deviceName, int sinkType, int displayMode,
                int width, int height, int dpi, double fps, int rotation,
                int bitrateKbps, int batteryPct, boolean blackout, boolean audioEnabled,
                int injectStatus, boolean adaptiveSingleEnabled, int defaultSingleWidthDp,
                String activeLayoutMode, String topAppPackage, String topAppLabel) {
            this.deviceName = (deviceName != null) ? deviceName : "Android";
            this.sinkType = sinkType;
            this.displayMode = displayMode;
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.rotation = rotation;
            this.bitrateKbps = bitrateKbps;
            this.batteryPct = batteryPct;
            this.blackout = blackout;
            this.audioEnabled = audioEnabled;
            this.injectStatus = injectStatus;
            this.adaptiveSingleEnabled = adaptiveSingleEnabled;
            this.defaultSingleWidthDp = Math.max(320, Math.min(1200, defaultSingleWidthDp));
            this.activeLayoutMode = (activeLayoutMode != null && !activeLayoutMode.isEmpty())
                    ? activeLayoutMode : "FULLSCREEN";
            this.topAppPackage = (topAppPackage != null) ? topAppPackage : "";
            this.topAppLabel = (topAppLabel != null) ? topAppLabel : "";
        }
    }

    public static byte[] encodeDeviceState(DeviceState s) {
        String json = new SimpleJson.ObjectBuilder()
                .put("deviceName", s.deviceName)
                .put("sinkType", s.sinkType)
                .put("displayMode", s.displayMode)
                .put("width", s.width)
                .put("height", s.height)
                .put("dpi", s.dpi)
                .put("fps", s.fps)
                .put("rotation", s.rotation)
                .put("bitrateKbps", s.bitrateKbps)
                .put("batteryPct", s.batteryPct)
                .put("blackout", s.blackout)
                .put("audioEnabled", s.audioEnabled)
                .put("injectStatus", s.injectStatus)
                .put("adaptiveSingleEnabled", s.adaptiveSingleEnabled)
                .put("defaultSingleWidthDp", s.defaultSingleWidthDp)
                .put("activeLayoutMode", s.activeLayoutMode)
                .put("topAppPackage", s.topAppPackage)
                .put("topAppLabel", s.topAppLabel)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static DeviceState decodeDeviceState(byte[] payload) throws ProtocolException {
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new DeviceState(
                    obj.getString("deviceName", "Android"),
                    obj.getInt("sinkType", 0),
                    obj.getInt("displayMode", 0),
                    obj.getInt("width", 1920),
                    obj.getInt("height", 1080),
                    obj.getInt("dpi", 240),
                    obj.getDouble("fps", 60.0),
                    obj.getInt("rotation", 0),
                    obj.getInt("bitrateKbps", 12000),
                    obj.getInt("batteryPct", -1),
                    obj.getBoolean("blackout", false),
                    obj.getBoolean("audioEnabled", false),
                    obj.getInt("injectStatus", 1),
                    obj.getBoolean("adaptiveSingleEnabled", true),
                    obj.getInt("defaultSingleWidthDp", 460),
                    obj.getString("activeLayoutMode", "FULLSCREEN"),
                    obj.getString("topAppPackage", ""),
                    obj.getString("topAppLabel", ""));
        } catch (Throwable t) {
            throw new ProtocolException("Invalid DEVICE_STATE JSON: " + t.getMessage());
        }
    }

    public static final class ClipboardPush {
        public final long seq;
        public final String text;

        public ClipboardPush(long seq, String text) {
            this.seq = seq;
            this.text = (text != null) ? text : "";
        }
    }

    public static byte[] encodeClipboardPush(long seq, String text) {
        byte[] utf8 = (text != null ? text : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(8 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        bb.put(utf8);
        return bb.array();
    }

    public static ClipboardPush decodeClipboardPush(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("CLIPBOARD_PUSH payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long seq = bb.getLong();
        String text = new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
        return new ClipboardPush(seq, text);
    }

    public static final class ClipboardImagePush {
        public final long seq;
        public final byte[] pngData;

        public ClipboardImagePush(long seq, byte[] pngData) {
            this.seq = seq;
            this.pngData = (pngData != null) ? pngData : EMPTY_BYTES;
        }
    }

    public static byte[] encodeClipboardImagePush(long seq, byte[] pngData) throws ProtocolException {
        byte[] data = (pngData != null) ? pngData : EMPTY_BYTES;
        if (data.length > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Clipboard PNG exceeds 8 MiB limit: " + data.length);
        }
        ByteBuffer bb = ByteBuffer.allocate(8 + data.length).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(seq);
        if (data.length > 0) bb.put(data);
        return bb.array();
    }

    public static ClipboardImagePush decodeClipboardImagePush(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("CLIPBOARD_PUSH image payload too short");
        }
        int imgLen = payload.length - 8;
        if (imgLen > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Clipboard PNG exceeds 8 MiB limit: " + imgLen);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long seq = bb.getLong();
        byte[] data = new byte[imgLen];
        if (imgLen > 0) {
            bb.get(data);
        }
        return new ClipboardImagePush(seq, data);
    }

    public static final class AuthResultMsg {
        public final boolean ok;
        public final String authToken;
        public final String message;
        public final String deviceName;
        public final String certFingerprint;

        public AuthResultMsg(boolean ok, String authToken, String message, String deviceName, String certFingerprint) {
            this.ok = ok;
            this.authToken = (authToken != null) ? authToken : "";
            this.message = (message != null) ? message : "";
            this.deviceName = (deviceName != null) ? deviceName : "";
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
        }
    }

    public static byte[] encodeAuthResult(AuthResultMsg msg) {
        String json = new SimpleJson.ObjectBuilder()
                .put("ok", msg.ok)
                .put("authToken", msg.authToken)
                .put("message", msg.message)
                .put("deviceName", msg.deviceName)
                .put("certFingerprint", msg.certFingerprint)
                .build();
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + utf8.length];
        out[0] = (byte) (msg.ok ? 1 : 0);
        System.arraycopy(utf8, 0, out, 1, utf8.length);
        return out;
    }

    public static AuthResultMsg decodeAuthResult(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 3) {
            throw new ProtocolException("AUTH_RESULT payload too short");
        }
        boolean okByte = (payload[0] & 0xFF) != 0;
        try {
            String json = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(json);
            return new AuthResultMsg(
                    okByte && obj.getBoolean("ok", okByte),
                    obj.getString("authToken", ""),
                    obj.getString("message", ""),
                    obj.getString("deviceName", ""),
                    obj.getString("certFingerprint", ""));
        } catch (Throwable t) {
            throw new ProtocolException("Invalid AUTH_RESULT JSON: " + t.getMessage());
        }
    }

    public static final class FileTransferAck {
        public final int transferId;
        public final int status; // 0=ok, 1=err
        public final String pathOrError;

        public FileTransferAck(int transferId, int status, String pathOrError) {
            this.transferId = transferId;
            this.status = status;
            this.pathOrError = (pathOrError != null) ? pathOrError : "";
        }
    }

    public static byte[] encodeFileTransferAck(int transferId, int status, String pathOrError) {
        byte[] utf8 = (pathOrError != null ? pathOrError : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(5 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (status & 0xFF));
        bb.put(utf8);
        return bb.array();
    }

    public static FileTransferAck decodeFileTransferAck(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("FILE_TRANSFER_ACK payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int status = bb.get() & 0xFF;
        String pathOrErr = new String(payload, 5, payload.length - 5, StandardCharsets.UTF_8);
        return new FileTransferAck(transferId, status, pathOrErr);
    }

    public static byte[] encodePingPong(long pingId) {
        ByteBuffer bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        bb.putLong(pingId);
        return bb.array();
    }

    public static long decodePingPong(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("PING/PONG payload must be 8 bytes");
        }
        return ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getLong();
    }

    // ---- Uplink payload structures & encoders/decoders ----

    public static final class HelloMsg {
        public final int protocolVersion;
        public final String clientName;
        public final String clientId;
        public final String preferredCodec;
        public final int width;
        public final int height;
        public final int dpi;
        public final int fps;
        public final int bitrateKbps;
        public final int displayMode;
        public final boolean audioEnabled;
        public final String authToken;
        public final String pinCode;
        // ExtCast v1.1 optional fields
        public final String transportHint; // "usb" | "wifi_adb" | "wifi_tls" | ""
        public final String sessionRole;   // "desktop" | "standalone_app"
        public final String appPackage;
        public final String appActivity;

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode) {
            this(protocolVersion, clientName, clientId, preferredCodec,
                    width, height, dpi, fps, bitrateKbps, displayMode, audioEnabled,
                    authToken, pinCode, "", SESSION_ROLE_DESKTOP, "", "");
        }

        public HelloMsg(
                int protocolVersion, String clientName, String clientId, String preferredCodec,
                int width, int height, int dpi, int fps, int bitrateKbps,
                int displayMode, boolean audioEnabled, String authToken, String pinCode,
                String transportHint, String sessionRole, String appPackage, String appActivity) {
            this.protocolVersion = protocolVersion;
            this.clientName = (clientName != null) ? clientName : "Mac";
            this.clientId = (clientId != null) ? clientId : "";
            this.preferredCodec = (preferredCodec != null) ? preferredCodec : "hevc";
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fps = fps;
            this.bitrateKbps = bitrateKbps;
            this.displayMode = displayMode;
            this.audioEnabled = audioEnabled;
            this.authToken = (authToken != null) ? authToken : "";
            this.pinCode = (pinCode != null) ? pinCode : "";
            this.transportHint = (transportHint != null) ? transportHint.trim() : "";
            String role = (sessionRole != null) ? sessionRole.trim() : "";
            this.sessionRole = role.isEmpty() ? SESSION_ROLE_DESKTOP : role;
            this.appPackage = (appPackage != null) ? appPackage.trim() : "";
            this.appActivity = (appActivity != null) ? appActivity.trim() : "";
        }

        public boolean isStandaloneApp() {
            return SESSION_ROLE_STANDALONE_APP.equalsIgnoreCase(sessionRole);
        }
    }

    public static byte[] encodeHello(HelloMsg msg) {
        String json = new SimpleJson.ObjectBuilder()
                .put("protocolVersion", msg.protocolVersion)
                .put("clientName", msg.clientName)
                .put("clientId", msg.clientId)
                .put("preferredCodec", msg.preferredCodec)
                .put("width", msg.width)
                .put("height", msg.height)
                .put("dpi", msg.dpi)
                .put("fps", msg.fps)
                .put("bitrateKbps", msg.bitrateKbps)
                .put("displayMode", msg.displayMode)
                .put("audioEnabled", msg.audioEnabled)
                .put("authToken", msg.authToken)
                .put("pinCode", msg.pinCode)
                .put("transportHint", msg.transportHint)
                .put("sessionRole", msg.sessionRole)
                .put("appPackage", msg.appPackage)
                .put("appActivity", msg.appActivity)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static HelloMsg decodeHello(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("HELLO payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new HelloMsg(
                    obj.getInt("protocolVersion", 0),
                    obj.getString("clientName", "Mac"),
                    obj.getString("clientId", ""),
                    obj.getString("preferredCodec", "hevc"),
                    obj.getInt("width", 1920),
                    obj.getInt("height", 1080),
                    obj.getInt("dpi", 240),
                    obj.getInt("fps", 60),
                    obj.getInt("bitrateKbps", 12000),
                    obj.getInt("displayMode", DISPLAY_MODE_DEX),
                    obj.getBoolean("audioEnabled", true),
                    obj.getString("authToken", ""),
                    obj.getString("pinCode", ""),
                    obj.getString("transportHint", ""),
                    obj.getString("sessionRole", SESSION_ROLE_DESKTOP),
                    obj.getString("appPackage", ""),
                    obj.getString("appActivity", ""));
        } catch (Throwable t) {
            throw new ProtocolException("Invalid HELLO JSON: " + t.getMessage());
        }
    }

    public static final class ResizeConfig {
        public final int width;
        public final int height;
        public final int dpi;
        public final int fpsX100;
        public final int bitrateKbps;
        public final int displayMode; // 0=DEX, 1=MIRROR, 0xFF=unchanged
        public final int rotation;    // 0..3, 0xFF=unchanged

        public ResizeConfig(
                int width, int height, int dpi, int fpsX100,
                int bitrateKbps, int displayMode, int rotation) {
            this.width = width;
            this.height = height;
            this.dpi = dpi;
            this.fpsX100 = fpsX100;
            this.bitrateKbps = bitrateKbps;
            this.displayMode = displayMode & 0xFF;
            this.rotation = rotation & 0xFF;
        }
    }

    public static byte[] encodeResizeConfig(ResizeConfig cfg) {
        ByteBuffer bb = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
        bb.putShort((short) (cfg.width & 0xFFFF));
        bb.putShort((short) (cfg.height & 0xFFFF));
        bb.putShort((short) (cfg.dpi & 0xFFFF));
        bb.putShort((short) (cfg.fpsX100 & 0xFFFF));
        bb.putInt(cfg.bitrateKbps);
        bb.put((byte) (cfg.displayMode & 0xFF));
        bb.put((byte) (cfg.rotation & 0xFF));
        return bb.array();
    }

    public static ResizeConfig decodeResizeConfig(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 14) {
            throw new ProtocolException("RESIZE_CONFIG payload must be 14 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int w = bb.getShort() & 0xFFFF;
        int h = bb.getShort() & 0xFFFF;
        int dpi = bb.getShort() & 0xFFFF;
        int fpsX100 = bb.getShort() & 0xFFFF;
        int bitrateKbps = bb.getInt();
        int displayMode = bb.get() & 0xFF;
        int rotation = bb.get() & 0xFF;
        return new ResizeConfig(w, h, dpi, fpsX100, bitrateKbps, displayMode, rotation);
    }

    public static final class MouseEventMsg {
        public final int action;
        public final float x;
        public final float y;
        public final int buttons;
        public final int actionButton;
        public final float hScroll;
        public final float vScroll;

        public MouseEventMsg(
                int action, float x, float y, int buttons,
                int actionButton, float hScroll, float vScroll) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.buttons = buttons;
            this.actionButton = actionButton;
            this.hScroll = hScroll;
            this.vScroll = vScroll;
        }
    }

    public static byte[] encodeMouseEvent(MouseEventMsg msg) {
        ByteBuffer bb = ByteBuffer.allocate(25).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.action & 0xFF));
        bb.putFloat(msg.x);
        bb.putFloat(msg.y);
        bb.putInt(msg.buttons);
        bb.putInt(msg.actionButton);
        bb.putFloat(msg.hScroll);
        bb.putFloat(msg.vScroll);
        return bb.array();
    }

    public static MouseEventMsg decodeMouseEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 25) {
            throw new ProtocolException("INPUT_MOUSE payload must be 25 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        float x = bb.getFloat();
        float y = bb.getFloat();
        int buttons = bb.getInt();
        int actionButton = bb.getInt();
        float hScroll = bb.getFloat();
        float vScroll = bb.getFloat();
        return new MouseEventMsg(action, x, y, buttons, actionButton, hScroll, vScroll);
    }

    public static final class TouchPointer {
        public final int pointerId;
        public final float x;
        public final float y;
        public final float pressure;

        public TouchPointer(int pointerId, float x, float y, float pressure) {
            this.pointerId = pointerId;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
        }
    }

    public static final class TouchEventMsg {
        public final int action;
        public final int actionIndex;
        public final TouchPointer[] pointers;

        public TouchEventMsg(int action, int actionIndex, TouchPointer[] pointers) {
            this.action = action;
            this.actionIndex = actionIndex;
            this.pointers = (pointers != null) ? pointers : new TouchPointer[0];
        }
    }

    public static byte[] encodeTouchEvent(TouchEventMsg msg) throws ProtocolException {
        int count = (msg.pointers != null) ? msg.pointers.length : 0;
        if (count > MAX_TOUCH_POINTERS) {
            throw new ProtocolException("Too many touch pointers: " + count);
        }
        ByteBuffer bb = ByteBuffer.allocate(3 + count * 16).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.action & 0xFF));
        bb.put((byte) (msg.actionIndex & 0xFF));
        bb.put((byte) (count & 0xFF));
        for (int i = 0; i < count; i++) {
            TouchPointer p = msg.pointers[i];
            bb.putInt(p.pointerId);
            bb.putFloat(p.x);
            bb.putFloat(p.y);
            bb.putFloat(p.pressure);
        }
        return bb.array();
    }

    public static TouchEventMsg decodeTouchEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 3) {
            throw new ProtocolException("INPUT_TOUCH payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int actionIndex = bb.get() & 0xFF;
        int count = bb.get() & 0xFF;
        if (count > MAX_TOUCH_POINTERS) {
            throw new ProtocolException("Excessive touch pointerCount: " + count);
        }
        if (payload.length < 3 + count * 16) {
            throw new ProtocolException("INPUT_TOUCH payload truncated for pointerCount=" + count);
        }
        TouchPointer[] pts = new TouchPointer[count];
        for (int i = 0; i < count; i++) {
            int pid = bb.getInt();
            float x = bb.getFloat();
            float y = bb.getFloat();
            float pressure = bb.getFloat();
            pts[i] = new TouchPointer(pid, x, y, pressure);
        }
        return new TouchEventMsg(action, actionIndex, pts);
    }

    public static final class KeyEventMsg {
        public final int action; // 0=DOWN, 1=UP
        public final int androidKeyCode;
        public final int metaState;
        public final int repeatCount;

        public KeyEventMsg(int action, int androidKeyCode, int metaState, int repeatCount) {
            this.action = action;
            this.androidKeyCode = androidKeyCode;
            this.metaState = metaState;
            this.repeatCount = repeatCount;
        }
    }

    public static byte[] encodeKeyEvent(KeyEventMsg msg) {
        ByteBuffer bb = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.action & 0xFF));
        bb.putInt(msg.androidKeyCode);
        bb.putInt(msg.metaState);
        bb.putInt(msg.repeatCount);
        return bb.array();
    }

    public static KeyEventMsg decodeKeyEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 13) {
            throw new ProtocolException("INPUT_KEY payload must be 13 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int keyCode = bb.getInt();
        int meta = bb.getInt();
        int repeat = bb.getInt();
        return new KeyEventMsg(action, keyCode, meta, repeat);
    }

    public static final class TextEventMsg {
        public final int mode; // 0=COMMIT_TEXT_PASTE, 1=SET_CLIPBOARD_ONLY
        public final long seq;
        public final String text;

        public TextEventMsg(int mode, long seq, String text) {
            this.mode = mode;
            this.seq = seq;
            this.text = (text != null) ? text : "";
        }
    }

    public static byte[] encodeTextEvent(TextEventMsg msg) {
        byte[] utf8 = (msg.text != null ? msg.text : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(9 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.mode & 0xFF));
        bb.putLong(msg.seq);
        bb.put(utf8);
        return bb.array();
    }

    public static TextEventMsg decodeTextEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new ProtocolException("INPUT_TEXT payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        String text = new String(payload, 9, payload.length - 9, StandardCharsets.UTF_8);
        return new TextEventMsg(mode, seq, text);
    }

    public static final class ImageClipboardEventMsg {
        public final int mode; // 0=PASTE_IMAGE, 1=SET_CLIPBOARD_IMAGE_ONLY
        public final long seq;
        public final byte[] pngData;

        public ImageClipboardEventMsg(int mode, long seq, byte[] pngData) {
            this.mode = mode;
            this.seq = seq;
            this.pngData = (pngData != null) ? pngData : EMPTY_BYTES;
        }
    }

    public static byte[] encodeImageClipboardEvent(ImageClipboardEventMsg msg) throws ProtocolException {
        byte[] data = (msg != null && msg.pngData != null) ? msg.pngData : EMPTY_BYTES;
        if (data.length > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Image clipboard payload exceeds 8 MiB limit: " + data.length);
        }
        ByteBuffer bb = ByteBuffer.allocate(9 + data.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) ((msg != null ? msg.mode : TEXT_MODE_SET_CLIPBOARD_ONLY) & 0xFF));
        bb.putLong(msg != null ? msg.seq : 0L);
        if (data.length > 0) bb.put(data);
        return bb.array();
    }

    public static ImageClipboardEventMsg decodeImageClipboardEvent(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 9) {
            throw new ProtocolException("INPUT_TEXT image payload too short");
        }
        int imgLen = payload.length - 9;
        if (imgLen > MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Image clipboard payload exceeds 8 MiB limit: " + imgLen);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int mode = bb.get() & 0xFF;
        long seq = bb.getLong();
        byte[] data = new byte[imgLen];
        if (imgLen > 0) {
            bb.get(data);
        }
        return new ImageClipboardEventMsg(mode, seq, data);
    }

    // ---- ExtCast v1.1 Downlink: WIFI_UPGRADE_READY (0x14) & APP_LIST (0x15) ----

    public static final class WifiUpgradeReadyMsg {
        public final boolean ok;
        public final String host;
        public final int port;
        public final String certFingerprint;
        public final String authToken;
        public final String message;

        public WifiUpgradeReadyMsg(
                boolean ok, String host, int port,
                String certFingerprint, String authToken, String message) {
            this.ok = ok;
            this.host = (host != null) ? host : "";
            this.port = port;
            this.certFingerprint = (certFingerprint != null) ? certFingerprint : "";
            this.authToken = (authToken != null) ? authToken : "";
            this.message = (message != null) ? message : "";
        }
    }

    public static byte[] encodeWifiUpgradeReady(WifiUpgradeReadyMsg msg) {
        String json = new SimpleJson.ObjectBuilder()
                .put("ok", msg.ok)
                .put("host", msg.host)
                .put("port", msg.port)
                .put("certFingerprint", msg.certFingerprint)
                .put("authToken", msg.authToken)
                .put("message", msg.message)
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static WifiUpgradeReadyMsg decodeWifiUpgradeReady(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("WIFI_UPGRADE_READY payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            return new WifiUpgradeReadyMsg(
                    obj.getBoolean("ok", false),
                    obj.getString("host", ""),
                    obj.getInt("port", 0),
                    obj.getString("certFingerprint", ""),
                    obj.getString("authToken", ""),
                    obj.getString("message", ""));
        } catch (Throwable t) {
            throw new ProtocolException("Invalid WIFI_UPGRADE_READY JSON: " + t.getMessage());
        }
    }

    public static final class AppEntry {
        public final String pkg;
        public final String cls;
        public final String label;
        public final boolean runningOnExt;
        public final int taskId;

        public AppEntry(String pkg, String cls, String label, boolean runningOnExt, int taskId) {
            this.pkg = (pkg != null) ? pkg : "";
            this.cls = (cls != null) ? cls : "";
            this.label = (label != null) ? label : "";
            this.runningOnExt = runningOnExt;
            this.taskId = taskId;
        }
    }

    public static final class AppListMsg {
        public final List<AppEntry> apps;

        public AppListMsg(List<AppEntry> apps) {
            this.apps = (apps != null)
                    ? Collections.unmodifiableList(new ArrayList<>(apps))
                    : Collections.emptyList();
        }
    }

    public static byte[] encodeAppList(AppListMsg msg) {
        SimpleJson.ArrayBuilder arr = new SimpleJson.ArrayBuilder();
        if (msg != null && msg.apps != null) {
            for (AppEntry e : msg.apps) {
                if (e == null) continue;
                arr.addRaw(new SimpleJson.ObjectBuilder()
                        .put("pkg", e.pkg)
                        .put("cls", e.cls)
                        .put("label", e.label)
                        .put("runningOnExt", e.runningOnExt)
                        .put("taskId", e.taskId)
                        .build());
            }
        }
        String json = new SimpleJson.ObjectBuilder()
                .putRaw("apps", arr.build())
                .build();
        return json.getBytes(StandardCharsets.UTF_8);
    }

    public static AppListMsg decodeAppList(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("APP_LIST payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            List<SimpleJson.JsonObjectView> items = obj.getObjectList("apps");
            List<AppEntry> entries = new ArrayList<>(items.size());
            for (SimpleJson.JsonObjectView item : items) {
                entries.add(new AppEntry(
                        item.getString("pkg", ""),
                        item.getString("cls", ""),
                        item.getString("label", ""),
                        item.getBoolean("runningOnExt", false),
                        item.getInt("taskId", -1)));
            }
            return new AppListMsg(entries);
        } catch (Throwable t) {
            throw new ProtocolException("Invalid APP_LIST JSON: " + t.getMessage());
        }
    }

    // ---- ExtCast v1.1 Uplink: WINDOW_ACTION (0x2B) ----

    public static final class WindowActionMsg {
        public final String action;
        public final boolean hasEnabled;
        public final boolean enabled;
        public final int defaultWidthDp;
        public final String pkg;
        public final String cls;
        public final String mode;
        public final int widthPct;
        public final boolean remember;
        public final int slot;
        public final int taskId;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;
        public final boolean hasBounds;
        public final String topology;
        public final List<String> pkgs;
        public final List<Integer> ratios;
        public final String backend;

        public WindowActionMsg(
                String action,
                boolean hasEnabled,
                boolean enabled,
                int defaultWidthDp,
                String pkg,
                String cls,
                String mode,
                int widthPct,
                boolean remember,
                int slot,
                int taskId,
                int left,
                int top,
                int right,
                int bottom,
                boolean hasBounds,
                String topology,
                List<String> pkgs,
                List<Integer> ratios,
                String backend) {
            this.action = (action != null) ? action.trim() : "";
            this.hasEnabled = hasEnabled;
            this.enabled = enabled;
            this.defaultWidthDp = defaultWidthDp;
            this.pkg = (pkg != null) ? pkg.trim() : "";
            this.cls = (cls != null) ? cls.trim() : "";
            this.mode = (mode != null) ? mode.trim() : "";
            this.widthPct = widthPct;
            this.remember = remember;
            this.slot = slot;
            this.taskId = taskId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.hasBounds = hasBounds;
            this.topology = (topology != null) ? topology.trim() : "";
            this.pkgs = (pkgs != null)
                    ? Collections.unmodifiableList(new ArrayList<>(pkgs))
                    : Collections.emptyList();
            this.ratios = (ratios != null)
                    ? Collections.unmodifiableList(new ArrayList<>(ratios))
                    : Collections.emptyList();
            this.backend = (backend != null) ? backend.trim() : "";
        }
    }

    public static byte[] encodeWindowAction(WindowActionMsg msg) {
        SimpleJson.ObjectBuilder ob = new SimpleJson.ObjectBuilder()
                .put("action", msg.action);
        if (msg.hasEnabled) {
            ob.put("enabled", msg.enabled);
        }
        if (msg.defaultWidthDp > 0) {
            ob.put("defaultWidthDp", msg.defaultWidthDp);
        }
        if (!msg.pkg.isEmpty()) {
            ob.put("pkg", msg.pkg);
        }
        if (!msg.cls.isEmpty()) {
            ob.put("cls", msg.cls);
        }
        if (!msg.mode.isEmpty()) {
            ob.put("mode", msg.mode);
        }
        if (msg.widthPct > 0) {
            ob.put("widthPct", msg.widthPct);
        }
        if (msg.remember) {
            ob.put("remember", true);
        }
        if (msg.slot >= 0) {
            ob.put("slot", msg.slot);
        }
        if (msg.taskId > 0) {
            ob.put("taskId", msg.taskId);
        }
        if (msg.hasBounds) {
            ob.put("left", msg.left)
              .put("top", msg.top)
              .put("right", msg.right)
              .put("bottom", msg.bottom);
        }
        if (!msg.topology.isEmpty()) {
            ob.put("topology", msg.topology);
        }
        if (!msg.pkgs.isEmpty()) {
            SimpleJson.ArrayBuilder pa = new SimpleJson.ArrayBuilder();
            for (String p : msg.pkgs) pa.add(p);
            ob.putRaw("pkgs", pa.build());
        }
        if (!msg.ratios.isEmpty()) {
            SimpleJson.ArrayBuilder ra = new SimpleJson.ArrayBuilder();
            for (Integer r : msg.ratios) ra.add(r != null ? r : 0);
            ob.putRaw("ratios", ra.build());
        }
        if (!msg.backend.isEmpty()) {
            ob.put("backend", msg.backend);
        }
        return ob.build().getBytes(StandardCharsets.UTF_8);
    }

    public static WindowActionMsg decodeWindowAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 2) {
            throw new ProtocolException("WINDOW_ACTION payload empty");
        }
        try {
            SimpleJson.JsonObjectView obj = SimpleJson.parseObject(new String(payload, StandardCharsets.UTF_8));
            String action = obj.getString("action", "");
            if (action.trim().isEmpty()) {
                throw new ProtocolException("WINDOW_ACTION missing action");
            }
            boolean hasBounds = obj.has("left") && obj.has("top") && obj.has("right") && obj.has("bottom");
            return new WindowActionMsg(
                    action,
                    obj.has("enabled"),
                    obj.getBoolean("enabled", true),
                    obj.getInt("defaultWidthDp", 0),
                    obj.getString("pkg", ""),
                    obj.getString("cls", ""),
                    obj.getString("mode", ""),
                    obj.getInt("widthPct", 0),
                    obj.getBoolean("remember", false),
                    obj.getInt("slot", -1),
                    obj.getInt("taskId", -1),
                    obj.getInt("left", 0),
                    obj.getInt("top", 0),
                    obj.getInt("right", 0),
                    obj.getInt("bottom", 0),
                    hasBounds,
                    obj.getString("topology", ""),
                    obj.getStringList("pkgs"),
                    obj.getIntList("ratios"),
                    obj.getString("backend", ""));
        } catch (ProtocolException pe) {
            throw pe;
        } catch (Throwable t) {
            throw new ProtocolException("Invalid WINDOW_ACTION JSON: " + t.getMessage());
        }
    }

    public static final class SystemActionMsg {
        public final int action;
        public final int arg;

        public SystemActionMsg(int action, int arg) {
            this.action = action;
            this.arg = arg;
        }
    }

    public static byte[] encodeSystemAction(int action, int arg) {
        ByteBuffer bb = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (action & 0xFF));
        bb.putInt(arg);
        return bb.array();
    }

    public static SystemActionMsg decodeSystemAction(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("SYSTEM_ACTION payload must be 5 bytes");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int action = bb.get() & 0xFF;
        int arg = bb.getInt();
        return new SystemActionMsg(action, arg);
    }

    public static final class FileTransferChunkMsg {
        public final int transferId;
        public final int chunkFlags;
        public final String fileName;   // populated when START flag set
        public final long totalSize;    // populated when START flag set
        public final byte[] data;       // populated when DATA flag set

        public FileTransferChunkMsg(
                int transferId, int chunkFlags, String fileName, long totalSize, byte[] data) {
            this.transferId = transferId;
            this.chunkFlags = chunkFlags & 0xFF;
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.data = (data != null) ? data : EMPTY_BYTES;
        }
    }

    public static byte[] encodeFileTransferStart(int transferId, String fileName, long totalSize) throws ProtocolException {
        byte[] nameBytes = (fileName != null ? fileName : "").getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF) {
            throw new ProtocolException("File name exceeds uint16 length");
        }
        ByteBuffer bb = ByteBuffer.allocate(5 + 2 + nameBytes.length + 8).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) FILE_FLAG_START);
        bb.putShort((short) nameBytes.length);
        bb.put(nameBytes);
        bb.putLong(totalSize);
        return bb.array();
    }

    public static byte[] encodeFileTransferData(int transferId, byte[] data, boolean isEnd) {
        byte[] bytes = (data != null) ? data : EMPTY_BYTES;
        int flags = FILE_FLAG_DATA | (isEnd ? FILE_FLAG_END : 0);
        ByteBuffer bb = ByteBuffer.allocate(5 + bytes.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) flags);
        if (bytes.length > 0) bb.put(bytes);
        return bb.array();
    }

    public static byte[] encodeFileTransferControl(int transferId, int chunkFlags) {
        ByteBuffer bb = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) (chunkFlags & 0xFF));
        return bb.array();
    }

    public static FileTransferChunkMsg decodeFileTransferChunk(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("FILE_TRANSFER_CHUNK payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int chunkFlags = bb.get() & 0xFF;
        String fileName = null;
        long totalSize = -1L;
        if ((chunkFlags & FILE_FLAG_START) != 0) {
            if (bb.remaining() < 2) {
                throw new ProtocolException("FILE_TRANSFER_CHUNK START missing fileNameLen");
            }
            int nameLen = bb.getShort() & 0xFFFF;
            if (bb.remaining() < nameLen + 8) {
                throw new ProtocolException("FILE_TRANSFER_CHUNK START truncated");
            }
            byte[] nameBytes = new byte[nameLen];
            bb.get(nameBytes);
            fileName = new String(nameBytes, StandardCharsets.UTF_8);
            totalSize = bb.getLong();
        }
        byte[] data = EMPTY_BYTES;
        if ((chunkFlags & FILE_FLAG_DATA) != 0 && bb.remaining() > 0) {
            data = new byte[bb.remaining()];
            bb.get(data);
        }
        return new FileTransferChunkMsg(transferId, chunkFlags, fileName, totalSize, data);
    }
}
