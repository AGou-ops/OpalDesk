package com.extbridge.helper

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

class BridgeProvider : ContentProvider() {
    companion object {
        private val sProcToken = Binder()
    }

    override fun onCreate(): Boolean = true

    private fun isTrustedCaller(): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == 0 || uid == 1000 || uid == 2000) return true
        return uid == Process.myUid()
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (!isTrustedCaller()) {
            return Bundle().apply { putBoolean("ok", false) }
        }
        if ("setBinder" == method && extras != null) {
            if (BridgeService.sUserStopping) {
                return Bundle().apply { putBoolean("ok", false) }
            }
            val b = extras.getBinder("cb")
            var descOk = false
            if (b != null && b.pingBinder()) {
                try {
                    descOk = BridgeClient.BINDER_DESC == b.interfaceDescriptor
                } catch (_: Throwable) {}
            }
            if (descOk) {
                BridgeClient.setBinder(b)
                BridgeService.ensureStarted(context)
                BridgeService.refreshFromReceiver(context)
                return Bundle().apply {
                    putBoolean("ok", true)
                    putBinder("proc", sProcToken)
                }
            }
        } else if ("syncState" == method) {
            val ctx = context
            if (ctx != null && !BridgeService.sUserStopping) {
                BridgeService.refreshFromReceiver(ctx)
                try {
                    val syncIntent = Intent("com.extbridge.helper.STATE_SYNC").setPackage(ctx.packageName)
                    ctx.sendBroadcast(syncIntent)
                } catch (_: Throwable) {}
            }
            return Bundle().apply { putBoolean("ok", true) }
        } else if ("mirEdgeCapture" == method && extras != null) {
            if (!BridgeService.sUserStopping) {
                val enter = extras.getBoolean("enter", true)
                BridgeService.onEdgeCaptureFromDaemon(enter)
            }
            return Bundle().apply { putBoolean("ok", true) }
        }
        return Bundle()
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
