package com.extbridge.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.extbridge.helper.protocol.BridgeProtocol.HelperActions
import com.extbridge.helper.protocol.IpcTrustVerifier

class BridgeProvider : ContentProvider() {
    companion object {
        private const val TAG = "ExtBridgeProvider"
        private val sProcToken = Binder()
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        if (!IpcTrustVerifier.isTrustedCallerUid(callerUid)) {
            Log.w(TAG, "Rejected BridgeProvider.call($method) from untrusted uid=$callerUid")
            return Bundle().apply { putBoolean("ok", false) }
        }
        return when (method) {
            HelperActions.PROVIDER_METHOD_SET_BINDER -> handleSetBinder(extras)
            HelperActions.PROVIDER_METHOD_SYNC_STATE -> handleSyncState()
            HelperActions.PROVIDER_METHOD_MIR_EDGE_CAPTURE -> handleMirrorEdgeCapture(extras)
            else -> Bundle()
        }
    }

    private fun handleSetBinder(extras: Bundle?): Bundle {
        if (extras == null || BridgeService.sUserStopping) {
            return Bundle().apply { putBoolean("ok", false) }
        }
        val cb = extras.getBinder(HelperActions.EXTRA_CALLBACK_BINDER)
        if (!IpcTrustVerifier.isValidDaemonBinder(cb)) {
            return Bundle()
        }
        BridgeClient.setBinder(cb)
        BridgeService.ensureStarted(context)
        BridgeService.refreshFromReceiver(context)
        return Bundle().apply {
            putBoolean("ok", true)
            putBinder(HelperActions.EXTRA_PROC_TOKEN, sProcToken)
        }
    }

    private fun handleSyncState(): Bundle {
        val ctx = context
        if (ctx != null && !BridgeService.sUserStopping) {
            BridgeService.refreshFromReceiver(ctx)
            try {
                val syncIntent = Intent(HelperActions.STATE_SYNC).setPackage(ctx.packageName)
                ctx.sendBroadcast(syncIntent)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to broadcast STATE_SYNC", t)
            }
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    private fun handleMirrorEdgeCapture(extras: Bundle?): Bundle {
        if (extras != null && !BridgeService.sUserStopping) {
            val enter = extras.getBoolean(HelperActions.EXTRA_EDGE_ENTER, true)
            BridgeService.onEdgeCaptureFromDaemon(enter)
        }
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun query(
        u: Uri,
        p: Array<out String>?,
        s: String?,
        sa: Array<out String>?,
        so: String?,
    ): Cursor? = null

    override fun getType(u: Uri): String? = null

    override fun insert(u: Uri, v: ContentValues?): Uri? = null

    override fun delete(u: Uri, s: String?, sa: Array<out String>?): Int = 0

    override fun update(u: Uri, v: ContentValues?, s: String?, sa: Array<out String>?): Int = 0
}
