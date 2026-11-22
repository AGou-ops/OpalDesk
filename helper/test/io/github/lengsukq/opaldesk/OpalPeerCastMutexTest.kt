package io.github.lengsukq.opaldesk

import io.github.lengsukq.opaldesk.cast.OpalPeerCastMutex
import org.junit.Assert.assertFalse
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
}
