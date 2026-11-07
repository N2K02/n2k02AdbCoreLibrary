package dev.n2k02.adb.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.net.InetAddress
import java.net.NetworkInterface

enum class AdbService { PAIRING, CONNECTION }
data class AdbDiscoveredPort(val service: AdbService, val port: Int)

interface AdbDiscovery {
    val events: Flow<AdbDiscoveredPort>
    fun start()
    fun stop()
}

/** Android Wireless debugging mDNS; never accepts another device's address. */
class LocalAdbDiscovery(context: Context) : AdbDiscovery {
    private val manager = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = MutableSharedFlow<AdbDiscoveredPort>(extraBufferCapacity = 16)
    override val events: Flow<AdbDiscoveredPort> = found
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var active = false
    private var generation = 0L

    @Synchronized
    override fun start() {
        if (active) return
        active = true
        generation++
        val thisGeneration = generation
        // Some Android Wi-Fi stacks drop mDNS responses without a multicast
        // lock. Only hold it while scanning, never for the lifetime of the app.
        multicastLock = runCatching {
            wifi?.createMulticastLock("n2k02-adb-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        discover("_adb-tls-pairing._tcp.", AdbService.PAIRING, thisGeneration)
        discover("_adb-tls-connect._tcp.", AdbService.CONNECTION, thisGeneration)
    }

    private fun discover(type: String, service: AdbService, generationId: Long) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w("N2K02AdbCore", "mDNS discovery failed: $serviceType ($errorCode)")
                stopOne(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // Android NSD may suffix the service type with ".local.".
                if (!serviceInfo.serviceType.startsWith(type.removeSuffix(".")) ||
                    !isCurrent(generationId)) return
                runCatching {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            if (!isCurrent(generationId) || !isThisDevice(info.host)) return
                            if (AdbVerification.validPort(info.port)) {
                                found.tryEmit(AdbDiscoveredPort(service, info.port))
                            }
                        }
                    })
                }
            }
        }
        synchronized(this) { if (active && generation == generationId) listeners += listener else return }
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { stopOne(listener) }
    }

    private fun isThisDevice(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching false
            interfaces.toList().any { network ->
                network.isUp && network.inetAddresses.toList().any { it == host }
            }
        }.getOrDefault(false)
    }

    @Synchronized
    private fun isCurrent(generationId: Long): Boolean = active && generation == generationId

    @Synchronized
    private fun stopOne(listener: NsdManager.DiscoveryListener) {
        listeners.remove(listener)
        runCatching { manager.stopServiceDiscovery(listener) }
    }

    @Synchronized
    override fun stop() {
        active = false
        generation++ // Ignore late NSD callbacks after page exit or Quit.
        val snapshot = listeners.toList()
        listeners.clear()
        snapshot.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
