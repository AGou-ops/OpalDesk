package io.github.lengsukq.opaldesk.daemon.stream;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class WebSocketCodecTest {

    @Test
    public void testRfc6455OfficialAcceptKeyVector() {
        // RFC 6455 Section 1.3 official test vector
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketCodec.computeAcceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void testMaskedBinaryOpalCastFrameRoundTrip() throws Exception {
        byte[] pingPayload = OpalCastProtocol.encodePingPong(0x1122334455667788L);
        byte[] opalHeader = new byte[OpalCastProtocol.HEADER_SIZE];
        OpalCastProtocol.encodeHeader(opalHeader, OpalCastProtocol.TYPE_PING, 0, pingPayload.length, 987654L);
        byte[] fullOpal = concat(opalHeader, pingPayload);

        byte[] wsBytes = encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_BINARY, fullOpal, new byte[]{0x12, 0x34, 0x56, 0x78});
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpalCastFrameTransport transport = OpalCastFrameTransport.webSocket(new ByteArrayInputStream(wsBytes), out);

        OpalCastProtocol.Frame frame = transport.readFrame();
        assertNotNull(frame);
        assertEquals(OpalCastProtocol.TYPE_PING, frame.type);
        assertEquals(987654L, frame.timestampUs);
        assertEquals(0x1122334455667788L, OpalCastProtocol.decodePingPong(frame.payload));

        // Write server PONG back and verify unmasked RFC 6455 binary framing
        byte[] pongPayload = OpalCastProtocol.encodePingPong(0x1122334455667788L);
        transport.writeFrame(OpalCastProtocol.TYPE_PONG, 0, 123456L, pongPayload, 0, pongPayload.length);
        transport.flush();

        byte[] wire = out.toByteArray();
        assertEquals((byte) 0x82, wire[0]); // FIN=1, opcode=BINARY
        assertEquals(OpalCastProtocol.HEADER_SIZE + pongPayload.length, wire[1] & 0xFF); // unmasked 7-bit len
        byte[] innerOpal = Arrays.copyOfRange(wire, 2, wire.length);
        OpalCastProtocol.Frame decodedOut = OpalCastProtocol.decodeFrameBytes(innerOpal);
        assertEquals(OpalCastProtocol.TYPE_PONG, decodedOut.type);
        assertEquals(123456L, decodedOut.timestampUs);
        assertEquals(0x1122334455667788L, OpalCastProtocol.decodePingPong(decodedOut.payload));
    }

    @Test
    public void testLarge16BitAnd64BitPayloadLengths() throws Exception {
        // Test 16-bit extended length (2000 bytes)
        byte[] payload2k = new byte[2000];
        Arrays.fill(payload2k, (byte) 0x5A);
        byte[] hdr = new byte[OpalCastProtocol.HEADER_SIZE];
        OpalCastProtocol.encodeHeader(hdr, OpalCastProtocol.TYPE_VIDEO_FRAME, OpalCastProtocol.FLAG_VIDEO_KEYFRAME, payload2k.length, 42L);
        byte[] wsIn = encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_BINARY, concat(hdr, payload2k), new byte[]{1, 2, 3, 4});

        ByteArrayOutputStream serverOut = new ByteArrayOutputStream();
        OpalCastFrameTransport transport = OpalCastFrameTransport.webSocket(new ByteArrayInputStream(wsIn), serverOut);
        OpalCastProtocol.Frame f = transport.readFrame();
        assertNotNull(f);
        assertEquals(OpalCastProtocol.TYPE_VIDEO_FRAME, f.type);
        assertEquals(OpalCastProtocol.FLAG_VIDEO_KEYFRAME, f.flags);
        assertArrayEquals(payload2k, f.payload);

        // Test 64-bit extended length (> 65535 bytes) on server write
        byte[] bigPayload = new byte[70_000];
        Arrays.fill(bigPayload, (byte) 0x7E);
        transport.writeFrame(OpalCastProtocol.TYPE_VIDEO_FRAME, 0, 77L, bigPayload, 0, bigPayload.length);
        transport.flush();
        byte[] wire = serverOut.toByteArray();
        assertEquals((byte) 0x82, wire[0]);
        assertEquals((byte) 127, wire[1]); // 64-bit extended len marker
        long encodedLen = 0;
        for (int i = 2; i < 10; i++) {
            encodedLen = (encodedLen << 8) | (wire[i] & 0xFF);
        }
        assertEquals(OpalCastProtocol.HEADER_SIZE + 70_000, encodedLen);
        assertEquals(10 + OpalCastProtocol.HEADER_SIZE + 70_000, wire.length);
    }

    @Test
    public void testFragmentedMessageWithInterleavedPingAndClose() throws Exception {
        byte[] part1 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part2 = "opaldesk".getBytes(StandardCharsets.UTF_8);
        byte[] pingData = new byte[]{0x0A, 0x0B};

        ByteArrayOutputStream streamIn = new ByteArrayOutputStream();
        streamIn.write(encodeClientMaskedFrame(false, WebSocketCodec.OPCODE_TEXT, part1, new byte[]{1, 1, 1, 1}));
        streamIn.write(encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_PING, pingData, new byte[]{2, 2, 2, 2}));
        streamIn.write(encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_CONT, part2, new byte[]{3, 3, 3, 3}));
        streamIn.write(encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_CLOSE, new byte[]{0x03, (byte) 0xE8}, new byte[]{4, 4, 4, 4}));

        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
        Object lock = new Object();
        ByteArrayInputStream in = new ByteArrayInputStream(streamIn.toByteArray());

        WebSocketCodec.Message msg = WebSocketCodec.readMessage(in, streamOut, lock, 1024);
        assertNotNull(msg);
        assertEquals(WebSocketCodec.OPCODE_TEXT, msg.opcode);
        assertEquals("hello opaldesk", new String(msg.payload, StandardCharsets.UTF_8));

        // Verify server automatically replied with PONG carrying exact pingData
        byte[] pongBytes = streamOut.toByteArray();
        assertTrue(pongBytes.length >= 4);
        assertEquals((byte) (0x80 | WebSocketCodec.OPCODE_PONG), pongBytes[0]);
        assertEquals((byte) 2, pongBytes[1]);
        assertEquals((byte) 0x0A, pongBytes[2]);
        assertEquals((byte) 0x0B, pongBytes[3]);

        // Next read sees CLOSE and returns clean null
        assertNull(WebSocketCodec.readMessage(in, streamOut, lock, 1024));
    }

    @Test
    public void testRejectsUnmaskedClientFrameAndOversizedFrame() {
        byte[] unmasked = new byte[]{(byte) 0x82, 0x02, 0x01, 0x02};
        try {
            WebSocketCodec.readMessage(new ByteArrayInputStream(unmasked), null, new Object(), 1024);
            fail("Expected ProtocolException for unmasked client frame");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("masked"));
        }

        byte[] oversized = encodeClientMaskedFrame(true, WebSocketCodec.OPCODE_BINARY, new byte[200], new byte[]{9, 8, 7, 6});
        try {
            WebSocketCodec.readMessage(new ByteArrayInputStream(oversized), null, new Object(), 64);
            fail("Expected ProtocolException for oversized frame");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds limit"));
        }
    }

    private static byte[] encodeClientMaskedFrame(boolean fin, int opcode, byte[] payload, byte[] mask) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((fin ? 0x80 : 0x00) | (opcode & 0x0F));
        int len = payload.length;
        if (len <= 125) {
            out.write(0x80 | len);
        } else if (len <= 65535) {
            out.write(0x80 | 126);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) (((long) len >>> shift) & 0xFF));
            }
        }
        out.write(mask, 0, 4);
        for (int i = 0; i < len; i++) {
            out.write((payload[i] ^ mask[i & 3]) & 0xFF);
        }
        return out.toByteArray();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] res = new byte[a.length + b.length];
        System.arraycopy(a, 0, res, 0, a.length);
        System.arraycopy(b, 0, res, a.length, b.length);
        return res;
    }
}
