package com.music.echo.p2p

import kotlinx.serialization.Serializable

/**
 * P2P Connection status for the local device.
 */
enum class P2PConnectionStatus {
    IDLE,
    STARTING_SERVER,
    SERVER_RUNNING,
    CONNECTING_TO_PEER,
    CONNECTED_TO_PEER,
    RECONNECTING,
    ERROR
}

/**
 * Information about a discovered or connected peer.
 */
data class DiscoveredPeer(
    val name: String,
    val hostAddress: String,
    val port: Int,
    val isTailscale: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis()
)

/**
 * P2P Partner configuration.
 */
data class P2PPartnerConfig(
    val partnerAddress: String = "",
    val port: Int = DEFAULT_P2P_PORT,
    val autoStartServer: Boolean = true,
    val enableMdnsDiscovery: Boolean = true
) {
    companion object {
        const val DEFAULT_P2P_PORT = 9876
    }
}
