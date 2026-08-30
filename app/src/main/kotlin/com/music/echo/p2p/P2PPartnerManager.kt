package com.music.echo.p2p

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.*
import echo.music.iad1tya.listentogether.ConnectionState
import echo.music.iad1tya.listentogether.ListenTogetherClient
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
        private const val RECONNECT_INTERVAL_MS = 4000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var p2pServer: P2PWebSocketServer? = null
    val discovery = P2PPeerDiscovery(context)

    private val _status = MutableStateFlow(P2PConnectionStatus.IDLE)
    val status: StateFlow<P2PConnectionStatus> = _status.asStateFlow()

    private val _savedPartnerAddress = MutableStateFlow("")
    val savedPartnerAddress: StateFlow<String> = _savedPartnerAddress.asStateFlow()

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
        }
    }

    private suspend fun loadConfig() {
        val partnerIp = context.dataStore.get(ListenTogetherP2PPartnerIpKey, "")
        val port = context.dataStore.get(ListenTogetherP2PPortKey, P2PPartnerConfig.DEFAULT_P2P_PORT)
        _savedPartnerAddress.value = partnerIp
        _localPort.value = port
    }

    fun savePartnerAddress(address: String) {
        _savedPartnerAddress.value = address.trim()
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[ListenTogetherP2PPartnerIpKey] = address.trim()
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
     * Starts the embedded local P2P WebSocket server.
     */
    @Synchronized
    fun startLocalServer(port: Int = _localPort.value): Boolean {
        if (p2pServer != null && _isServerRunning.value) {
            Timber.tag(TAG).i("P2P Server is already running")
            return true
        }

        return try {
            _status.value = P2PConnectionStatus.STARTING_SERVER
            val server = P2PWebSocketServer(port)
            server.start()
            p2pServer = server
            _isServerRunning.value = true
            _status.value = P2PConnectionStatus.SERVER_RUNNING
            discovery.startBroadcasting()
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
     * Connects directly to a partner's IP/hostname or local server in P2P mode.
     */
    fun connectToPartner(partnerAddress: String = _savedPartnerAddress.value, username: String = "Partner") {
        isIntentionalDisconnect = false
        savePartnerAddress(partnerAddress)

        scope.launch {
            // 1. Ensure local server is active for symmetrical hosting
            startLocalServer()

            // 2. Format WebSocket target URL
            val targetHost = partnerAddress.trim().ifEmpty { "127.0.0.1" }
            val cleanHost = targetHost.removePrefix("ws://").removePrefix("wss://").removePrefix("http://").removePrefix("https://")
            val targetUrl = if (cleanHost.contains(":")) {
                "ws://$cleanHost"
            } else {
                "ws://$cleanHost:${_localPort.value}"
            }

            _status.value = P2PConnectionStatus.CONNECTING_TO_PEER
            Timber.tag(TAG).i("Connecting to P2P partner target: $targetUrl")

            // 3. Connect client using ListenTogetherClient directly to target URL
            client.connectDirect(targetUrl, username)

            // 4. Start auto-reconnect guardian
            startAutoReconnectGuardian(targetUrl, username)
        }
    }

    /**
     * Host a standalone local P2P session and connect locally.
     */
    fun hostLocalSession(username: String = "Host") {
        isIntentionalDisconnect = false
        scope.launch {
            startLocalServer()
            val localUrl = "ws://127.0.0.1:${_localPort.value}"
            _status.value = P2PConnectionStatus.CONNECTING_TO_PEER
            client.connectDirect(localUrl, username)
        }
    }

    /**
     * Disconnects from P2P session.
     */
    fun disconnect() {
        isIntentionalDisconnect = true
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        client.disconnect()
        stopLocalServer()
        _status.value = P2PConnectionStatus.IDLE
    }

    private fun startAutoReconnectGuardian(targetUrl: String, username: String) {
        autoReconnectJob?.cancel()
        autoReconnectJob = scope.launch {
            while (!isIntentionalDisconnect) {
                delay(RECONNECT_INTERVAL_MS)
                val currentState = client.connectionState.value
                if (!isIntentionalDisconnect && 
                    (currentState == ConnectionState.DISCONNECTED || currentState == ConnectionState.ERROR)
                ) {
                    Timber.tag(TAG).i("P2P auto-reconnecting to partner: $targetUrl")
                    _status.value = P2PConnectionStatus.RECONNECTING
                    client.connectDirect(targetUrl, username)
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
