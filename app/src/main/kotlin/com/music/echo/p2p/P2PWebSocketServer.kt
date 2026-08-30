package com.music.echo.p2p

import echo.music.iad1tya.listentogether.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import timber.log.Timber
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class PeerSession(
    val userId: String,
    var username: String,
    val isHost: Boolean,
    val connectedAt: Long = System.currentTimeMillis()
)

class P2PWebSocketServer(
    port: Int = P2PPartnerConfig.DEFAULT_P2P_PORT
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "P2PWebSocketServer"
        const val ROOM_CODE_P2P = "P2P-MESH"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val peerSessions = ConcurrentHashMap<WebSocket, PeerSession>()
    private val bufferedUserIds = CopyOnWriteArraySet<String>()
    private var currentBufferingTrackId: String? = null

    private val _roomState = MutableStateFlow(
        RoomState(
            roomCode = ROOM_CODE_P2P,
            hostId = "server-local",
            users = emptyList(),
            currentTrack = null,
            isPlaying = false,
            position = 0L,
            lastUpdate = System.currentTimeMillis(),
            volume = 1f,
            queue = emptyList(),
            allowParticipantControl = true // Equal partner control
        )
    )
    val roomState: StateFlow<RoomState> = _roomState.asStateFlow()

    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    init {
        isReuseAddr = true
    }

    override fun onStart() {
        Timber.tag(TAG).i("P2P WebSocket Server started on port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Timber.tag(TAG).i("P2P Peer connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val session = peerSessions.remove(conn)
        Timber.tag(TAG).i("P2P Peer disconnected: ${session?.username ?: conn.remoteSocketAddress} ($reason)")
        
        session?.let { s ->
            bufferedUserIds.remove(s.userId)
            val updatedUsers = _roomState.value.users.filter { it.userId != s.userId }
            _roomState.value = _roomState.value.copy(users = updatedUsers)
            _connectedPeerCount.value = peerSessions.size

            // Broadcast user left
            val userLeftPayload = UserLeftPayload(s.userId, s.username)
            broadcastMessage(MessageTypes.USER_LEFT, userLeftPayload)
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val root = json.parseToJsonElement(message).jsonObject
            val type = root["type"]?.jsonPrimitive?.content ?: return
            val payloadElement = root["payload"]

            handleIncomingMessage(conn, type, payloadElement)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error processing incoming P2P message: $message")
        }
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        // String fallback for binary
        val text = Charsets.UTF_8.decode(message).toString()
        onMessage(conn, text)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Timber.tag(TAG).e(ex, "P2P Server error on connection: ${conn?.remoteSocketAddress}")
    }

    private fun handleIncomingMessage(conn: WebSocket, type: String, payload: JsonElement?) {
        when (type) {
            MessageTypes.PING -> {
                sendToPeerNoPayload(conn, MessageTypes.PONG)
            }

            MessageTypes.CREATE_ROOM, MessageTypes.JOIN_ROOM -> {
                val username = when (type) {
                    MessageTypes.CREATE_ROOM -> payload?.let { json.decodeFromJsonElement<CreateRoomPayload>(it).username } ?: "Partner"
                    else -> payload?.let { json.decodeFromJsonElement<JoinRoomPayload>(it).username } ?: "Partner"
                }

                val userId = UUID.randomUUID().toString().take(8)
                val session = PeerSession(userId = userId, username = username, isHost = peerSessions.isEmpty())
                peerSessions[conn] = session

                val userInfo = UserInfo(userId = userId, username = username, isHost = session.isHost)
                val updatedUsers = (_roomState.value.users.filter { it.userId != userId } + userInfo)
                _roomState.value = _roomState.value.copy(users = updatedUsers)
                _connectedPeerCount.value = peerSessions.size

                // Send approved join payload with full room state
                val approvedPayload = JoinApprovedPayload(
                    roomCode = ROOM_CODE_P2P,
                    userId = userId,
                    sessionToken = UUID.randomUUID().toString(),
                    state = _roomState.value
                )
                sendToPeer(conn, MessageTypes.JOIN_APPROVED, approvedPayload)

                // Broadcast user joined to other peers
                val userJoinedPayload = UserJoinedPayload(userId = userId, username = username)
                broadcastMessage(MessageTypes.USER_JOINED, userJoinedPayload, excludePeer = conn)
            }

            MessageTypes.PLAYBACK_ACTION -> {
                val actionPayload = payload?.let { json.decodeFromJsonElement<PlaybackActionPayload>(it) } ?: return
                applyPlaybackAction(actionPayload)
                // Broadcast playback sync to all other peers
                broadcastMessage(MessageTypes.SYNC_PLAYBACK, actionPayload, excludePeer = conn)
            }

            MessageTypes.BUFFER_READY -> {
                val bufferPayload = payload?.let { json.decodeFromJsonElement<BufferReadyPayload>(it) } ?: return
                val session = peerSessions[conn] ?: return
                handleBufferReady(session.userId, bufferPayload.trackId)
            }

            MessageTypes.CHAT -> {
                val chatPayload = payload?.let { json.decodeFromJsonElement<ChatPayload>(it) } ?: return
                val session = peerSessions[conn]
                val senderUsername = session?.username ?: "Partner"
                
                val broadcastChat = ChatMessagePayload(
                    userId = session?.userId ?: "unknown",
                    username = senderUsername,
                    message = chatPayload.message,
                    timestamp = System.currentTimeMillis(),
                    replyTo = chatPayload.replyTo
                )
                // Broadcast to all peers including sender so UI has uniform timestamp
                broadcastMessage(MessageTypes.CHAT, broadcastChat)
            }

            MessageTypes.SUGGEST_TRACK -> {
                val suggestPayload = payload?.let { json.decodeFromJsonElement<SuggestTrackPayload>(it) } ?: return
                val session = peerSessions[conn]
                val suggestionId = UUID.randomUUID().toString().take(8)
                val receivedPayload = SuggestionReceivedPayload(
                    suggestionId = suggestionId,
                    fromUserId = session?.userId ?: "peer",
                    fromUsername = session?.username ?: "Partner",
                    trackInfo = suggestPayload.trackInfo
                )
                broadcastMessage(MessageTypes.SUGGESTION_RECEIVED, receivedPayload)
            }

            MessageTypes.APPROVE_SUGGESTION -> {
                val approvePayload = payload?.let { json.decodeFromJsonElement<ApproveSuggestionPayload>(it) } ?: return
                broadcastMessage(MessageTypes.SUGGESTION_APPROVED, approvePayload)
            }

            MessageTypes.REJECT_SUGGESTION -> {
                val rejectPayload = payload?.let { json.decodeFromJsonElement<RejectSuggestionPayload>(it) } ?: return
                broadcastMessage(MessageTypes.SUGGESTION_REJECTED, rejectPayload)
            }

            MessageTypes.REQUEST_SYNC -> {
                val syncStatePayload = SyncStatePayload(
                    currentTrack = _roomState.value.currentTrack,
                    isPlaying = _roomState.value.isPlaying,
                    position = _roomState.value.position,
                    lastUpdate = System.currentTimeMillis(),
                    queue = _roomState.value.queue
                )
                sendToPeer(conn, MessageTypes.SYNC_STATE, syncStatePayload)
            }

            MessageTypes.UPDATE_ROOM_SETTINGS -> {
                val updateSettings = payload?.let { json.decodeFromJsonElement<UpdateRoomSettingsPayload>(it) } ?: return
                _roomState.value = _roomState.value.copy(allowParticipantControl = updateSettings.allowParticipantControl)
                broadcastMessage(MessageTypes.ROOM_SETTINGS_CHANGED, updateSettings)
            }
        }
    }

    private fun applyPlaybackAction(action: PlaybackActionPayload) {
        val currentState = _roomState.value
        when (action.action) {
            PlaybackActions.PLAY -> {
                _roomState.value = currentState.copy(
                    isPlaying = true,
                    position = action.position ?: currentState.position,
                    lastUpdate = System.currentTimeMillis()
                )
            }
            PlaybackActions.PAUSE -> {
                _roomState.value = currentState.copy(
                    isPlaying = false,
                    position = action.position ?: currentState.position,
                    lastUpdate = System.currentTimeMillis()
                )
            }
            PlaybackActions.SEEK -> {
                _roomState.value = currentState.copy(
                    position = action.position ?: currentState.position,
                    lastUpdate = System.currentTimeMillis()
                )
            }
            PlaybackActions.CHANGE_TRACK -> {
                currentBufferingTrackId = action.trackInfo?.id
                bufferedUserIds.clear()
                _roomState.value = currentState.copy(
                    currentTrack = action.trackInfo,
                    isPlaying = false,
                    position = 0L,
                    lastUpdate = System.currentTimeMillis()
                )
            }
            PlaybackActions.QUEUE_ADD -> {
                val track = action.trackInfo
                if (track != null) {
                    val updatedQueue = if (action.insertNext == true) {
                        listOf(track) + currentState.queue
                    } else {
                        currentState.queue + track
                    }
                    _roomState.value = currentState.copy(queue = updatedQueue)
                }
            }
            PlaybackActions.QUEUE_REMOVE -> {
                val trackId = action.trackId
                if (trackId != null) {
                    _roomState.value = currentState.copy(
                        queue = currentState.queue.filter { it.id != trackId }
                    )
                }
            }
            PlaybackActions.QUEUE_CLEAR -> {
                _roomState.value = currentState.copy(queue = emptyList())
            }
            PlaybackActions.SYNC_QUEUE -> {
                if (action.queue != null) {
                    _roomState.value = currentState.copy(queue = action.queue)
                }
            }
            PlaybackActions.SET_VOLUME -> {
                val vol = action.volume
                if (vol != null) {
                    _roomState.value = currentState.copy(volume = vol.coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun handleBufferReady(userId: String, trackId: String) {
        if (currentBufferingTrackId == null || currentBufferingTrackId == trackId) {
            currentBufferingTrackId = trackId
            bufferedUserIds.add(userId)

            val totalPeers = peerSessions.size
            if (bufferedUserIds.size >= totalPeers && totalPeers > 0) {
                // All peers buffered! Start playback
                Timber.tag(TAG).i("Cooperative buffer barrier reached: all $totalPeers peers ready for track $trackId")
                broadcastMessage(MessageTypes.BUFFER_COMPLETE, BufferCompletePayload(trackId))
                bufferedUserIds.clear()
            } else {
                val waitingList = peerSessions.values.map { it.userId }.filterNot { bufferedUserIds.contains(it) }
                broadcastMessage(MessageTypes.BUFFER_WAIT, BufferWaitPayload(trackId, waitingList))
            }
        }
    }

    private fun sendToPeerNoPayload(conn: WebSocket, type: String) {
        try {
            val messageObj = buildJsonObject {
                put("type", type)
            }
            conn.send(messageObj.toString())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send message $type to peer")
        }
    }

    private inline fun <reified T> sendToPeer(conn: WebSocket, type: String, payload: T?) {
        try {
            val payloadElement = payload?.let { json.encodeToJsonElement(it) }
            val messageObj = buildJsonObject {
                put("type", type)
                if (payloadElement != null) {
                    put("payload", payloadElement)
                }
            }
            conn.send(messageObj.toString())
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to send message $type to peer")
        }
    }

    private inline fun <reified T> broadcastMessage(type: String, payload: T?, excludePeer: WebSocket? = null) {
        try {
            val payloadElement = payload?.let { json.encodeToJsonElement(it) }
            val messageObj = buildJsonObject {
                put("type", type)
                if (payloadElement != null) {
                    put("payload", payloadElement)
                }
            }
            val text = messageObj.toString()
            for (peer in peerSessions.keys) {
                if (peer != excludePeer && peer.isOpen) {
                    peer.send(text)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to broadcast message $type")
        }
    }
}
