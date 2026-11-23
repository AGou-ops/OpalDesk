package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import io.github.lengsukq.opaldesk.protocol.PairingJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpalPeerCastMutexTest {

    @Test
    fun blocksSimultaneousTwoWayCastingAndSelfLoop() {
        // Idle -> allowed to connect to another LAN Android device
        assertTrue(
            OpalPeerCastMutex.validateStartReceiving(
                daemonSending = false,
                sendingPeerName = "",
                targetHost = "192.168.31.88",
                selfLanIp = "192.168.31.105",
            ).allowed,
        )

        // Already sending -> strictly blocks receiving (one-way only)
        val blockedRecv = OpalPeerCastMutex.validateStartReceiving(
            daemonSending = true,
            sendingPeerName = "Xiaomi 15",
            targetHost = "192.168.31.88",
            selfLanIp = "192.168.31.105",
        )
        assertFalse(blockedRecv.allowed)
        assertTrue(blockedRecv.reason.contains("单向"))

        // Self-loop IP and loopback rejected
        assertFalse(
            OpalPeerCastMutex.validateStartReceiving(
                daemonSending = false,
                sendingPeerName = "",
                targetHost = "192.168.31.105",
                selfLanIp = "192.168.31.105",
            ).allowed,
        )
        assertFalse(OpalPeerCastMutex.isSelfAddress("192.168.31.88", "192.168.31.105"))
        assertTrue(OpalPeerCastMutex.isSelfAddress("127.0.0.1", "192.168.31.105"))
        assertTrue(OpalPeerCastMutex.isSelfAddress("localhost", "192.168.31.105"))

        // Already receiving -> strictly blocks enabling outbound sending
        val blockedSend = OpalPeerCastMutex.validateEnableSending(
            clientReceiving = true,
            receivingPeerName = "Xiaomi Pad",
        )
        assertFalse(blockedSend.allowed)
        assertTrue(blockedSend.reason.contains("双向"))

        // Idle -> sending allowed
        assertTrue(OpalPeerCastMutex.validateEnableSending(false, "").allowed)
    }

    @Test
    fun refCountsConcurrentDesktopAndFloatingReceivers() {
        assertTrue(OpalPeerCastMutex.beginReceiving("192.168.31.40", 19878, "Phone", "desktop_viewer"))
        assertTrue(OpalPeerCastMutex.beginReceiving("192.168.31.40", 19878, "Phone", "floating_app"))
        assertTrue(OpalPeerCastMutex.isReceivingActive)

        // Closing desktop viewer must keep RECEIVING active while floating window still runs
        OpalPeerCastMutex.endReceiving("desktop_viewer")
        assertTrue(OpalPeerCastMutex.isReceivingActive)

        // Closing final receiver session returns mutex to IDLE
        OpalPeerCastMutex.endReceiving("floating_app")
        assertFalse(OpalPeerCastMutex.isReceivingActive)
    }

    @Test
    fun parsesPendingPairingAndTrustedPeersJson() {
        assertNull(PairingJsonParser.parsePending(""))
        val req = PairingJsonParser.parsePending(
            """{"requestId":"abc123","clientId":"android-1","clientName":"Xiaomi Pad","peerIp":"192.168.31.105","createdAtMs":1000,"expiresAtMs":61000}""",
        )
        assertNotNull(req)
        assertEquals("abc123", req!!.requestId)
        assertEquals("Xiaomi Pad", req.clientName)
        assertEquals("192.168.31.105", req.peerIp)

        val peers = PairingJsonParser.parseTrustedPeers(
            """[{"clientId":"android-1","clientName":"Xiaomi Pad","createdAtMs":1234},{"clientId":"mac-2","clientName":"MacBook","createdAtMs":5678}]""",
        )
        assertEquals(2, peers.size)
        assertEquals("android-1", peers[0].clientId)
        assertEquals("MacBook", peers[1].clientName)
    }
}
