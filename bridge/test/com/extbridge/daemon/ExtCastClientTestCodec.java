package com.extbridge.daemon;

import com.extbridge.daemon.stream.ExtCastProtocol;
import com.extbridge.daemon.stream.ExtCastProtocol.AppEntry;
import com.extbridge.daemon.stream.ExtCastProtocol.AppListMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.AudioConfig;
import com.extbridge.daemon.stream.ExtCastProtocol.AuthResultMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.DeviceState;
import com.extbridge.daemon.stream.ExtCastProtocol.HelloMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.ImageClipboardEventMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.KeyEventMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.MouseEventMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.ProtocolException;
import com.extbridge.daemon.stream.ExtCastProtocol.ResizeConfig;
import com.extbridge.daemon.stream.ExtCastProtocol.TextEventMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.TouchEventMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.TouchPointer;
import com.extbridge.daemon.stream.ExtCastProtocol.VideoConfig;
import com.extbridge.daemon.stream.ExtCastProtocol.WifiUpgradeReadyMsg;
import com.extbridge.daemon.stream.ExtCastProtocol.WindowActionMsg;
import com.extbridge.daemon.stream.SimpleJson;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only ExtCast v1.1 client-side counterpart codec (decodes server downlink frames,
 * encodes client uplink frames) used by {@link ExtCastProtocolTest} for end-to-end wire
 * roundtrip verification without bloating production daemon DEX size.
 */
final class ExtCastClientTestCodec {
    private ExtCastClientTestCodec() {}

    static final class ClipboardPush {
        final long seq;
        final String text;

        ClipboardPush(long seq, String text) {
            this.seq = seq;
            this.text = (text != null) ? text : "";
        }
    }

    static final class ClipboardImagePush {
        final long seq;
        final byte[] pngData;

        ClipboardImagePush(long seq, byte[] pngData) {
            this.seq = seq;
            this.pngData = (pngData != null) ? pngData : ExtCastProtocol.EMPTY_BYTES;
        }
    }

    static final class FileTransferAck {
        final int transferId;
        final int status;
        final String pathOrError;

        FileTransferAck(int transferId, int status, String pathOrError) {
            this.transferId = transferId;
            this.status = status;
            this.pathOrError = (pathOrError != null) ? pathOrError : "";
        }
    }

    // ---- Client-side Downlink Decoders (Android -> Mac) ----

    static VideoConfig decodeVideoConfig(byte[] payload) throws ProtocolException {
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

    static AudioConfig decodeAudioConfig(byte[] payload) throws ProtocolException {
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

    static DeviceState decodeDeviceState(byte[] payload) throws ProtocolException {
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
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid DEVICE_STATE JSON: " + t.getMessage(), t);
        }
    }

    static ClipboardPush decodeClipboardPush(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("CLIPBOARD_PUSH payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long seq = bb.getLong();
        String text = new String(payload, 8, payload.length - 8, StandardCharsets.UTF_8);
        return new ClipboardPush(seq, text);
    }

    static ClipboardImagePush decodeClipboardImagePush(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 8) {
            throw new ProtocolException("CLIPBOARD_PUSH image payload too short");
        }
        int imgLen = payload.length - 8;
        if (imgLen > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Clipboard PNG exceeds 8 MiB limit: " + imgLen);
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        long seq = bb.getLong();
        byte[] data = new byte[imgLen];
        if (imgLen > 0) bb.get(data);
        return new ClipboardImagePush(seq, data);
    }

    static AuthResultMsg decodeAuthResult(byte[] payload) throws ProtocolException {
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
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid AUTH_RESULT JSON: " + t.getMessage(), t);
        }
    }

    static FileTransferAck decodeFileTransferAck(byte[] payload) throws ProtocolException {
        if (payload == null || payload.length < 5) {
            throw new ProtocolException("FILE_TRANSFER_ACK payload too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int transferId = bb.getInt();
        int status = bb.get() & 0xFF;
        String pathOrErr = new String(payload, 5, payload.length - 5, StandardCharsets.UTF_8);
        return new FileTransferAck(transferId, status, pathOrErr);
    }

    static WifiUpgradeReadyMsg decodeWifiUpgradeReady(byte[] payload) throws ProtocolException {
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
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid WIFI_UPGRADE_READY JSON: " + t.getMessage(), t);
        }
    }

    static AppListMsg decodeAppList(byte[] payload) throws ProtocolException {
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
        } catch (RuntimeException t) {
            throw new ProtocolException("Invalid APP_LIST JSON: " + t.getMessage(), t);
        }
    }

    // ---- Client-side Uplink Encoders (Mac -> Android) ----

    static byte[] encodeHello(HelloMsg msg) {
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

    static byte[] encodeResizeConfig(ResizeConfig cfg) {
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

    static byte[] encodeMouseEvent(MouseEventMsg msg) {
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

    static byte[] encodeTouchEvent(TouchEventMsg msg) throws ProtocolException {
        int count = (msg.pointers != null) ? msg.pointers.length : 0;
        if (count > ExtCastProtocol.MAX_TOUCH_POINTERS) {
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

    static byte[] encodeKeyEvent(KeyEventMsg msg) {
        ByteBuffer bb = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.action & 0xFF));
        bb.putInt(msg.androidKeyCode);
        bb.putInt(msg.metaState);
        bb.putInt(msg.repeatCount);
        return bb.array();
    }

    static byte[] encodeTextEvent(TextEventMsg msg) {
        byte[] utf8 = (msg.text != null ? msg.text : "").getBytes(StandardCharsets.UTF_8);
        ByteBuffer bb = ByteBuffer.allocate(9 + utf8.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (msg.mode & 0xFF));
        bb.putLong(msg.seq);
        bb.put(utf8);
        return bb.array();
    }

    static byte[] encodeImageClipboardEvent(ImageClipboardEventMsg msg) throws ProtocolException {
        byte[] data = (msg != null && msg.pngData != null) ? msg.pngData : ExtCastProtocol.EMPTY_BYTES;
        if (data.length > ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES) {
            throw new ProtocolException("Image clipboard payload exceeds 8 MiB limit: " + data.length);
        }
        ByteBuffer bb = ByteBuffer.allocate(9 + data.length).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) ((msg != null ? msg.mode : ExtCastProtocol.TEXT_MODE_SET_CLIPBOARD_ONLY) & 0xFF));
        bb.putLong(msg != null ? msg.seq : 0L);
        if (data.length > 0) bb.put(data);
        return bb.array();
    }

    static byte[] encodeWindowAction(WindowActionMsg msg) {
        SimpleJson.ObjectBuilder ob = new SimpleJson.ObjectBuilder()
                .put("action", msg.action);
        if (msg.hasEnabled) ob.put("enabled", msg.enabled);
        if (msg.defaultWidthDp > 0) ob.put("defaultWidthDp", msg.defaultWidthDp);
        if (!msg.pkg.isEmpty()) ob.put("pkg", msg.pkg);
        if (!msg.cls.isEmpty()) ob.put("cls", msg.cls);
        if (!msg.mode.isEmpty()) ob.put("mode", msg.mode);
        if (msg.widthPct > 0) ob.put("widthPct", msg.widthPct);
        if (msg.remember) ob.put("remember", true);
        if (msg.slot >= 0) ob.put("slot", msg.slot);
        if (msg.taskId > 0) ob.put("taskId", msg.taskId);
        if (msg.hasBounds) {
            ob.put("left", msg.left)
              .put("top", msg.top)
              .put("right", msg.right)
              .put("bottom", msg.bottom);
        }
        if (!msg.topology.isEmpty()) ob.put("topology", msg.topology);
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
        if (!msg.backend.isEmpty()) ob.put("backend", msg.backend);
        return ob.build().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] encodeSystemAction(int action, int arg) {
        ByteBuffer bb = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN);
        bb.put((byte) (action & 0xFF));
        bb.putInt(arg);
        return bb.array();
    }

    static byte[] encodeFileTransferStart(int transferId, String fileName, long totalSize) throws ProtocolException {
        byte[] nameBytes = (fileName != null ? fileName : "").getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > 0xFFFF) {
            throw new ProtocolException("File name exceeds uint16 length");
        }
        ByteBuffer bb = ByteBuffer.allocate(5 + 2 + nameBytes.length + 8).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) ExtCastProtocol.FILE_FLAG_START);
        bb.putShort((short) nameBytes.length);
        bb.put(nameBytes);
        bb.putLong(totalSize);
        return bb.array();
    }

    static byte[] encodeFileTransferData(int transferId, byte[] data, boolean isEnd) {
        byte[] bytes = (data != null) ? data : ExtCastProtocol.EMPTY_BYTES;
        int flags = ExtCastProtocol.FILE_FLAG_DATA | (isEnd ? ExtCastProtocol.FILE_FLAG_END : 0);
        ByteBuffer bb = ByteBuffer.allocate(5 + bytes.length).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(transferId);
        bb.put((byte) flags);
        if (bytes.length > 0) bb.put(bytes);
        return bb.array();
    }
}
