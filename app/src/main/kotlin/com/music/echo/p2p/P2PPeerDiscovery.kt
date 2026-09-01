package com.music.echo.p2p

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections

class P2PPeerDiscovery(
    private val context: Context,
    private val port: Int = P2PPartnerConfig.DEFAULT_P2P_PORT
) {
    companion object {
        private const val TAG = "P2PPeerDiscovery"
        private const val SERVICE_TYPE = "_echomusic._tcp."
        private const val PROBE_TIMEOUT_MS = 800
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    }

    private val _discoveredPeers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val discoveredPeers: StateFlow<List<DiscoveredPeer>> = _discoveredPeers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

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
        return addresses.sortedByDescending { it.second }
    }

    /**
     * Starts registering the local Echo Music P2P service on the network via mDNS.
     */
    fun startBroadcasting(deviceName: String = "Echo Device") {
        currentDeviceName = deviceName
        if (isRegistered || nsdManager == null) return

        try {
            val sanitizedName = deviceName.replace(Regex("[^a-zA-Z0-9 _-]"), "").take(20).ifBlank { "Echo-${Build.MODEL.take(8)}" }
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = sanitizedName
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    isRegistered = true
                    Timber.tag(TAG).i("P2P NSD Service registered: ${serviceInfo.serviceName}")
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
     * Starts discovering other Echo Music P2P instances via mDNS (local WiFi).
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

    private var currentDeviceName: String = "Echo Device"

    fun setCurrentDeviceName(name: String) {
        currentDeviceName = name
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

                    // Prevent discovering self
                    val myIps = getDeviceIpAddresses().map { it.first }.toSet()
                    if (host in myIps || host == "127.0.0.1" || host.startsWith("127.") || host.equals("localhost", ignoreCase = true)) {
                        Timber.tag(TAG).d("Ignoring self-discovered peer address: $host")
                        return
                    }
                    if (serviceInfo.serviceName.equals(currentDeviceName, ignoreCase = true)) {
                        Timber.tag(TAG).d("Ignoring self-discovered peer name: ${serviceInfo.serviceName}")
                        return
                    }

                    addDiscoveredPeer(
                        DiscoveredPeer(
                            name = serviceInfo.serviceName,
                            hostAddress = host,
                            port = peerPort,
                            isTailscale = isTailscale
                        )
                    )
                }
            })
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error during service resolve")
        }
    }

    /**
     * Actively scans candidate Tailscale IPs and saved partner IP by directly probing port 9876.
     * This bypasses mDNS limitations over Tailscale VPN networks.
     */
    fun scanTailscalePartners(candidateIps: List<String>, myDeviceName: String) {
        scope.launch {
            _isScanning.value = true
            Timber.tag(TAG).d("Starting active Tailscale probe for ${candidateIps.size} targets")

            val myIps = getDeviceIpAddresses().map { it.first }.toSet()
            val filteredCandidates = candidateIps.filter { it.isNotBlank() && it !in myIps }.distinct()

            filteredCandidates.map { ip ->
                async { probeAndAddPeer(ip, myDeviceName) }
            }.awaitAll()

            _isScanning.value = false
        }
    }

    /**
     * Probes an individual IP address on port 9876 to discover if an Echo Partner server is active.
     */
    suspend fun probeAndAddPeer(ip: String, myDeviceName: String): DiscoveredPeer? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
                socket.soTimeout = PROBE_TIMEOUT_MS

                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                // Send lightweight HTTP GET probe to read server info header/json
                val probeRequest = "GET /info HTTP/1.1\r\nHost: $ip:$port\r\nUser-Agent: Echo-P2P-Probe\r\nX-Echo-Sender-Name: $myDeviceName\r\n\r\n"
                writer.write(probeRequest)
                writer.flush()

                var peerName = "Echo Device ($ip)"
                var line: String? = reader.readLine()
                var contentLength = 0

                while (line != null && line.isNotBlank()) {
                    if (line.startsWith("X-Echo-Device-Name:", ignoreCase = true)) {
                        peerName = line.substringAfter(":").trim()
                    } else if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    line = reader.readLine()
                }

                if (contentLength > 0) {
                    val bodyChars = CharArray(contentLength)
                    reader.read(bodyChars, 0, contentLength)
                    val body = String(bodyChars)
                    try {
                        val json = JSONObject(body)
                        if (json.has("name")) {
                            peerName = json.getString("name")
                        }
                    } catch (_: Exception) {}
                }

                val isTailscale = ip.startsWith("100.")
                val discovered = DiscoveredPeer(
                    name = peerName,
                    hostAddress = ip,
                    port = port,
                    isTailscale = isTailscale
                )
                addDiscoveredPeer(discovered)
                Timber.tag(TAG).i("Active Tailscale probe found Echo peer: $peerName at $ip:$port")
                return@withContext discovered
            }
        } catch (e: Exception) {
            // Connection failed or timed out (expected if device is offline)
            Timber.tag(TAG).v("Probe failed for $ip: ${e.message}")
            null
        }
    }

    private fun addDiscoveredPeer(peer: DiscoveredPeer) {
        val myIps = getDeviceIpAddresses().map { it.first }.toSet()
        if (peer.hostAddress in myIps || peer.hostAddress == "127.0.0.1" || peer.hostAddress.startsWith("127.") || peer.hostAddress.equals("localhost", ignoreCase = true)) {
            return
        }
        if (peer.name.equals(currentDeviceName, ignoreCase = true)) {
            return
        }
        scope.launch {
            val current = _discoveredPeers.value.filter { it.hostAddress != peer.hostAddress }
            _discoveredPeers.value = current + peer
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
