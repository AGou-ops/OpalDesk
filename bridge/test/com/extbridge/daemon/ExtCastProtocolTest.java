package com.extbridge.daemon;

import com.extbridge.daemon.sink.DisplaySink;
import com.extbridge.daemon.stream.ExtCastProtocol;
import com.extbridge.daemon.stream.FileTransferPathGuard;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ExtCastProtocolTest {

    @Rule
    public TemporaryFolder tmpDir = new TemporaryFolder();

    private ExtCastProtocol.Frame roundTrip(int type, int flags, long tsUs, byte[] payload) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ExtCastProtocol.writeFrame(baos, type, flags, tsUs, payload, 0, payload.length);
        byte[] wire = baos.toByteArray();

        // Verify exact 18-byte BE header layout
        assertEquals(ExtCastProtocol.HEADER_SIZE + payload.length, wire.length);
        assertEquals('E', wire[0]);
        assertEquals('X', wire[1]);
        assertEquals('B', wire[2]);
        assertEquals('1', wire[3]);
        assertEquals(type & 0xFF, wire[4] & 0xFF);
        assertEquals(flags & 0xFF, wire[5] & 0xFF);
        ByteBuffer hdr = ByteBuffer.wrap(wire, 6, 12).order(ByteOrder.BIG_ENDIAN);
        assertEquals(payload.length, hdr.getInt());
        assertEquals(tsUs, hdr.getLong());

        ByteArrayInputStream bais = new ByteArrayInputStream(wire);
        ExtCastProtocol.Frame frame = ExtCastProtocol.readFrame(bais);
        assertNotNull(frame);
        assertEquals(type, frame.type);
        assertEquals(flags, frame.flags);
        assertEquals(tsUs, frame.timestampUs);
        assertArrayEquals(payload, frame.payload);
        assertNull(ExtCastProtocol.readFrame(bais));
        return frame;
    }

    @Test
    public void testVideoConfigAndFrame() throws Exception {
        byte[] csd = new byte[]{0, 0, 0, 1, 0x40, 0x01, 0x0C, 0, 0, 0, 1, 0x42, 0x01};
        ExtCastProtocol.VideoConfig cfg = new ExtCastProtocol.VideoConfig(
                ExtCastProtocol.VIDEO_CODEC_HEVC, 2560, 1440, 240, 0, 6000, ExtCastProtocol.DISPLAY_MODE_DEX, csd);
        byte[] enc = ExtCastProtocol.encodeVideoConfig(cfg);
        assertEquals(11 + csd.length, enc.length);

        ExtCastProtocol.Frame f = roundTrip(ExtCastProtocol.TYPE_VIDEO_CONFIG, 0, 123_456_789L, enc);
        ExtCastProtocol.VideoConfig dec = ExtCastClientTestCodec.decodeVideoConfig(f.payload);
        assertEquals(ExtCastProtocol.VIDEO_CODEC_HEVC, dec.codec);
        assertEquals(2560, dec.width);
        assertEquals(1440, dec.height);
        assertEquals(240, dec.dpi);
        assertEquals(0, dec.rotation);
        assertEquals(6000, dec.fpsX100);
        assertEquals(ExtCastProtocol.DISPLAY_MODE_DEX, dec.displayMode);
        assertArrayEquals(csd, dec.csdAnnexB);

        byte[] nal = new byte[]{0, 0, 0, 1, 0x26, 0x01, (byte) 0xAF};
        int flags = ExtCastProtocol.FLAG_VIDEO_KEYFRAME | ExtCastProtocol.FLAG_VIDEO_CONFIG_INLINE;
        ExtCastProtocol.Frame vf = roundTrip(ExtCastProtocol.TYPE_VIDEO_FRAME, flags, 987_654_321L, nal);
        assertEquals(flags, vf.flags);
        assertArrayEquals(nal, vf.payload);
    }

    @Test
    public void testAudioConfigAndFrame() throws Exception {
        byte[] asc = new byte[]{0x11, (byte) 0x90};
        ExtCastProtocol.AudioConfig cfg = new ExtCastProtocol.AudioConfig(
                ExtCastProtocol.AUDIO_CODEC_AAC_LC, 48_000, 2, asc);
        byte[] enc = ExtCastProtocol.encodeAudioConfig(cfg);
        assertEquals(8, enc.length);

        ExtCastProtocol.Frame f = roundTrip(ExtCastProtocol.TYPE_AUDIO_CONFIG, 0, 1000L, enc);
        ExtCastProtocol.AudioConfig dec = ExtCastClientTestCodec.decodeAudioConfig(f.payload);
        assertEquals(ExtCastProtocol.AUDIO_CODEC_AAC_LC, dec.codec);
        assertEquals(48_000, dec.sampleRate);
        assertEquals(2, dec.channels);
        assertArrayEquals(asc, dec.audioSpecificConfig);

        byte[] au = new byte[]{0x21, 0x10, 0x04, 0x60};
        roundTrip(ExtCastProtocol.TYPE_AUDIO_FRAME, 0, 2000L, au);
    }

    @Test
    public void testDeviceStateAndAuthResultAndHelloJson() throws Exception {
        ExtCastProtocol.DeviceState st = new ExtCastProtocol.DeviceState(
                "Xiaomi \"Pad\" \\ Pro", 2, 0, 1920, 1080, 240, 60.0, 0, 12000, 85, true, true);
        ExtCastProtocol.Frame f1 = roundTrip(ExtCastProtocol.TYPE_DEVICE_STATE, 0, 3000L,
                ExtCastProtocol.encodeDeviceState(st));
        ExtCastProtocol.DeviceState stDec = ExtCastClientTestCodec.decodeDeviceState(f1.payload);
        assertEquals("Xiaomi \"Pad\" \\ Pro", stDec.deviceName);
        assertEquals(2, stDec.sinkType);
        assertEquals(0, stDec.displayMode);
        assertEquals(1920, stDec.width);
        assertEquals(1080, stDec.height);
        assertEquals(240, stDec.dpi);
        assertEquals(60.0, stDec.fps, 0.01);
        assertEquals(0, stDec.rotation);
        assertEquals(12000, stDec.bitrateKbps);
        assertEquals(85, stDec.batteryPct);
        assertTrue(stDec.blackout);
        assertTrue(stDec.audioEnabled);

        ExtCastProtocol.AuthResultMsg auth = new ExtCastProtocol.AuthResultMsg(
                true, "abcdef0123456789", "OK 中文", "Redmi K Pad", "deadbeef");
        byte[] authBytes = ExtCastProtocol.encodeAuthResult(auth);
        assertEquals(1, authBytes[0]);
        ExtCastProtocol.Frame f2 = roundTrip(ExtCastProtocol.TYPE_AUTH_RESULT, 0, 4000L, authBytes);
        ExtCastProtocol.AuthResultMsg authDec = ExtCastClientTestCodec.decodeAuthResult(f2.payload);
        assertTrue(authDec.ok);
        assertEquals("abcdef0123456789", authDec.authToken);
        assertEquals("OK 中文", authDec.message);
        assertEquals("Redmi K Pad", authDec.deviceName);
        assertEquals("deadbeef", authDec.certFingerprint);

        ExtCastProtocol.HelloMsg hello = new ExtCastProtocol.HelloMsg(
                1, "Leo's MacBook Pro", "mac-uuid-123", "hevc",
                2560, 1440, 220, 60, 16000, 0, true, "tok123", "654321");
        ExtCastProtocol.Frame f3 = roundTrip(ExtCastProtocol.TYPE_HELLO, 0, 5000L,
                ExtCastClientTestCodec.encodeHello(hello));
        ExtCastProtocol.HelloMsg helloDec = ExtCastProtocol.decodeHello(f3.payload);
        assertEquals(1, helloDec.protocolVersion);
        assertEquals("Leo's MacBook Pro", helloDec.clientName);
        assertEquals("mac-uuid-123", helloDec.clientId);
        assertEquals("hevc", helloDec.preferredCodec);
        assertEquals(2560, helloDec.width);
        assertEquals(1440, helloDec.height);
        assertEquals(220, helloDec.dpi);
        assertEquals(60, helloDec.fps);
        assertEquals(16000, helloDec.bitrateKbps);
        assertEquals(0, helloDec.displayMode);
        assertTrue(helloDec.audioEnabled);
        assertEquals("tok123", helloDec.authToken);
        assertEquals("654321", helloDec.pinCode);
    }

    @Test
    public void testResizeAndInputAndControlFrames() throws Exception {
        // RESIZE_CONFIG (14 bytes)
        ExtCastProtocol.ResizeConfig rc = new ExtCastProtocol.ResizeConfig(
                1920, 1080, 240, 6000, 14000, ExtCastProtocol.DISPLAY_MODE_UNCHANGED, 2);
        byte[] rcEnc = ExtCastClientTestCodec.encodeResizeConfig(rc);
        assertEquals(14, rcEnc.length);
        ExtCastProtocol.ResizeConfig rcDec = ExtCastProtocol.decodeResizeConfig(
                roundTrip(ExtCastProtocol.TYPE_RESIZE_CONFIG, 0, 10L, rcEnc).payload);
        assertEquals(1920, rcDec.width);
        assertEquals(1080, rcDec.height);
        assertEquals(240, rcDec.dpi);
        assertEquals(6000, rcDec.fpsX100);
        assertEquals(14000, rcDec.bitrateKbps);
        assertEquals(0xFF, rcDec.displayMode);
        assertEquals(2, rcDec.rotation);

        // REQUEST_IDR (0 bytes)
        ExtCastProtocol.Frame idr = roundTrip(ExtCastProtocol.TYPE_REQUEST_IDR, 0, 11L, ExtCastProtocol.EMPTY_BYTES);
        assertEquals(0, idr.payload.length);

        // INPUT_MOUSE (25 bytes)
        ExtCastProtocol.MouseEventMsg mouse = new ExtCastProtocol.MouseEventMsg(
                ExtCastProtocol.MOUSE_SCROLL, 512.5f, 320.25f,
                ExtCastProtocol.BUTTON_PRIMARY | ExtCastProtocol.BUTTON_BACK,
                ExtCastProtocol.BUTTON_PRIMARY, -1.5f, 3.25f);
        byte[] mEnc = ExtCastClientTestCodec.encodeMouseEvent(mouse);
        assertEquals(25, mEnc.length);
        ExtCastProtocol.MouseEventMsg mDec = ExtCastProtocol.decodeMouseEvent(
                roundTrip(ExtCastProtocol.TYPE_INPUT_MOUSE, 0, 12L, mEnc).payload);
        assertEquals(ExtCastProtocol.MOUSE_SCROLL, mDec.action);
        assertEquals(512.5f, mDec.x, 0.0001f);
        assertEquals(320.25f, mDec.y, 0.0001f);
        assertEquals(9, mDec.buttons);
        assertEquals(1, mDec.actionButton);
        assertEquals(-1.5f, mDec.hScroll, 0.0001f);
        assertEquals(3.25f, mDec.vScroll, 0.0001f);

        // INPUT_TOUCH (3 + 16 * N bytes)
        ExtCastProtocol.TouchPointer[] pts = new ExtCastProtocol.TouchPointer[]{
                new ExtCastProtocol.TouchPointer(0, 100f, 200f, 0.8f),
                new ExtCastProtocol.TouchPointer(1, 300f, 400f, 0.9f)
        };
        ExtCastProtocol.TouchEventMsg touch = new ExtCastProtocol.TouchEventMsg(
                ExtCastProtocol.TOUCH_POINTER_DOWN, 1, pts);
        byte[] tEnc = ExtCastClientTestCodec.encodeTouchEvent(touch);
        assertEquals(3 + 32, tEnc.length);
        ExtCastProtocol.TouchEventMsg tDec = ExtCastProtocol.decodeTouchEvent(
                roundTrip(ExtCastProtocol.TYPE_INPUT_TOUCH, 0, 13L, tEnc).payload);
        assertEquals(ExtCastProtocol.TOUCH_POINTER_DOWN, tDec.action);
        assertEquals(1, tDec.actionIndex);
        assertEquals(2, tDec.pointers.length);
        assertEquals(1, tDec.pointers[1].pointerId);
        assertEquals(300f, tDec.pointers[1].x, 0.0001f);

        // INPUT_KEY (13 bytes)
        ExtCastProtocol.KeyEventMsg key = new ExtCastProtocol.KeyEventMsg(ExtCastProtocol.KEY_DOWN, 29, 4096, 1);
        byte[] kEnc = ExtCastClientTestCodec.encodeKeyEvent(key);
        assertEquals(13, kEnc.length);
        ExtCastProtocol.KeyEventMsg kDec = ExtCastProtocol.decodeKeyEvent(
                roundTrip(ExtCastProtocol.TYPE_INPUT_KEY, 0, 14L, kEnc).payload);
        assertEquals(ExtCastProtocol.KEY_DOWN, kDec.action);
        assertEquals(29, kDec.androidKeyCode);
        assertEquals(4096, kDec.metaState);
        assertEquals(1, kDec.repeatCount);

        // INPUT_TEXT + CLIPBOARD_PUSH
        ExtCastProtocol.TextEventMsg txt = new ExtCastProtocol.TextEventMsg(
                ExtCastProtocol.TEXT_MODE_COMMIT_PASTE, 42L, "你好 ExtBridge 🚀");
        ExtCastProtocol.TextEventMsg txtDec = ExtCastProtocol.decodeTextEvent(
                roundTrip(ExtCastProtocol.TYPE_INPUT_TEXT, 0, 15L, ExtCastClientTestCodec.encodeTextEvent(txt)).payload);
        assertEquals(ExtCastProtocol.TEXT_MODE_COMMIT_PASTE, txtDec.mode);
        assertEquals(42L, txtDec.seq);
        assertEquals("你好 ExtBridge 🚀", txtDec.text);

        ExtCastClientTestCodec.ClipboardPush cpDec = ExtCastClientTestCodec.decodeClipboardPush(
                roundTrip(ExtCastProtocol.TYPE_CLIPBOARD_PUSH, 0, 16L,
                        ExtCastProtocol.encodeClipboardPush(99L, "剪贴板同步")).payload);
        assertEquals(99L, cpDec.seq);
        assertEquals("剪贴板同步", cpDec.text);

        // SYSTEM_ACTION (5 bytes)
        byte[] sysEnc = ExtCastClientTestCodec.encodeSystemAction(ExtCastProtocol.SYS_TOGGLE_BLACKOUT, -1);
        assertEquals(5, sysEnc.length);
        ExtCastProtocol.SystemActionMsg sysDec = ExtCastProtocol.decodeSystemAction(
                roundTrip(ExtCastProtocol.TYPE_SYSTEM_ACTION, 0, 17L, sysEnc).payload);
        assertEquals(ExtCastProtocol.SYS_TOGGLE_BLACKOUT, sysDec.action);
        assertEquals(-1, sysDec.arg);

        // PING / PONG (8 bytes)
        long pingId = 0x1122334455667788L;
        assertEquals(pingId, ExtCastProtocol.decodePingPong(
                roundTrip(ExtCastProtocol.TYPE_PING, 0, 18L, ExtCastProtocol.encodePingPong(pingId)).payload));
        assertEquals(pingId, ExtCastProtocol.decodePingPong(
                roundTrip(ExtCastProtocol.TYPE_PONG, 0, 19L, ExtCastProtocol.encodePingPong(pingId)).payload));
    }

    @Test
    public void testFileTransferFramesAndPathTraversalDefense() throws Exception {
        byte[] startEnc = ExtCastClientTestCodec.encodeFileTransferStart(7, "report_2026.pdf", 4096L);
        ExtCastProtocol.FileTransferChunkMsg startDec = ExtCastProtocol.decodeFileTransferChunk(
                roundTrip(ExtCastProtocol.TYPE_FILE_TRANSFER_CHUNK, 0, 20L, startEnc).payload);
        assertEquals(7, startDec.transferId);
        assertEquals(ExtCastProtocol.FILE_FLAG_START, startDec.chunkFlags);
        assertEquals("report_2026.pdf", startDec.fileName);
        assertEquals(4096L, startDec.totalSize);

        byte[] chunk = new byte[]{1, 2, 3, 4, 5};
        byte[] dataEnc = ExtCastClientTestCodec.encodeFileTransferData(7, chunk, true);
        ExtCastProtocol.FileTransferChunkMsg dataDec = ExtCastProtocol.decodeFileTransferChunk(
                roundTrip(ExtCastProtocol.TYPE_FILE_TRANSFER_CHUNK, 0, 21L, dataEnc).payload);
        assertEquals(ExtCastProtocol.FILE_FLAG_DATA | ExtCastProtocol.FILE_FLAG_END, dataDec.chunkFlags);
        assertArrayEquals(chunk, dataDec.data);

        byte[] ackEnc = ExtCastProtocol.encodeFileTransferAck(7, ExtCastProtocol.FILE_ACK_OK,
                "/sdcard/Download/ExtBridge/report_2026.pdf");
        ExtCastClientTestCodec.FileTransferAck ackDec = ExtCastClientTestCodec.decodeFileTransferAck(
                roundTrip(ExtCastProtocol.TYPE_FILE_TRANSFER_ACK, 0, 22L, ackEnc).payload);
        assertEquals(7, ackDec.transferId);
        assertEquals(ExtCastProtocol.FILE_ACK_OK, ackDec.status);
        assertEquals("/sdcard/Download/ExtBridge/report_2026.pdf", ackDec.pathOrError);

        // Path traversal rejections
        assertNull(FileTransferPathGuard.sanitizeFileName("../etc/passwd"));
        assertNull(FileTransferPathGuard.sanitizeFileName("..\\..\\Windows\\win.ini"));
        assertNull(FileTransferPathGuard.sanitizeFileName("foo/../../bar.txt"));
        assertNull(FileTransferPathGuard.sanitizeFileName(".ssh_config"));
        assertNull(FileTransferPathGuard.sanitizeFileName("bad\0name.txt"));
        assertNull(FileTransferPathGuard.sanitizeFileName(""));
        assertEquals("photo.png", FileTransferPathGuard.sanitizeFileName("subdir/photo.png"));
        assertEquals("中文文档.pdf", FileTransferPathGuard.sanitizeFileName("中文文档.pdf"));

        File base = tmpDir.newFolder("ExtBridge").getCanonicalFile();
        File okTarget = FileTransferPathGuard.resolveUniqueSafeFile(base, "demo.txt");
        assertTrue(okTarget.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator));
        try {
            FileTransferPathGuard.verifyInsideBaseDir(base, new File(base, "../escape.txt"));
            fail("Expected SecurityException for path escape");
        } catch (SecurityException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test(expected = ExtCastProtocol.ProtocolException.class)
    public void testRejectInvalidMagic() throws Exception {
        byte[] bad = new byte[ExtCastProtocol.HEADER_SIZE];
        bad[0] = 'B'; bad[1] = 'A'; bad[2] = 'D'; bad[3] = '!';
        ExtCastProtocol.readFrame(new ByteArrayInputStream(bad));
    }

    @Test(expected = ExtCastProtocol.ProtocolException.class)
    public void testRejectOversizedPayloadAbove16MiB() throws Exception {
        byte[] hdr = new byte[ExtCastProtocol.HEADER_SIZE];
        ExtCastProtocol.encodeHeader(hdr, ExtCastProtocol.TYPE_VIDEO_FRAME, 0,
                ExtCastProtocol.MAX_PAYLOAD_LEN + 1, 0L);
        ExtCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test(expected = ExtCastProtocol.ProtocolException.class)
    public void testRejectNegativeSignedUint32PayloadLen() throws Exception {
        byte[] hdr = new byte[ExtCastProtocol.HEADER_SIZE];
        ExtCastProtocol.encodeHeader(hdr, ExtCastProtocol.TYPE_VIDEO_FRAME, 0, 0xFFFFFFFF, 0L);
        ExtCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test(expected = EOFException.class)
    public void testRejectTruncatedFrame() throws Exception {
        byte[] hdr = new byte[ExtCastProtocol.HEADER_SIZE + 2];
        ExtCastProtocol.encodeHeader(hdr, ExtCastProtocol.TYPE_PING, 0, 8, 123L);
        ExtCastProtocol.readFrame(new ByteArrayInputStream(hdr));
    }

    @Test
    public void testV11HelloAndDeviceStateExtensionsAndBackwardCompat() throws Exception {
        assertEquals(4, DisplaySink.SINK_REMOTE_WIFI_ADB);
        assertEquals(BridgeBuildConfig.EXTCAST_PROTOCOL_VERSION, ExtCastProtocol.PROTOCOL_VERSION);

        // Full v1.1 HELLO with standalone_app + wifi_adb
        ExtCastProtocol.HelloMsg h11 = new ExtCastProtocol.HelloMsg(
                1, "Mac Studio", "mac-007", "hevc",
                840, 1680, 320, 120, 14000, 0, false, "tok", "",
                ExtCastProtocol.TRANSPORT_HINT_WIFI_ADB,
                ExtCastProtocol.SESSION_ROLE_STANDALONE_APP,
                "com.tencent.mm", "com.tencent.mm.ui.LauncherUI");
        ExtCastProtocol.Frame hf = roundTrip(ExtCastProtocol.TYPE_HELLO, 0, 100L,
                ExtCastClientTestCodec.encodeHello(h11));
        ExtCastProtocol.HelloMsg hDec = ExtCastProtocol.decodeHello(hf.payload);
        assertEquals("wifi_adb", hDec.transportHint);
        assertEquals("standalone_app", hDec.sessionRole);
        assertTrue(hDec.isStandaloneApp());
        assertEquals("com.tencent.mm", hDec.appPackage);
        assertEquals("com.tencent.mm.ui.LauncherUI", hDec.appActivity);

        // Legacy v1.0 HELLO JSON lacking v1.1 fields defaults safely
        byte[] legacyHello = "{\"protocolVersion\":1,\"clientName\":\"OldMac\",\"clientId\":\"old1\"}".getBytes(StandardCharsets.UTF_8);
        ExtCastProtocol.HelloMsg legacyDec = ExtCastProtocol.decodeHello(legacyHello);
        assertEquals("", legacyDec.transportHint);
        assertEquals("desktop", legacyDec.sessionRole);
        assertFalse(legacyDec.isStandaloneApp());
        assertEquals("", legacyDec.appPackage);

        // Full v1.1 DEVICE_STATE
        ExtCastProtocol.DeviceState ds11 = new ExtCastProtocol.DeviceState(
                "Xiaomi 15 Ultra", DisplaySink.SINK_REMOTE_WIFI_ADB, 0,
                2560, 1440, 240, 120.0, 0, 18000, 92, false, true,
                2, true, 460, "ADAPTIVE_SINGLE", "com.tencent.mm", "微信");
        ExtCastProtocol.Frame df = roundTrip(ExtCastProtocol.TYPE_DEVICE_STATE, 0, 200L, ExtCastProtocol.encodeDeviceState(ds11));
        ExtCastProtocol.DeviceState dsDec = ExtCastClientTestCodec.decodeDeviceState(df.payload);
        assertEquals(DisplaySink.SINK_REMOTE_WIFI_ADB, dsDec.sinkType);
        assertEquals(2, dsDec.injectStatus);
        assertTrue(dsDec.adaptiveSingleEnabled);
        assertEquals(460, dsDec.defaultSingleWidthDp);
        assertEquals("ADAPTIVE_SINGLE", dsDec.activeLayoutMode);
        assertEquals("com.tencent.mm", dsDec.topAppPackage);
        assertEquals("微信", dsDec.topAppLabel);

        // Legacy v1.0 DEVICE_STATE JSON defaults safely
        byte[] legacyDs = "{\"deviceName\":\"Legacy\",\"sinkType\":2}".getBytes(StandardCharsets.UTF_8);
        ExtCastProtocol.DeviceState legacyDsDec = ExtCastClientTestCodec.decodeDeviceState(legacyDs);
        assertEquals(1, legacyDsDec.injectStatus);
        assertTrue(legacyDsDec.adaptiveSingleEnabled);
        assertEquals(460, legacyDsDec.defaultSingleWidthDp);
        assertEquals("FULLSCREEN", legacyDsDec.activeLayoutMode);
        assertEquals("", legacyDsDec.topAppPackage);
    }

    @Test
    public void testV11WifiUpgradeReadyAppListAndWindowActions() throws Exception {
        // 0x29 REQUEST_WIFI_UPGRADE & 0x2A LIST_APPS empty payload
        assertEquals(0x29, ExtCastProtocol.TYPE_REQUEST_WIFI_UPGRADE);
        assertEquals(0x2A, ExtCastProtocol.TYPE_LIST_APPS);
        assertEquals(0, roundTrip(ExtCastProtocol.TYPE_REQUEST_WIFI_UPGRADE, 0, 300L, ExtCastProtocol.EMPTY_BYTES).payload.length);
        assertEquals(0, roundTrip(ExtCastProtocol.TYPE_LIST_APPS, 0, 301L, ExtCastProtocol.EMPTY_BYTES).payload.length);

        // 0x14 WIFI_UPGRADE_READY
        String fp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String tok = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";
        ExtCastProtocol.WifiUpgradeReadyMsg upg = new ExtCastProtocol.WifiUpgradeReadyMsg(
                true, "192.168.1.42", 19876, fp, tok, "");
        ExtCastProtocol.Frame uf = roundTrip(ExtCastProtocol.TYPE_WIFI_UPGRADE_READY, 0, 302L,
                ExtCastProtocol.encodeWifiUpgradeReady(upg));
        ExtCastProtocol.WifiUpgradeReadyMsg upgDec = ExtCastClientTestCodec.decodeWifiUpgradeReady(uf.payload);
        assertTrue(upgDec.ok);
        assertEquals("192.168.1.42", upgDec.host);
        assertEquals(19876, upgDec.port);
        assertEquals(fp, upgDec.certFingerprint);
        assertEquals(tok, upgDec.authToken);
        assertEquals("", upgDec.message);

        // 0x15 APP_LIST
        ExtCastProtocol.AppListMsg apps = new ExtCastProtocol.AppListMsg(Arrays.asList(
                new ExtCastProtocol.AppEntry("com.tencent.mm", "com.tencent.mm.ui.LauncherUI", "微信", true, 123),
                new ExtCastProtocol.AppEntry("com.quark.browser", "com.ucpro.MainActivity", "夸克", false, -1)));
        ExtCastProtocol.Frame af = roundTrip(ExtCastProtocol.TYPE_APP_LIST, 0, 303L, ExtCastProtocol.encodeAppList(apps));
        ExtCastProtocol.AppListMsg appsDec = ExtCastClientTestCodec.decodeAppList(af.payload);
        assertEquals(2, appsDec.apps.size());
        assertEquals("com.tencent.mm", appsDec.apps.get(0).pkg);
        assertEquals("微信", appsDec.apps.get(0).label);
        assertTrue(appsDec.apps.get(0).runningOnExt);
        assertEquals(123, appsDec.apps.get(0).taskId);
        assertFalse(appsDec.apps.get(1).runningOnExt);
        assertEquals(-1, appsDec.apps.get(1).taskId);

        // 0x2B WINDOW_ACTION: set_tiling with nested pkgs & ratios arrays
        ExtCastProtocol.WindowActionMsg tiling = new ExtCastProtocol.WindowActionMsg(
                "set_tiling", false, false, 0, "", "", "", 0, false, -1, -1,
                0, 0, 0, 0, false, "SPLIT_3_MASTER",
                Arrays.asList("com.tencent.mm", "com.quark.browser", "com.ss.android.ugc.aweme"),
                Arrays.asList(55, 50), "");
        ExtCastProtocol.Frame wf = roundTrip(ExtCastProtocol.TYPE_WINDOW_ACTION, 0, 304L,
                ExtCastClientTestCodec.encodeWindowAction(tiling));
        ExtCastProtocol.WindowActionMsg tDec = ExtCastProtocol.decodeWindowAction(wf.payload);
        assertEquals("set_tiling", tDec.action);
        assertEquals("SPLIT_3_MASTER", tDec.topology);
        assertEquals(3, tDec.pkgs.size());
        assertEquals("com.quark.browser", tDec.pkgs.get(1));
        assertEquals(Arrays.asList(55, 50), tDec.ratios);

        // 0x2B WINDOW_ACTION: update_window with explicit bounds & remember
        ExtCastProtocol.WindowActionMsg upd = ExtCastProtocol.decodeWindowAction(
                ("{\"action\":\"update_window\",\"taskId\":45,\"pkg\":\"com.tencent.mm\",\"mode\":\"freeform\","
                        + "\"left\":100,\"top\":80,\"right\":700,\"bottom\":1000,\"remember\":true}")
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("update_window", upd.action);
        assertEquals(45, upd.taskId);
        assertEquals("com.tencent.mm", upd.pkg);
        assertEquals("freeform", upd.mode);
        assertTrue(upd.hasBounds);
        assertEquals(100, upd.left);
        assertEquals(80, upd.top);
        assertEquals(700, upd.right);
        assertEquals(1000, upd.bottom);
        assertTrue(upd.remember);

        // 0x2B WINDOW_ACTION: set_input_backend, reload_app_dpi, return_to_desktop, set_single_width
        ExtCastProtocol.WindowActionMsg backend = ExtCastProtocol.decodeWindowAction(
                "{\"action\":\"set_input_backend\",\"backend\":\"uhid\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals("set_input_backend", backend.action);
        assertEquals("uhid", backend.backend);

        ExtCastProtocol.WindowActionMsg sw = ExtCastProtocol.decodeWindowAction(
                "{\"action\":\"set_single_width\",\"pkg\":\"com.tencent.mm\",\"widthPct\":42,\"remember\":true}"
                        .getBytes(StandardCharsets.UTF_8));
        assertEquals("set_single_width", sw.action);
        assertEquals(42, sw.widthPct);
        assertTrue(sw.remember);

        ExtCastProtocol.WindowActionMsg ret = new ExtCastProtocol.WindowActionMsg(
                "return_to_desktop", false, false, 0, "com.tencent.mm", "", "", 0, false, -1, 88,
                0, 0, 0, 0, false, "", Collections.emptyList(), Collections.emptyList(), "");
        ExtCastProtocol.WindowActionMsg retDec = ExtCastProtocol.decodeWindowAction(
                ExtCastClientTestCodec.encodeWindowAction(ret));
        assertEquals("return_to_desktop", retDec.action);
        assertEquals("com.tencent.mm", retDec.pkg);
        assertEquals(88, retDec.taskId);
    }

    @Test
    public void testJsonDecodeFailurePreservesCause() {
        try {
            ExtCastProtocol.decodeHello("{bad json".getBytes(StandardCharsets.UTF_8));
            fail("Expected ProtocolException");
        } catch (ExtCastProtocol.ProtocolException expected) {
            assertNotNull("JSON decode failure must preserve cause", expected.getCause());
        }
        try {
            ExtCastProtocol.decodeWindowAction("{bad json".getBytes(StandardCharsets.UTF_8));
            fail("Expected ProtocolException");
        } catch (ExtCastProtocol.ProtocolException expected) {
            assertNotNull("WINDOW_ACTION JSON failure must preserve cause", expected.getCause());
        }
    }

    @Test
    public void testPngClipboardFramesAnd8MiBCap() throws Exception {
        byte[] fakePng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
        };
        assertTrue(ExtCastProtocol.isValidPngHeader(fakePng));
        assertFalse(ExtCastProtocol.isValidPngHeader(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));

        // Downlink 0x11 CLIPBOARD_PUSH with FLAG_CLIPBOARD_IMAGE_PNG (0x01)
        byte[] pushEnc = ExtCastProtocol.encodeClipboardImagePush(777L, fakePng);
        assertEquals(8 + fakePng.length, pushEnc.length);
        ExtCastProtocol.Frame pf = roundTrip(
                ExtCastProtocol.TYPE_CLIPBOARD_PUSH, ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, 400L, pushEnc);
        assertEquals(ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, pf.flags);
        ExtCastClientTestCodec.ClipboardImagePush pushDec = ExtCastClientTestCodec.decodeClipboardImagePush(pf.payload);
        assertEquals(777L, pushDec.seq);
        assertArrayEquals(fakePng, pushDec.pngData);

        // Uplink 0x26 INPUT_TEXT with FLAG_CLIPBOARD_IMAGE_PNG (0x01)
        ExtCastProtocol.ImageClipboardEventMsg imgEv = new ExtCastProtocol.ImageClipboardEventMsg(
                ExtCastProtocol.TEXT_MODE_COMMIT_PASTE, 888L, fakePng);
        byte[] imgEnc = ExtCastClientTestCodec.encodeImageClipboardEvent(imgEv);
        assertEquals(9 + fakePng.length, imgEnc.length);
        ExtCastProtocol.Frame inf = roundTrip(
                ExtCastProtocol.TYPE_INPUT_TEXT, ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, 401L, imgEnc);
        assertEquals(ExtCastProtocol.FLAG_CLIPBOARD_IMAGE_PNG, inf.flags);
        ExtCastProtocol.ImageClipboardEventMsg imgDec = ExtCastProtocol.decodeImageClipboardEvent(inf.payload);
        assertEquals(ExtCastProtocol.TEXT_MODE_COMMIT_PASTE, imgDec.mode);
        assertEquals(888L, imgDec.seq);
        assertArrayEquals(fakePng, imgDec.pngData);

        // Oversized (> 8 MiB) PNG rejected on both encode and decode
        byte[] oversized = new byte[ExtCastProtocol.MAX_CLIPBOARD_IMAGE_BYTES + 1];
        try {
            ExtCastProtocol.encodeClipboardImagePush(1L, oversized);
            fail("Expected ProtocolException for >8 MiB clipboard push PNG");
        } catch (ExtCastProtocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("8 MiB"));
        }
        try {
            ExtCastClientTestCodec.encodeImageClipboardEvent(new ExtCastProtocol.ImageClipboardEventMsg(1, 2L, oversized));
            fail("Expected ProtocolException for >8 MiB uplink clipboard PNG");
        } catch (ExtCastProtocol.ProtocolException expected) {
            assertTrue(expected.getMessage().contains("8 MiB"));
        }
    }
}
