package io.github.lengsukq.opaldesk.ipc

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import io.github.lengsukq.opaldesk.protocol.BridgeProtocol
import io.github.lengsukq.opaldesk.protocol.IpcTrustVerifier
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Low-level Binder & abstract LocalSocket transport to the OpalDesk daemon.
 * Centralizes Parcel obtain/recycle, death-recipient cleanup, and serialized ctl socket commands.
 */
object BinderTransport {
    private const val TAG = "ExtBinderTransport"
    private const val CTL_SOCKET_TIMEOUT_MS = 2500

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var linkedBinder: IBinder? = null

    private val binderListeners = CopyOnWriteArraySet<Runnable>()

    private val ctlExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ExtCtlSocket").apply { isDaemon = true }
    }

    fun addOnBinderReady(listener: Runnable?) {
        if (listener != null) binderListeners.add(listener)
    }

    fun removeOnBinderReady(listener: Runnable?) {
        if (listener != null) binderListeners.remove(listener)
    }

    fun setBinder(candidate: IBinder?) {
        if (!IpcTrustVerifier.isValidDaemonBinder(candidate)) return
        val valid = candidate ?: return
        binder = valid
        linkBinderDeath(valid)
        for (listener in binderListeners) {
            try {
                listener.run()
            } catch (t: Throwable) {
                Log.w(TAG, "Binder-ready listener threw", t)
            }
        }
    }

    fun getService(): IBinder? {
        val current = binder ?: return null
        if (current.pingBinder()) return current
        binder = null
        DaemonStateStore.resetDisconnected()
        return null
    }

    fun clearBinder() {
        binder = null
        DaemonStateStore.resetDisconnected()
    }

    @Synchronized
    private fun linkBinderDeath(target: IBinder) {
        if (target === linkedBinder) return
        linkedBinder = target
        try {
            target.linkToDeath({
                if (binder === target) {
                    binder = null
                    DaemonStateStore.resetDisconnected()
                }
                if (linkedBinder === target) {
                    linkedBinder = null
                }
            }, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "linkToDeath failed for daemon binder", t)
            if (linkedBinder === target) linkedBinder = null
        }
    }

    fun sendCtlSocketCmdAsync(cmd: String) {
        ctlExecutor.execute {
            var sock: LocalSocket? = null
            try {
                sock = LocalSocket()
                sock.connect(
                    LocalSocketAddress(
                        BridgeProtocol.CTL_SOCKET_NAME,
                        LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
                val peer = sock.peerCredentials
                if (peer == null || !IpcTrustVerifier.isTrustedDaemonUid(peer.uid)) {
                    Log.w(TAG, "Rejected untrusted ctl socket peer uid=${peer?.uid}")
                    return@execute
                }
                sock.soTimeout = CTL_SOCKET_TIMEOUT_MS
                sock.outputStream.write("$cmd\n".toByteArray(StandardCharsets.UTF_8))
                sock.outputStream.flush()
                sock.inputStream.read()
            } catch (t: Throwable) {
                Log.d(TAG, "ctl socket cmd=$cmd unavailable: ${t.message}")
            } finally {
                try {
                    sock?.close()
                } catch (t: Throwable) {
                    Log.d(TAG, "ctl socket close failed: ${t.message}")
                }
            }
        }
    }

    fun requestBinder(ctx: Context?) {
        sendCtlSocketCmdAsync("NEED_BINDER")
        if (ctx == null) return
        try {
            val opts: Bundle? = if (Build.VERSION.SDK_INT >= 34) {
                BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
            } else {
                null
            }
            for (pkg in arrayOf("android", "com.android.shell")) {
                val intent = Intent(BridgeProtocol.ACTION_NEED_BINDER).setPackage(pkg)
                if (opts != null) {
                    ctx.sendBroadcast(intent, null, opts)
                } else {
                    ctx.sendBroadcast(intent)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to broadcast NEED_BINDER", t)
        }
    }

    @PublishedApi
    internal fun obtainInterfaceParcel(): Parcel =
        Parcel.obtain().apply { writeInterfaceToken(BridgeProtocol.BINDER_DESC) }

    inline fun <T> transactOrNull(
        code: Int,
        write: Parcel.() -> Unit = {},
        read: Parcel.() -> T?,
    ): T? {
        val svc = getService() ?: return null
        val data = obtainInterfaceParcel()
        val reply = Parcel.obtain()
        return try {
            data.write()
            if (!svc.transact(code, data, reply, 0)) {
                Log.w("ExtBinderTransport", "Binder transact returned false for code=$code")
                return null
            }
            reply.read()
        } catch (t: Throwable) {
            Log.w("ExtBinderTransport", "Binder transact failed for code=$code", t)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    inline fun transactBool(
        code: Int,
        write: Parcel.() -> Unit = {},
    ): Boolean = transactOrNull(code, write) {
        dataAvail() >= 4 && readInt() == 1
    } ?: false

    inline fun transactInt(
        code: Int,
        defaultValue: Int = -1,
        write: Parcel.() -> Unit = {},
    ): Int = transactOrNull(code, write) {
        if (dataAvail() >= 4) readInt() else defaultValue
    } ?: defaultValue

    inline fun transactVoid(
        code: Int,
        write: Parcel.() -> Unit = {},
    ) {
        transactOrNull(code, write) { Unit }
    }

    fun transactOneWay(code: Int, write: Parcel.() -> Unit = {}) {
        val svc = getService() ?: return
        val data = obtainInterfaceParcel()
        try {
            data.write()
            if (!svc.transact(code, data, null, IBinder.FLAG_ONEWAY)) {
                Log.w(TAG, "One-way binder transact returned false for code=$code")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "One-way binder transact failed for code=$code", t)
        } finally {
            data.recycle()
        }
    }

    /**
     * Ultra-low-latency fire-and-forget Binder path for 120Hz+ local touch/scroll forwarding.
     * Skips the extra synchronous {@code pingBinder()} roundtrip (relying on {@code linkToDeath}
     * and {@code DeadObjectException} cleanup) and uses {@code FLAG_ONEWAY} for zero reply wait.
     */
    fun transactOneWayFast(code: Int, write: Parcel.() -> Unit = {}): Boolean {
        val svc = binder ?: return false
        val data = obtainInterfaceParcel()
        return try {
            data.write()
            svc.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: android.os.DeadObjectException) {
            if (binder === svc) {
                binder = null
                DaemonStateStore.resetDisconnected()
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Fast one-way binder transact failed for code=$code", t)
            false
        } finally {
            data.recycle()
        }
    }
}
