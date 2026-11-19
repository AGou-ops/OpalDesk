package com.extbridge.helper.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.extbridge.helper.AdbBootstrap
import java.util.ArrayDeque

/**
 * Serialized NsdManager mDNS discovery for Android Wireless Debugging pairing & connect services,
 * holding a non-reference-counted MulticastLock only while actively scanning.
 */
internal object AdbMdnsDiscovery {
    private const val TAG = "ExtAdbMdns"
    private const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
    private const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val state = AdbBootstrap.DiscoveryState()

    private var nsdManager: NsdManager? = null
    private var pairDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var connDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var listener: AdbBootstrap.DiscoveryListener? = null
    private var activePairSvcName = ""
    private var activeConnSvcName = ""
    private var resolving = false

    val isActive: Boolean
        @Synchronized get() = nsdManager != null

    @Synchronized
    fun getState(): AdbBootstrap.DiscoveryState = AdbBootstrap.DiscoveryState().apply {
        pairingHost = state.pairingHost
        pairingPort = state.pairingPort
        connectHost = state.connectHost
        connectPort = state.connectPort
    }

    @Synchronized
    fun clearListener() {
        listener = null
    }

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (nsdManager == null) start(ctx, listener)
    }

    @Synchronized
    fun start(ctx: Context, nextListener: AdbBootstrap.DiscoveryListener?) {
        listener = nextListener
        val appCtx = ctx.applicationContext
        if (nsdManager != null) {
            notifyListenerLocked()
            return
        }
        try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && multicastLock == null) {
                multicastLock = wm.createMulticastLock("extbridge_mdns").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "MulticastLock unavailable", t)
        }

        try {
            val nsd = appCtx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            nsdManager = nsd
            val pairDisc = createDiscoveryListener(isPairing = true)
            val connDisc = createDiscoveryListener(isPairing = false)
            pairDiscoveryListener = pairDisc
            connDiscoveryListener = connDisc
            nsd.discoverServices(SERVICE_PAIRING, NsdManager.PROTOCOL_DNS_SD, pairDisc)
            nsd.discoverServices(SERVICE_CONNECT, NsdManager.PROTOCOL_DNS_SD, connDisc)
        } catch (t: Throwable) {
            Log.e(TAG, "startDiscovery failed", t)
        }
    }

    @Synchronized
    fun stop() {
        listener = null
        val nsd = nsdManager
        if (nsd != null) {
            pairDiscoveryListener?.let { stopDiscoverySafely(nsd, it) }
            connDiscoveryListener?.let { stopDiscoverySafely(nsd, it) }
        }
        pairDiscoveryListener = null
        connDiscoveryListener = null
        nsdManager = null
        synchronized(resolveQueue) {
            resolveQueue.clear()
            resolving = false
        }
        multicastLock?.let { lock ->
            try {
                if (lock.isHeld) lock.release()
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to release MulticastLock: ${t.message}")
            }
            multicastLock = null
        }
    }

    private fun stopDiscoverySafely(nsd: NsdManager, disc: NsdManager.DiscoveryListener) {
        try {
            nsd.stopServiceDiscovery(disc)
        } catch (t: Throwable) {
            Log.d(TAG, "stopServiceDiscovery ignored: ${t.message}")
        }
    }

    private fun createDiscoveryListener(isPairing: Boolean): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) = Unit
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                Log.w(TAG, "onStartDiscoveryFailed type=$serviceType code=$errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo != null) enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                val lostName = serviceInfo?.serviceName.orEmpty()
                synchronized(this@AdbMdnsDiscovery) {
                    if (isPairing) {
                        if (lostName.isEmpty() || activePairSvcName.isEmpty() || lostName == activePairSvcName) {
                            state.pairingPort = 0
                            activePairSvcName = ""
                            notifyListenerLocked()
                        }
                    } else if (lostName.isEmpty() || activeConnSvcName.isEmpty() || lostName == activeConnSvcName) {
                        state.connectPort = 0
                        activeConnSvcName = ""
                        notifyListenerLocked()
                    }
                }
            }
        }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) {
            resolveQueue.addLast(info)
            pumpResolveLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveLocked() {
        val nsd = nsdManager
        if (resolving || resolveQueue.isEmpty() || nsd == null) return
        val next = resolveQueue.pollFirst() ?: return
        resolving = true
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    synchronized(resolveQueue) {
                        resolving = false
                        pumpResolveLocked()
                    }
                }

                override fun onServiceResolved(resolved: NsdServiceInfo?) {
                    if (resolved != null) handleResolved(resolved)
                    synchronized(resolveQueue) {
                        resolving = false
                        pumpResolveLocked()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "resolveService failed", t)
            resolving = false
            pumpResolveLocked()
        }
    }

    @Suppress("DEPRECATION")
    private fun handleResolved(resolved: NsdServiceInfo) {
        val type = resolved.serviceType.orEmpty()
        val addr = resolved.host
        if (!AdbProcessRunner.isLocalDeviceAddress(addr)) return
        var host = addr?.hostAddress.orEmpty().substringBefore('%')
        if (host.contains(':') && !host.startsWith("[")) {
            // Prefer local IPv4 for adb connect/pair when link-local IPv6 scope was stripped
            host = AdbProcessRunner.getLocalIpv4()
        }
        val port = resolved.port
        if (port <= 0) return
        val svcName = resolved.serviceName.orEmpty()

        synchronized(this) {
            when {
                type.contains("pairing") -> {
                    state.pairingHost = host
                    state.pairingPort = port
                    activePairSvcName = svcName
                }
                type.contains("connect") -> {
                    state.connectHost = host
                    state.connectPort = port
                    activeConnSvcName = svcName
                }
            }
            notifyListenerLocked()
        }
    }

    private fun notifyListenerLocked() {
        val target = listener ?: return
        val snap = getState()
        mainHandler.post { target.onDiscoveryUpdated(snap) }
    }
}
