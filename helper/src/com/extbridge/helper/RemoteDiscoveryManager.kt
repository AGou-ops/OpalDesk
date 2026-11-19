package com.extbridge.helper

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * Publishes `_extbridge._tcp.` via Android [NsdManager] when Wireless Remote Display mode
 * is enabled so ExtBridge for macOS discovers this device automatically over Bonjour/mDNS.
 */
class RemoteDiscoveryManager(context: Context) {

    companion object {
        private const val TAG = "ExtRemoteDiscovery"
        const val SERVICE_TYPE = "_extbridge._tcp."
        const val PROTOCOL_VERSION = "1"

        fun resolveDeviceName(ctx: Context): String {
            val fromGlobal = runCatching {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()?.trim()
            if (!fromGlobal.isNullOrEmpty()) return fromGlobal
            val model = Build.MODEL?.trim().orEmpty()
            return model.ifEmpty { "Android" }
        }

        fun sanitizeServiceName(deviceName: String): String {
            val cleaned = deviceName
                .replace(Regex("[^\\p{L}\\p{N}\\-_ ]"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
            val base = cleaned.ifEmpty { "ExtBridge" }
            val prefixed = if (base.startsWith("ExtBridge", ignoreCase = true)) {
                base
            } else {
                "ExtBridge-$base"
            }
            return prefixed.take(48)
        }

        fun buildTxtAttributes(
            deviceName: String,
            model: String,
            certFingerprint: String,
        ): Map<String, String> {
            val cleanFp = certFingerprint
                .replace(":", "")
                .replace("-", "")
                .trim()
                .lowercase(Locale.ROOT)
                .take(16)
            return linkedMapOf(
                "name" to deviceName.trim().ifEmpty { model.trim().ifEmpty { "Android" } }.take(64),
                "model" to model.trim().ifEmpty { "Android" }.take(48),
                "fp" to cleanFp,
                "ver" to PROTOCOL_VERSION,
            )
        }

        fun buildRegistrationSignature(
            port: Int,
            certFingerprint: String,
            deviceName: String,
            model: String,
        ): String {
            val fp16 = certFingerprint
                .replace(":", "")
                .replace("-", "")
                .trim()
                .lowercase(Locale.ROOT)
                .take(16)
            return "$port|$fp16|${deviceName.trim()}|${model.trim()}"
        }
    }

    private val appCtx: Context = context.applicationContext
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var activeSignature: String? = null
    private var registeredServiceName: String? = null

    @Synchronized
    fun sync(enabled: Boolean, port: Int, certFingerprint: String) {
        if (!enabled || port <= 0) {
            stop()
            return
        }
        val deviceName = resolveDeviceName(appCtx)
        val model = Build.MODEL ?: "Android"
        val sig = buildRegistrationSignature(port, certFingerprint, deviceName, model)
        if (sig == activeSignature && registrationListener != null) {
            return
        }
        unregisterInternal()
        registerInternal(port, certFingerprint, deviceName, model, sig)
    }

    @Synchronized
    fun stop() {
        unregisterInternal()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wm.createMulticastLock("extbridge_remote_mdns")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to acquire MulticastLock", t)
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        try {
            if (lock.isHeld) lock.release()
        } catch (t: Throwable) {
            Log.d(TAG, "MulticastLock release ignored: ${t.message}")
        }
    }

    private fun registerInternal(
        port: Int,
        certFingerprint: String,
        deviceName: String,
        model: String,
        signature: String,
    ) {
        acquireMulticastLock()
        val mgr = nsdManager ?: (appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager)
        if (mgr == null) {
            Log.w(TAG, "NsdManager unavailable")
            return
        }
        nsdManager = mgr

        val info = NsdServiceInfo().apply {
            serviceName = sanitizeServiceName(deviceName)
            serviceType = SERVICE_TYPE
            setPort(port)
            val attrs = buildTxtAttributes(deviceName, model, certFingerprint)
            for ((k, v) in attrs) {
                setAttribute(k, v)
            }
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                synchronized(this@RemoteDiscoveryManager) {
                    registeredServiceName = serviceInfo.serviceName
                }
                Log.i(TAG, "mDNS registered: ${serviceInfo.serviceName} ($SERVICE_TYPE) port=$port")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS registration failed: code=$errorCode")
                synchronized(this@RemoteDiscoveryManager) {
                    if (registrationListener === this) {
                        registrationListener = null
                        activeSignature = null
                        registeredServiceName = null
                    }
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "mDNS unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "mDNS unregistration failed: code=$errorCode")
            }
        }

        try {
            registrationListener = listener
            activeSignature = signature
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            Log.e(TAG, "registerService threw", t)
            registrationListener = null
            activeSignature = null
            registeredServiceName = null
        }
    }

    private fun unregisterInternal() {
        val mgr = nsdManager
        val listener = registrationListener
        registrationListener = null
        activeSignature = null
        registeredServiceName = null
        if (mgr != null && listener != null) {
            try {
                mgr.unregisterService(listener)
            } catch (t: Throwable) {
                Log.d(TAG, "unregisterService ignored: ${t.message}")
            }
        }
    }
}
