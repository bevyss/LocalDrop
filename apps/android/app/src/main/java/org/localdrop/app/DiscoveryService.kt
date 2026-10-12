package org.localdrop.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground DNS-SD discovery and single-session TCP listener. */
class DiscoveryService(
    context: Context,
    private val localization: Localization,
    private val ownId: String,
    private val onPeer: (DiscoveryRecord) -> Unit,
    private val onLost: (String) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onIncoming: (Socket) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val sessions = Executors.newSingleThreadExecutor()
    private val sessionActive = AtomicBoolean(false)
    private val active = AtomicBoolean(false)
    private val queue = ArrayDeque<NsdServiceInfo>()
    private val available = mutableMapOf<String, NsdServiceInfo>()
    private val instanceIds = mutableMapOf<String, String>()
    private val seen = mutableMapOf<String, Long>()
    private var resolving = false
    private var socket: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registered = false
    private var discovering = false

    private val expire = object : Runnable {
        override fun run() {
            // Older NSD APIs emit found once. Re-resolve to refresh presence and TXT data.
            available.values.forEach { queue.addLast(it) }
            resolveNext()
            val cutoff = System.currentTimeMillis() - 90_000
            seen.filterValues { it < cutoff }.keys.toList().forEach { id ->
                seen.remove(id)
                onLost(id)
            }
            if (active.get()) main.postDelayed(this, 15_000)
        }
    }

    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "event=service_registered")
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            main.post { onStatus(localization.text("advertise_failed", code)) }
            Log.e(TAG, "event=registration_failed code=$code")
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) {
            Log.i(TAG, "event=service_unregistered")
        }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
            Log.e(TAG, "event=unregistration_failed code=$code")
        }
    }

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {
            Log.i(TAG, "event=discovery_started")
            main.post { onStatus(localization.text("searching")) }
        }
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!active.get() || !info.serviceType.contains("_localdrop._tcp")) return
            main.post {
                available[info.serviceName] = info
                queue.addLast(info)
                resolveNext()
            }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post {
                available.remove(info.serviceName)
                val id = instanceIds.remove(info.serviceName) ?: return@post
                seen.remove(id)
                onLost(id)
                Log.i(TAG, "event=peer_lost id=$id")
            }
        }
        override fun onDiscoveryStopped(type: String) {
            Log.i(TAG, "event=discovery_stopped")
        }
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            main.post { onStatus(localization.text("local_discovery_failed", code)) }
            Log.e(TAG, "event=discovery_start_failed code=$code")
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) {
            Log.e(TAG, "event=discovery_stop_failed code=$code")
        }
    }

    private fun resolveNext() {
        if (resolving || !active.get() || queue.isEmpty()) return
        resolving = true
        val item = queue.removeFirst()
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(item, object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                main.post {
                    val txt = info.attributes.mapValues { (_, bytes) -> bytes.toString(Charsets.UTF_8) }
                    val peer = DiscoveryRecord.parse(txt, info.port)
                    if (active.get() && peer != null && peer.id != ownId) {
                        instanceIds[info.serviceName] = peer.id
                        seen[peer.id] = System.currentTimeMillis()
                        onPeer(peer.copy(host = info.host?.hostAddress))
                        Log.i(TAG, "event=peer_seen id=${peer.id}")
                    }
                    resolving = false
                    resolveNext()
                }
            }
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                main.post {
                    Log.w(TAG, "event=resolve_failed code=$code")
                    resolving = false
                    resolveNext()
                }
            }
            })
        } catch (e: Exception) {
            Log.w(TAG, "event=resolve_start_failed", e)
            resolving = false
            resolveNext()
        }
    }

    fun start(deviceName: String) {
        check(active.compareAndSet(false, true))
        try {
            socket = ServerSocket(0)
            worker.execute {
                while (active.get()) {
                    try {
                        val client = socket?.accept() ?: break
                        if (!sessionActive.compareAndSet(false, true)) client.close()
                        else sessions.execute {
                            try { client.use(onIncoming) }
                            finally { sessionActive.set(false) }
                        }
                    } catch (_: Exception) { break }
                }
            }
            // Required on Android 12 and earlier to receive Wi-Fi multicast reliably.
            multicastLock = wifi.createMulticastLock("LocalDrop discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
            val advertisedName = DeviceName.normalize(deviceName)
            var instanceName = advertisedName
            while (instanceName.toByteArray(Charsets.UTF_8).size > 50)
                instanceName = instanceName.dropLast(1)
            val info = NsdServiceInfo().apply {
                serviceName = "$instanceName-${ownId.take(8)}"
                serviceType = DiscoveryRecord.SERVICE_TYPE
                port = socket!!.localPort
                setAttribute("txtvers", "1")
                setAttribute("pv", "1")
                setAttribute("id", ownId)
                setAttribute("name", advertisedName)
                setAttribute("type", "android")
                setAttribute("caps", "file1")
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            registered = true
            nsd.discoverServices(DiscoveryRecord.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
            discovering = true
            main.postDelayed(expire, 15_000)
            Log.i(TAG, "event=startup")
        } catch (e: Exception) {
            Log.e(TAG, "event=startup_failed", e)
            onStatus(localization.text("discovery_failed"))
            stop()
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        main.removeCallbacks(expire)
        if (discovering) try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) { }
        if (registered) try { nsd.unregisterService(registration) } catch (_: Exception) { }
        discovering = false
        registered = false
        socket?.close()
        socket = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        queue.clear()
        available.clear()
        instanceIds.clear()
        seen.clear()
        worker.shutdownNow()
        sessions.shutdownNow()
    }

    companion object { private const val TAG = "LocalDrop" }
}
