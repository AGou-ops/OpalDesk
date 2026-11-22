package io.github.lengsukq.opaldesk.ipc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Parcel
import android.util.Log
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.InputBackend
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.LayoutMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MirrorMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.MirrorSideMode
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.SinkType
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol.TxCode
import io.github.lengsukq.opaldesk.protocol.DaemonStatus
import io.github.lengsukq.opaldesk.protocol.DisplayGeometry
import io.github.lengsukq.opaldesk.protocol.TaskSnapshotData

/**
 * Low-level Binder Parcel decoders for large binary/versioned daemon replies
 * (`TxCode.GET_STATUS` and `TxCode.CAPTURE_TASK_SNAPSHOT`).
 */
internal object BridgeParcelCodecs {
    private const val TAG = "ExtParcelCodec"

    @Volatile
    private var hwBufSnapshotBlocked = false

    fun readDaemonStatus(reply: Parcel): DaemonStatus {
        val extId = reply.readInt()
        val vdId = reply.readInt()
        val physW = reply.readInt()
        val physH = reply.readInt()
        val physFps = reply.readInt() / 100f
        val virtW = reply.readInt()
        val virtH = reply.readInt()
        val virtDpi = reply.readInt()
        val virtFps = reply.readInt() / 100f
        val inputToExt = reply.readInt()
        val cursorX = reply.readInt()
        val cursorY = reply.readInt()
        val extInputCnt = reply.readInt()
        val modesRaw = reply.readString().orEmpty()
        val renderMode = reply.readString()?.takeIf { it.isNotEmpty() } ?: LayoutMode.NONE

        var mirrorMode = MirrorMode.DEX
        var fullscreenPolicy = 1
        if (reply.dataAvail() >= 8) {
            mirrorMode = reply.readInt()
            fullscreenPolicy = reply.readInt()
        }
        val virtRot = if (reply.dataAvail() >= 4) reply.readInt() else 0
        val injectStatus = if (reply.dataAvail() >= 4) reply.readInt() else InputBackend.INJECT_STATUS_SYSTEM

        var splitActive = 0
        var splitRatioPct = 50
        var splitSlot0Pkg = ""
        var splitSlot1Pkg = ""
        if (reply.dataAvail() >= 8) {
            splitActive = reply.readInt()
            splitRatioPct = reply.readInt()
            splitSlot0Pkg = reply.readString().orEmpty()
            splitSlot1Pkg = reply.readString().orEmpty()
        }

        var mirrorSideMode = MirrorSideMode.ALIGN_LEFT
        var sideVdId = -1
        var d0W = 0
        var d0H = 0
        var mirX = 0
        var mirY = 0
        var mirW = 0
        var mirH = 0
        if (reply.dataAvail() >= 32) {
            mirrorSideMode = reply.readInt()
            sideVdId = reply.readInt()
            d0W = reply.readInt()
            d0H = reply.readInt()
            mirX = reply.readInt()
            mirY = reply.readInt()
            mirW = reply.readInt()
            mirH = reply.readInt()
        }

        val sinkType = if (reply.dataAvail() >= 4) reply.readInt() else SinkType.NONE
        val remotePeerName = if (reply.dataAvail() >= 4) reply.readString().orEmpty() else ""

        var streamBitrateKbps = 0
        var streamFpsX100 = 0
        var wifiServerEnabled = 0
        var wifiServerPort = 0
        if (reply.dataAvail() >= 16) {
            streamBitrateKbps = reply.readInt()
            streamFpsX100 = reply.readInt()
            wifiServerEnabled = reply.readInt()
            wifiServerPort = reply.readInt()
        }
        val wifiCertFingerprint = if (reply.dataAvail() >= 4) reply.readString().orEmpty() else ""
        val activePairingPin = if (reply.dataAvail() >= 4) reply.readString().orEmpty() else ""

        val adaptiveSingleEnabled = if (reply.dataAvail() >= 4) reply.readInt() else 1
        var defaultSingleWidthDp = DisplayGeometry.DEFAULT_SINGLE_WIDTH_DP
        if (reply.dataAvail() >= 4) {
            val wDp = reply.readInt()
            if (wDp in DisplayGeometry.MIN_SINGLE_WIDTH_DP..DisplayGeometry.MAX_SINGLE_WIDTH_DP) {
                defaultSingleWidthDp = wDp
            }
        }
        val activeLayoutMode = if (reply.dataAvail() >= 4) {
            reply.readString()?.takeIf { it.isNotBlank() } ?: LayoutMode.FULLSCREEN
        } else {
            LayoutMode.FULLSCREEN
        }

        return DaemonStatus(
            connected = true,
            extId = extId,
            vdId = vdId,
            physW = physW,
            physH = physH,
            physFps = physFps,
            virtW = virtW,
            virtH = virtH,
            virtDpi = virtDpi,
            virtFps = virtFps,
            inputToExt = inputToExt,
            cursorX = cursorX,
            cursorY = cursorY,
            extInputCnt = extInputCnt,
            modesRaw = modesRaw,
            renderMode = renderMode,
            mirrorMode = mirrorMode,
            fullscreenPolicy = fullscreenPolicy,
            virtRot = virtRot,
            injectStatus = injectStatus,
            splitActive = splitActive,
            splitRatioPct = splitRatioPct,
            splitSlot0Pkg = splitSlot0Pkg,
            splitSlot1Pkg = splitSlot1Pkg,
            mirrorSideMode = mirrorSideMode,
            sideVdId = sideVdId,
            d0W = d0W,
            d0H = d0H,
            mirX = mirX,
            mirY = mirY,
            mirW = mirW,
            mirH = mirH,
            sinkType = sinkType,
            remotePeerName = remotePeerName,
            streamBitrateKbps = streamBitrateKbps,
            streamFpsX100 = streamFpsX100,
            wifiServerEnabled = wifiServerEnabled,
            wifiServerPort = wifiServerPort,
            wifiCertFingerprint = wifiCertFingerprint,
            activePairingPin = activePairingPin,
            adaptiveSingleEnabled = adaptiveSingleEnabled,
            defaultSingleWidthDp = defaultSingleWidthDp,
            activeLayoutMode = activeLayoutMode,
        )
    }

    fun captureTaskSnapshot(taskId: Int, pkg: String?, live: Boolean): TaskSnapshotData {
        if (!hwBufSnapshotBlocked) {
            captureInternal(taskId, pkg, live, forceJpeg = false)?.let { return it }
        }
        return captureInternal(taskId, pkg, live, forceJpeg = true) ?: TaskSnapshotData()
    }

    private fun captureInternal(
        taskId: Int,
        pkg: String?,
        live: Boolean,
        forceJpeg: Boolean,
    ): TaskSnapshotData? {
        val svc = BinderTransport.getService() ?: return TaskSnapshotData()
        val data = BinderTransport.obtainInterfaceParcel()
        val reply = Parcel.obtain()
        var hb: HardwareBuffer? = null
        return try {
            val flags = (if (live) 1 else 0) or (if (forceJpeg) 2 else 0)
            data.writeInt(taskId)
            data.writeString(pkg.orEmpty())
            data.writeInt(flags)
            if (!svc.transact(TxCode.CAPTURE_TASK_SNAPSHOT, data, reply, 0) || reply.dataAvail() < 4) {
                return fallbackOrEmpty(forceJpeg)
            }
            val bounds = if (reply.readInt() == 1 && reply.dataAvail() >= 16) {
                Rect(reply.readInt(), reply.readInt(), reply.readInt(), reply.readInt())
            } else {
                null
            }
            if (reply.dataAvail() < 4) return TaskSnapshotData(bounds = bounds)
            when (reply.readInt()) {
                1 -> {
                    val csOrd = reply.readInt()
                    hb = HardwareBuffer.CREATOR.createFromParcel(reply)
                    val bmp = wrapHardwareBufferBitmap(hb, csOrd)
                    if (bmp != null) {
                        TaskSnapshotData(bitmap = bmp, bounds = bounds, ownBitmap = true)
                    } else {
                        fallbackOrEmpty(forceJpeg)
                    }
                }
                2 -> {
                    val bytes = reply.createByteArray()
                    val bmp = if (bytes != null && bytes.isNotEmpty()) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } else {
                        null
                    }
                    TaskSnapshotData(bitmap = bmp, bounds = bounds, ownBitmap = bmp != null)
                }
                else -> TaskSnapshotData(bounds = bounds)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "captureTaskSnapshot(forceJpeg=$forceJpeg) failed for pkg=$pkg", t)
            fallbackOrEmpty(forceJpeg)
        } finally {
            if (hb != null && !hb.isClosed) {
                try {
                    hb.close()
                } catch (t: Throwable) {
                    Log.d(TAG, "HardwareBuffer.close() failed: ${t.message}")
                }
            }
            data.recycle()
            reply.recycle()
        }
    }

    private fun wrapHardwareBufferBitmap(hb: HardwareBuffer?, csOrd: Int): Bitmap? {
        if (hb == null || hb.isClosed || hb.width <= 0 || hb.height <= 0) return null
        val names = ColorSpace.Named.entries
        val cs = ColorSpace.get(if (csOrd in names.indices) names[csOrd] else ColorSpace.Named.SRGB)
        return Bitmap.wrapHardwareBuffer(hb, cs)
    }

    private fun fallbackOrEmpty(forceJpeg: Boolean): TaskSnapshotData? {
        if (!forceJpeg) {
            hwBufSnapshotBlocked = true
            return null
        }
        return TaskSnapshotData()
    }
}
