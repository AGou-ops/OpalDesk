package io.github.lengsukq.opaldesk.cast

import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

data class DiscoveredOpalPeer(
    val serviceName: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val shortFingerprint: String = "",
)

/**
 * Standard AOSP [NsdManager] mDNS advertiser & browser for `_opaldesk._tcp.` enabling
 * automatic zero-config discovery between Android <-> Android and Android <-> Mac OpalDesk nodes.
 */
object OpalPeerDiscovery {
    private const val TAG = "OpalPeerNsd"
    const val SERVICE_TYPE = "_opaldesk._tcp."
    const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

    /**
     * On Android 17 (API 37+), calling NsdManager.discoverServices without ACCESS_LOCAL_NETWORK
     * launches the system NsdPickerActivity modal on every call. Guard all NSD calls behind this check.
     */
    fun hasLocalNetworkPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 37) return true
        return try {
            ctx.checkSelfPermission(PERMISSION_ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private val peersByKey = ConcurrentHashMap<String, DiscoveredOpalPeer>()
    private val _discoveredPeersFlow = MutableStateFlow<List<DiscoveredOpalPeer>>(emptyList())
    val discoveredPeersFlow: StateFlow<List<DiscoveredOpalPeer>> = _discoveredPeersFlow.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var registeredPort: Int = -1

    @Volatile
    private var registeredFp: String = ""

    @Volatile
    private var localLanIp: String = ""

    fun syncAdvertise(ctx: Context, enabled: Boolean, port: Int, shortFingerprint: String) {
        val shouldAdvertise = enabled && port > 0 && !OpalPeerCastMutex.isReceivingActive && hasLocalNetworkPermission(ctx)
        if (!shouldAdvertise) {
            stopAdvertise()
            return
        }
        if (regListener != null && registeredPort == port && registeredFp == shortFingerprint) {
            return
        }
        stopAdvertise()
        val mgr = (ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
        nsdManager = mgr
        val info = NsdServiceInfo().apply {
            serviceName = "OpalDesk-${Build.MODEL}".take(48)
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("device", Build.MODEL.orEmpty())
            if (shortFingerprint.isNotBlank()) setAttribute("fp", shortFingerprint)
            setAttribute("v", "1")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Advertised ${serviceInfo.serviceName} on :$port")
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD advertise failed code=$errorCode")
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            regListener = listener
            registeredPort = port
            registeredFp = shortFingerprint
        } catch (t: Throwable) {
            Log.w(TAG, "registerService failed: ${t.message}")
        }
    }

    fun stopAdvertise() {
        val mgr = nsdManager
        val listener = regListener
        regListener = null
        registeredPort = -1
        registeredFp = ""
        if (mgr != null && listener != null) {
            try {
                mgr.unregisterService(listener)
            } catch (_: Throwable) {
            }
        }
    }

    fun startBrowsing(ctx: Context, selfLanIp: String) {
        localLanIp = selfLanIp.trim()
        if (!hasLocalNetworkPermission(ctx)) {
            stopBrowsing()
            return
        }
        if (discListener != null) return
        val mgr = (ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager) ?: return
        nsdManager = mgr
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discListener = null
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discListener = null
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                if (!service.serviceType.contains("_opaldesk")) return
                resolvePeer(mgr, service)
            }
            override fun onServiceLost(service: NsdServiceInfo) {
                val removed = peersByKey.entries.removeIf { it.value.serviceName == service.serviceName }
                if (removed) publishSorted()
            }
        }
        try {
            mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discListener = listener
        } catch (t: Throwable) {
            Log.w(TAG, "discoverServices failed: ${t.message}")
        }
    }

    fun stopBrowsing() {
        val mgr = nsdManager
        val listener = discListener
        discListener = null
        if (mgr != null && listener != null) {
            try {
                mgr.stopServiceDiscovery(listener)
            } catch (_: Throwable) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolvePeer(mgr: NsdManager, service: NsdServiceInfo) {
        try {
            mgr.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        val hostAddr = resolved.host
                        val ip = (hostAddr as? Inet4Address)?.hostAddress
                            ?: hostAddr?.hostAddress.orEmpty()
                        val port = resolved.port
                        if (ip.isBlank() || port <= 0) return
                        if (OpalPeerCastMutex.isSelfAddress(ip, localLanIp)) return
                        val attrs = resolved.attributes
                        val devName = attrs?.get("device")?.let { String(it, StandardCharsets.UTF_8) }
                            ?.takeIf { it.isNotBlank() }
                            ?: resolved.serviceName.removePrefix("OpalDesk-").ifBlank { resolved.serviceName }
                        val fp = attrs?.get("fp")?.let { String(it, StandardCharsets.UTF_8) }.orEmpty()
                        peersByKey["$ip:$port"] = DiscoveredOpalPeer(
                            serviceName = resolved.serviceName,
                            displayName = devName,
                            host = ip,
                            port = port,
                            shortFingerprint = fp,
                        )
                        publishSorted()
                    }
                },
            )
        } catch (_: Throwable) {
        }
    }

    private fun publishSorted() {
        _discoveredPeersFlow.value = peersByKey.values.sortedBy { it.displayName }
    }
}
