package com.music.echo.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class P2PPeerDiscovery(
    private val context: Context,
    private val port: Int = P2PPartnerConfig.DEFAULT_P2P_PORT
) {
    companion object {
        private const val TAG = "P2PPeerDiscovery"
        private const val SERVICE_TYPE = "_echomusic._tcp."
        private const val SERVICE_NAME = "EchoPlayer"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private var isDiscovering = false
    private var isRegistered = false

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /**
     * Finds local IP addresses, prioritizing Tailscale IPs (100.x.y.z or tun/tailscale interfaces).
     */
    fun getDeviceIpAddresses(): List<Pair<String, Boolean>> {
        val addresses = mutableListOf<Pair<String, Boolean>>() // IP to isTailscale
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val isTailscaleInterface = intf.name.contains("tailscale", ignoreCase = true) ||
                        intf.name.contains("tun", ignoreCase = true)

                val inetAddresses = Collections.list(intf.inetAddresses)
                for (inetAddress in inetAddresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        val host = inetAddress.hostAddress ?: continue
                        val isTailscaleIp = host.startsWith("100.") || isTailscaleInterface
                        addresses.add(host to isTailscaleIp)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error resolving device IP addresses")
        }
        // Return tailscale IPs first, followed by LAN IPs
        return addresses.sortedByDescending { it.second }
    }

    /**
     * Starts registering the local Echo Music P2P service on the network.
     */
    fun startBroadcasting(serviceNamePrefix: String = "Echo") {
        if (isRegistered || nsdManager == null) return

        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$serviceNamePrefix-${Build.MODEL.take(12)}"
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                    isRegistered = true
                    Timber.tag(TAG).i("P2P NSD Service registered: ${NsdServiceInfo.serviceName}")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    isRegistered = false
                    Timber.tag(TAG).w("P2P NSD Registration failed: $errorCode")
                }

                override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                    isRegistered = false
                    Timber.tag(TAG).i("P2P NSD Service unregistered")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.tag(TAG).w("P2P NSD Unregistration failed: $errorCode")
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error starting NSD broadcast")
        }
    }

    /**
     * Stops broadcasting service.
     */
    fun stopBroadcasting() {
        if (!isRegistered || nsdManager == null) return
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
            isRegistered = false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error unregistering NSD service")
        }
    }

    /**
     * Starts discovering other Echo Music P2P instances on the network.
     */
    fun startDiscovery() {
        if (isDiscovering || nsdManager == null) return

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                isDiscovering = true
                Timber.tag(TAG).i("P2P NSD Discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Timber.tag(TAG).d("P2P Service found: ${service.serviceName}")
                if (service.serviceType.contains("echomusic", ignoreCase = true) ||
                    service.serviceType == SERVICE_TYPE
                ) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Timber.tag(TAG).d("P2P Service lost: ${service.serviceName}")
                _discoveredPeers.value = _discoveredPeers.value.filter { it.name != service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscovering = false
                Timber.tag(TAG).i("P2P NSD Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscovering = false
                Timber.tag(TAG).w("P2P NSD Discovery start failed: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.tag(TAG).w("P2P NSD Discovery stop failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error starting NSD discovery")
        }
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        try {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Timber.tag(TAG).w("P2P Resolve failed for ${serviceInfo.serviceName}: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    val peerPort = serviceInfo.port
                    val isTailscale = host.startsWith("100.")
                    val peer = DiscoveredPeer(
                        name = serviceInfo.serviceName,
                        hostAddress = host,
                        port = peerPort,
                        isTailscale = isTailscale
                    )

                    scope.launch {
                        val current = _discoveredPeers.value.filter { it.hostAddress != host }
                        _discoveredPeers.value = current + peer
                    }
                }
            })
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error during service resolve")
        }
    }

    /**
     * Stops peer discovery.
     */
    fun stopDiscovery() {
        if (!isDiscovering || nsdManager == null) return
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            isDiscovering = false
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping NSD discovery")
        }
    }
}
