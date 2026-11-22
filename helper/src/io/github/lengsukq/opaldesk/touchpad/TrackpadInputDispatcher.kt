package io.github.lengsukq.opaldesk.touchpad

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.lengsukq.opaldesk.BridgeClient
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MouseAction
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Single-threaded FIFO mouse/keyboard IPC dispatcher preserving strict LEFT_DOWN -> MOVE -> LEFT_UP
 * ordering, relative move coalescing, and non-blocking auxiliary control queries.
 */
class TrackpadInputDispatcher(
    private val onMouseResult: (actionType: Int, cx: Int, cy: Int, injectStatus: Int) -> Unit,
) {
    companion object {
        private const val TAG = "ExtTrackpadDisp"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExec = Executors.newSingleThreadExecutor()
    private val controlExec = Executors.newSingleThreadExecutor()
    private val moveLock = Any()

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var moveInFlight = false

    @Volatile
    private var destroyed = false

    @Volatile
    private var remotePrimaryHeld = false

    fun sendMouse(actionType: Int, a1: Float = 0f, a2: Float = 0f) {
        if (destroyed || ioExec.isShutdown) return
        when (actionType) {
            MouseAction.LEFT_DOWN -> remotePrimaryHeld = true
            MouseAction.LEFT_UP, MouseAction.LEFT_CLICK -> remotePrimaryHeld = false
        }
        if (actionType == MouseAction.MOVE_REL) {
            enqueueCoalescedMove(a1, a2)
            return
        }
        submitIo("sendMouse($actionType)") {
            if (destroyed) return@submitIo
            val xy = BridgeClient.sendMouse(actionType, a1, a2)
            dispatchMouseResult(actionType, xy)
        }
    }

    private fun enqueueCoalescedMove(dxAdd: Float, dyAdd: Float) {
        val shouldLaunch = synchronized(moveLock) {
            pendingDx += dxAdd
            pendingDy += dyAdd
            if (moveInFlight) false else {
                moveInFlight = true
                true
            }
        }
        if (!shouldLaunch) return
        try {
            ioExec.execute { drainCoalescedMoves() }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to schedule coalesced mouse move", t)
            synchronized(moveLock) {
                pendingDx = 0f
                pendingDy = 0f
                moveInFlight = false
            }
        }
    }

    private fun drainCoalescedMoves() {
        try {
            while (!destroyed && !ioExec.isShutdown) {
                var dx: Float
                var dy: Float
                synchronized(moveLock) {
                    dx = pendingDx
                    dy = pendingDy
                    pendingDx = 0f
                    pendingDy = 0f
                    if (abs(dx) < 0.01f && abs(dy) < 0.01f) {
                        moveInFlight = false
                        return
                    }
                }
                val xy = BridgeClient.sendMouse(MouseAction.MOVE_REL, dx, dy)
                dispatchMouseResult(MouseAction.MOVE_REL, xy)
            }
        } finally {
            synchronized(moveLock) { moveInFlight = false }
        }
    }

    private fun dispatchMouseResult(actionType: Int, xy: IntArray?) {
        if (destroyed || xy == null || xy.size < 2) return
        val injectStatus = if (xy.size >= 3) xy[2] else 1
        mainHandler.post {
            if (!destroyed) onMouseResult(actionType, xy[0], xy[1], injectStatus)
        }
    }

    /**
     * Releases any stuck remote primary button on the exact same single-threaded FIFO executor
     * so LEFT_UP can never overtake an already-enqueued LEFT_DOWN.
     */
    fun releaseHeldMouseIfNeeded(dragLocked: Boolean): Boolean {
        val wasHeld = remotePrimaryHeld || dragLocked
        remotePrimaryHeld = false
        if (!wasHeld) return false
        synchronized(moveLock) {
            pendingDx = 0f
            pendingDy = 0f
        }
        submitIo("releaseHeldMouse") {
            BridgeClient.sendMouse(MouseAction.LEFT_UP, 0f, 0f)
        }
        return true
    }

    fun sendKey(keyCode: Int, metaState: Int) {
        submitIo("sendKey($keyCode)") { BridgeClient.sendKey(keyCode, metaState) }
    }

    fun sendAscii(text: String) {
        submitIo("sendAscii") { BridgeClient.sendAscii(text) }
    }

    fun sendPasteDelayed(useCtrlV: Boolean, delayMs: Long = 35L) {
        mainHandler.postDelayed({
            if (!destroyed) {
                submitIo("sendPaste") { BridgeClient.sendPaste(useCtrlV) }
            }
        }, delayMs)
    }

    fun sendBack() = submitControl("sendBack") { BridgeClient.sendBack() }

    fun relaunchLauncher() = submitControl("relaunchLauncher") { BridgeClient.relaunchLauncher() }

    fun toggleInput(onResult: (Int) -> Unit) = submitControl("toggleInput") {
        val mode = BridgeClient.toggleInput()
        mainHandler.post { if (!destroyed) onResult(mode) }
    }

    fun refreshStatus(onResult: (DaemonStatus) -> Unit) = submitControl("refreshStatus") {
        val st = BridgeClient.getStatus()
        mainHandler.post { if (!destroyed) onResult(st) }
    }

    fun postDelayed(delayMs: Long, action: () -> Unit) {
        mainHandler.postDelayed({ if (!destroyed) action() }, delayMs)
    }

    fun shutdown() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        ioExec.shutdown()
        controlExec.shutdownNow()
    }

    private fun submitIo(label: String, block: () -> Unit) {
        if (ioExec.isShutdown) return
        try {
            ioExec.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "Trackpad IO task failed: $label", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Trackpad IO task rejected: $label", t)
        }
    }

    private fun submitControl(label: String, block: () -> Unit) {
        if (destroyed || controlExec.isShutdown) return
        try {
            controlExec.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "Trackpad control task failed: $label", t)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Trackpad control task rejected: $label", t)
        }
    }
}
