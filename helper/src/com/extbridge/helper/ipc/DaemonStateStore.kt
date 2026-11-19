package com.extbridge.helper.ipc

import com.extbridge.helper.protocol.DaemonStatus
import com.extbridge.helper.protocol.SysStatus
import com.extbridge.helper.protocol.WindowStateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single authoritative reactive store for polled daemon state.
 * Backed by immutable data classes so `StateFlow` structural equality eliminates redundant UI redraws.
 */
object DaemonStateStore {
    private val _status = MutableStateFlow(DaemonStatus())
    val status: StateFlow<DaemonStatus> = _status.asStateFlow()

    private val _sysStatus = MutableStateFlow(SysStatus())
    val sysStatus: StateFlow<SysStatus> = _sysStatus.asStateFlow()

    private val _windowSnapshot = MutableStateFlow(WindowStateSnapshot())
    val windowSnapshot: StateFlow<WindowStateSnapshot> = _windowSnapshot.asStateFlow()

    fun updateStatus(next: DaemonStatus) {
        _status.value = next
    }

    fun transformStatus(transform: (DaemonStatus) -> DaemonStatus) {
        _status.update(transform)
    }

    fun updateSysStatus(next: SysStatus) {
        if (next.valid) {
            _sysStatus.value = next
        }
    }

    fun updateWindowSnapshot(next: WindowStateSnapshot) {
        _windowSnapshot.value = next
    }

    fun resetDisconnected() {
        _status.value = DaemonStatus()
        _sysStatus.value = SysStatus()
        _windowSnapshot.value = WindowStateSnapshot()
    }
}
