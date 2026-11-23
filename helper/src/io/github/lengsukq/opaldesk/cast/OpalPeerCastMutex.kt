package io.github.lengsukq.opaldesk.cast

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Strict one-way mutual-exclusion guard for Android <-> Android OpalCast peer streaming.
 *
 * Enforces the user invariant: two OpalDesk devices may cast in ONE direction at a time
 * (A -> B OR B -> A), NEVER simultaneously in both directions (A <-> B loop forbidden).
 */
object OpalPeerCastMutex {
    enum class Role { IDLE, SENDING, RECEIVING }

    data class CastRoleState(
        val role: Role = Role.IDLE,
        val peerName: String = "",
        val peerHost: String = "",
        val peerPort: Int = 0,
    ) {
        val isReceiving: Boolean get() = role == Role.RECEIVING
        val isSending: Boolean get() = role == Role.SENDING
    }

    data class ValidationResult(
        val allowed: Boolean,
        val reason: String = "",
    )

    private val _roleFlow = MutableStateFlow(CastRoleState())
    val roleFlow: StateFlow<CastRoleState> = _roleFlow.asStateFlow()

    private val activeReceiverTags = LinkedHashSet<String>()

    val isReceivingActive: Boolean
        get() = _roleFlow.value.role == Role.RECEIVING

    @Synchronized
    fun beginReceiving(host: String, port: Int, peerName: String = "", sessionTag: String = "default"): Boolean {
        if (_roleFlow.value.role == Role.SENDING) return false
        activeReceiverTags.add(sessionTag)
        _roleFlow.value = CastRoleState(
            role = Role.RECEIVING,
            peerName = peerName.ifBlank { host },
            peerHost = host,
            peerPort = port,
        )
        return true
    }

    @Synchronized
    fun updateReceivingPeerName(peerName: String) {
        val cur = _roleFlow.value
        if (cur.role == Role.RECEIVING && peerName.isNotBlank()) {
            _roleFlow.value = cur.copy(peerName = peerName)
        }
    }

    @Synchronized
    fun endReceiving(sessionTag: String = "default") {
        activeReceiverTags.remove(sessionTag)
        if (activeReceiverTags.isEmpty() && _roleFlow.value.role == Role.RECEIVING) {
            _roleFlow.value = CastRoleState(Role.IDLE)
        }
    }

    @Synchronized
    fun syncDaemonSendingState(daemonSending: Boolean, peerName: String) {
        val cur = _roleFlow.value
        if (daemonSending) {
            if (cur.role != Role.RECEIVING) {
                _roleFlow.value = CastRoleState(Role.SENDING, peerName = peerName)
            }
        } else if (cur.role == Role.SENDING) {
            _roleFlow.value = CastRoleState(Role.IDLE)
        }
    }

    /** Pure JVM-testable guard deciding whether this device may initiate outbound receiving (A <- B). */
    fun validateStartReceiving(
        daemonSending: Boolean,
        sendingPeerName: String,
        targetHost: String,
        selfLanIp: String,
    ): ValidationResult {
        if (daemonSending) {
            val peer = sendingPeerName.ifBlank { "另一台设备" }
            return ValidationResult(
                allowed = false,
                reason = "当前正在向 $peer 投屏，仅支持单向投屏；请先断开当前投屏后再接收",
            )
        }
        val cleanHost = targetHost.trim()
        if (cleanHost.isEmpty()) {
            return ValidationResult(false, "请输入对方设备的局域网 IP 地址")
        }
        if (isSelfAddress(cleanHost, selfLanIp)) {
            return ValidationResult(false, "不能向本机自身发起投屏，请选择局域网内另一台设备")
        }
        return ValidationResult(true)
    }

    /** Pure JVM-testable guard deciding whether this device may enable outbound Wi-Fi sending (A -> B). */
    fun validateEnableSending(clientReceiving: Boolean, receivingPeerName: String): ValidationResult {
        if (clientReceiving) {
            val peer = receivingPeerName.ifBlank { "另一台设备" }
            return ValidationResult(
                allowed = false,
                reason = "当前正在接收 $peer 的投屏，同一时间不可双向互投；请先断开接收",
            )
        }
        return ValidationResult(true)
    }

    /** Detects loopback and matching local LAN IPv4 addresses to prevent self-cast loops. */
    fun isSelfAddress(host: String, selfLanIp: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "0.0.0.0") return true
        if (h.startsWith("127.")) return true
        val self = selfLanIp.trim().lowercase()
        return self.isNotEmpty() && h == self
    }
}
