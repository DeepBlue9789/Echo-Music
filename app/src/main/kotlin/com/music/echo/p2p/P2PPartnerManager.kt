package com.music.echo.p2p

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.*
import echo.music.iad1tya.listentogether.ConnectionState
import echo.music.iad1tya.listentogether.ListenTogetherClient
import echo.music.iad1tya.listentogether.TrackInfo
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class P2PPartnerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: ListenTogetherClient
) {
    companion object {
        private const val TAG = "P2PPartnerManager"
        private const val RECONNECT_INTERVAL_MS = 5000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var p2pServer: P2PWebSocketServer? = null
    val discovery = P2PPeerDiscovery(context)

    private val _status = MutableStateFlow(P2PConnectionStatus.IDLE)
    val status: StateFlow<P2PConnectionStatus> = _status.asStateFlow()

    private val _savedPartnerAddress = MutableStateFlow("")
    val savedPartnerAddress: StateFlow<String> = _savedPartnerAddress.asStateFlow()

    private val _deviceName = MutableStateFlow(Build.MODEL ?: "Echo Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _localPort = MutableStateFlow(P2PPartnerConfig.DEFAULT_P2P_PORT)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private var autoReconnectJob: Job? = null
    private var isIntentionalDisconnect = false

    init {
        scope.launch {
            loadConfig()
            observeConnectionState()
            observeAutoDiscoverable()
        }
    }

    private fun observeAutoDiscoverable() {
        scope.launch {
            context.dataStore.data
                .map { prefs -> (try { prefs[ListenTogetherAutoDiscoverableKey] } catch (e: Exception) { null }) ?: false }
                .distinctUntilChanged()
                .collectLatest { enabled ->
                    Timber.tag(TAG).i("Auto-discoverable background preference changed: $enabled")
                    if (enabled) {
                        discovery.setCurrentDeviceName(_deviceName.value)
                        startLocalServer()
                        discovery.startBroadcasting(_deviceName.value)
                        discovery.startDiscovery()
                        scanForPartners()
                    } else {
                        val currentClientState = client.connectionState.value
                        if (currentClientState == ConnectionState.DISCONNECTED || currentClientState == ConnectionState.ERROR) {
                            stopLocalServer()
                        }
                    }
                }
        }
    }

    private suspend fun loadConfig() {
        val partnerIp = context.dataStore.get(ListenTogetherP2PPartnerIpKey, "")
        val port = context.dataStore.get(ListenTogetherP2PPortKey, P2PPartnerConfig.DEFAULT_P2P_PORT)
        val defaultName = Build.MODEL?.take(16) ?: "Echo Device"
        val savedName = context.dataStore.get(ListenTogetherP2PDeviceNameKey, defaultName)

        _savedPartnerAddress.value = partnerIp
        _localPort.value = port
        _deviceName.value = savedName.ifBlank { defaultName }
        discovery.setCurrentDeviceName(_deviceName.value)
    }

    fun saveDeviceName(name: String) {
        val trimmed = name.trim().ifBlank { Build.MODEL ?: "Echo Device" }
        _deviceName.value = trimmed
        discovery.setCurrentDeviceName(trimmed)
        p2pServer?.serverDeviceName = trimmed
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[ListenTogetherP2PDeviceNameKey] = trimmed
            }
            if (_isServerRunning.value) {
                discovery.stopBroadcasting()
                discovery.startBroadcasting(trimmed)
            }
        }
    }

    fun savePartnerAddress(address: String) {
        val trimmed = address.trim()
        _savedPartnerAddress.value = trimmed
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[ListenTogetherP2PPartnerIpKey] = trimmed
            }
        }
    }

    fun savePort(port: Int) {
        val validPort = if (port in 1024..65535) port else P2PPartnerConfig.DEFAULT_P2P_PORT
        _localPort.value = validPort
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[ListenTogetherP2PPortKey] = validPort
            }
        }
    }

    /**
     * Triggers active scanning for Tailscale and LAN partners.
     */
    fun scanForPartners() {
        scope.launch {
            val candidates = mutableListOf<String>()
            val saved = _savedPartnerAddress.value.trim()
            if (saved.isNotBlank()) {
                candidates.add(saved)
            }

            // If we have a Tailscale IP (e.g. 100.99.1.23), add subnet scan targets (100.99.1.x)
            val ipList = discovery.getDeviceIpAddresses()
            val tailscaleIp = ipList.firstOrNull { it.second }?.first
            if (tailscaleIp != null && tailscaleIp.startsWith("100.")) {
                val prefix = tailscaleIp.substringBeforeLast(".")
                val lastOctet = tailscaleIp.substringAfterLast(".").toIntOrNull() ?: 1
                // Add nearby host IPs in the same subnet
                for (offset in 1..25) {
                    val candidate1 = "$prefix.$offset"
                    val candidate2 = "$prefix.${(lastOctet + offset) % 254}"
                    candidates.add(candidate1)
                    candidates.add(candidate2)
                }
            }

            discovery.scanTailscalePartners(candidates, _deviceName.value)
        }
    }

    /**
     * Starts the embedded local P2P WebSocket server.
     */
    @Synchronized
    fun startLocalServer(port: Int = _localPort.value): Boolean {
        if (p2pServer != null && _isServerRunning.value) {
            Timber.tag(TAG).i("P2P Server is already running, resetting room state to fresh instance")
            p2pServer?.resetRoomState()
            return true
        }

        return try {
            _status.value = P2PConnectionStatus.STARTING_SERVER
            val server = P2PWebSocketServer(port).apply {
                serverDeviceName = _deviceName.value
                onPeerJoinedListener = { peerName ->
                    Timber.tag(TAG).i("Peer joined server: $peerName, connecting local client if not connected")
                    val currentClientState = client.connectionState.value
                    if (currentClientState != ConnectionState.CONNECTED && currentClientState != ConnectionState.CONNECTING) {
                        isIntentionalDisconnect = false
                        scope.launch {
                            val localUrl = "ws://127.0.0.1:$port"
                            client.connectDirect(localUrl, _deviceName.value)
                        }
                    }
                }
            }
            server.start()
            p2pServer = server
            _isServerRunning.value = true
            _status.value = P2PConnectionStatus.SERVER_RUNNING
            discovery.startBroadcasting(_deviceName.value)
            Timber.tag(TAG).i("P2P Server successfully started on port $port")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start P2P Server on port $port")
            _status.value = P2PConnectionStatus.ERROR
            _isServerRunning.value = false
            false
        }
    }

    /**
     * Stops the embedded local P2P WebSocket server.
     */
    @Synchronized
    fun stopLocalServer() {
        try {
            discovery.stopBroadcasting()
            p2pServer?.stop(1000)
            p2pServer = null
            _isServerRunning.value = false
            Timber.tag(TAG).i("P2P Server stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error stopping P2P Server")
        }
    }

    /**
     * Connects directly to a partner's IP/hostname in P2P mode.
     * Guaranteed to never connect to local self device.
     */
    fun connectToPartner(partnerAddress: String = _savedPartnerAddress.value, username: String = _deviceName.value) {
        val cleanAddr = partnerAddress.trim()
        if (cleanAddr.isBlank()) {
            Timber.tag(TAG).w("Cannot connect: Partner address is empty")
            _status.value = P2PConnectionStatus.IDLE
            return
        }

        val cleanHost = cleanAddr.removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://").substringBefore(":")
        val isLocalhost = cleanHost.startsWith("127.") || cleanHost.equals("localhost", ignoreCase = true)
        val myIps = discovery.getDeviceIpAddresses().map { it.first }.toSet()

        if (isLocalhost || cleanHost in myIps) {
            Timber.tag(TAG).w("Prevented connection to self IP: $cleanHost. Use 'Host P2P Session' instead.")
            _status.value = P2PConnectionStatus.IDLE
            return
        }

        isIntentionalDisconnect = false
        savePartnerAddress(cleanAddr)

        scope.launch {
            val targetUrl = if (cleanAddr.contains(":")) {
                "ws://$cleanAddr"
            } else {
                "ws://$cleanAddr:${_localPort.value}"
            }

            _status.value = P2PConnectionStatus.CONNECTING_TO_PEER
            Timber.tag(TAG).i("Connecting to P2P partner target: $targetUrl as $username")

            // Connect client directly to partner target URL
            client.connectDirect(targetUrl, username)

            // Start auto-reconnect guardian for target URL
            startAutoReconnectGuardian(targetUrl, username)
        }
    }

    /**
     * Host a standalone local P2P session, reset server room state cleanly, seed initial state, and connect locally.
     */
    fun hostLocalSession(
        username: String = _deviceName.value,
        initialTrack: TrackInfo? = null,
        isPlaying: Boolean = false,
        positionMs: Long = 0L,
        queue: List<TrackInfo>? = null
    ) {
        isIntentionalDisconnect = false
        scope.launch {
            startLocalServer()
            if (initialTrack != null) {
                p2pServer?.seedInitialState(initialTrack, isPlaying, positionMs, queue)
            } else {
                p2pServer?.resetRoomState()
            }
            val localUrl = "ws://127.0.0.1:${_localPort.value}"
            _status.value = P2PConnectionStatus.CONNECTING_TO_PEER
            client.connectDirect(localUrl, username)
        }
    }

    fun seedInitialServerState(track: TrackInfo?, isPlaying: Boolean, positionMs: Long, queue: List<TrackInfo>?) {
        p2pServer?.seedInitialState(track, isPlaying, positionMs, queue)
    }

    /**
     * Disconnects from P2P session.
     * If autoDiscoverable is enabled, resets server into clean standby rather than killing it.
     */
    fun disconnect() {
        isIntentionalDisconnect = true
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        client.disconnect()

        scope.launch {
            val autoDiscoverable = context.dataStore.get(ListenTogetherAutoDiscoverableKey, false)
            if (autoDiscoverable) {
                p2pServer?.resetRoomState()
                _status.value = P2PConnectionStatus.SERVER_RUNNING
                discovery.startBroadcasting(_deviceName.value)
                Timber.tag(TAG).i("Preserving standby P2P server due to auto-discoverable setting")
            } else {
                stopLocalServer()
                _status.value = P2PConnectionStatus.IDLE
            }
        }
    }

    private fun startAutoReconnectGuardian(targetUrl: String, username: String) {
        autoReconnectJob?.cancel()
        var attempts = 0
        autoReconnectJob = scope.launch {
            while (!isIntentionalDisconnect && attempts < 3) {
                delay(RECONNECT_INTERVAL_MS)
                val currentState = client.connectionState.value
                val connectedPeersOnServer = p2pServer?.connectedPeerCount?.value ?: 0
                
                if (!isIntentionalDisconnect && 
                    (currentState == ConnectionState.ERROR || currentState == ConnectionState.DISCONNECTED) &&
                    connectedPeersOnServer == 0
                ) {
                    attempts++
                    Timber.tag(TAG).i("P2P auto-reconnecting to partner: $targetUrl (attempt $attempts/3)")
                    _status.value = P2PConnectionStatus.RECONNECTING
                    client.connectDirect(targetUrl, username)
                } else if (currentState == ConnectionState.CONNECTED) {
                    attempts = 0
                }
            }
        }
    }

    private fun observeConnectionState() {
        scope.launch {
            client.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> {
                        _status.value = P2PConnectionStatus.CONNECTED_TO_PEER
                    }
                    ConnectionState.CONNECTING -> {
                        _status.value = P2PConnectionStatus.CONNECTING_TO_PEER
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (isIntentionalDisconnect) {
                            _status.value = P2PConnectionStatus.IDLE
                        }
                    }
                    ConnectionState.ERROR -> {
                        _status.value = P2PConnectionStatus.ERROR
                    }
                    ConnectionState.RECONNECTING -> {
                        _status.value = P2PConnectionStatus.RECONNECTING
                    }
                }
            }
        }
    }
}
