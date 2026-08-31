package com.music.echo.p2p

import echo.music.iad1tya.listentogether.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    // Authoritative Virtual Timeline State: Position(t) = p_ref + r * (t - t_ref)
    @Volatile var virtualTimelineRefTime: Long = System.currentTimeMillis() // t_ref (ms)
    @Volatile var virtualTimelineRefPos: Double = 0.0                      // p_ref (seconds)
    @Volatile var virtualTimelineRate: Double = 0.0                        // r (0.0 or 1.0)
    @Volatile var currentSeqId: Long = 0L                                  // Monotonic seq_id

    fun getTimelinePositionSeconds(now: Long = System.currentTimeMillis()): Double {
        val elapsedSec = (now - virtualTimelineRefTime).coerceAtLeast(0L) / 1000.0
        return (virtualTimelineRefPos + virtualTimelineRate * elapsedSec).coerceAtLeast(0.0)
    }

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

    var serverDeviceName: String = "Echo Partner"
    var onPeerJoinedListener: ((username: String) -> Unit)? = null
    private var lastBroadcastSongChangeTrackId: String? = null

    init {
        isReuseAddr = true
    }

    override fun onWebsocketHandshakeReceivedAsServer(
        conn: WebSocket,
        draft: org.java_websocket.drafts.Draft,
        request: ClientHandshake
    ): org.java_websocket.handshake.ServerHandshakeBuilder {
        val builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request)
        builder.put("X-Echo-Device-Name", serverDeviceName)
        return builder
    }

    override fun onStart() {
        Timber.tag(TAG).i("P2P WebSocket Server ($serverDeviceName) started on port $port")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Timber.tag(TAG).i("P2P Peer connected: ${conn.remoteSocketAddress}")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val session = peerSessions.remove(conn)
        Timber.tag(TAG).i("P2P Peer disconnected: ${session?.username ?: conn.remoteSocketAddress} ($reason)")
        
        session?.let { s ->
            bufferedUserIds.remove(s.userId)
            val hostUser = UserInfo(
                userId = "host-local",
                username = serverDeviceName,
                isHost = true,
                isConnected = true
            )
            val peerUsers = peerSessions.values.map {
                UserInfo(userId = it.userId, username = it.username, isHost = it.isHost, isConnected = true)
            }
            val updatedUsers = (listOf(hostUser) + peerUsers).distinctBy { it.username }
            _roomState.value = _roomState.value.copy(users = updatedUsers)
            _connectedPeerCount.value = peerSessions.size

            // Broadcast user left
            val userLeftPayload = UserLeftPayload(s.userId, s.username)
            broadcastMessage(MessageTypes.USER_LEFT, userLeftPayload)

            // Broadcast pause on disconnect
            val pauseAction = PlaybackActionPayload(
                action = PlaybackActions.PAUSE,
                position = _roomState.value.position
            )
            applyPlaybackAction(pauseAction, null)
            broadcastMessage(MessageTypes.SYNC_PLAYBACK, pauseAction)
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

    private var currentVersion = 1L
    private var serverBarrierJob: Job? = null

    override fun onError(conn: WebSocket?, ex: Exception) {
        Timber.tag(TAG).e(ex, "P2P Server error on connection: ${conn?.remoteSocketAddress}")
    }

    private fun handleIncomingMessage(conn: WebSocket, type: String, payload: JsonElement?) {
        when (type) {
            MessageTypes.PING -> {
                sendToPeer(conn, MessageTypes.PONG, PongPayload(serverTime = System.currentTimeMillis()))
            }

            "probe" -> {
                val response = buildJsonObject {
                    put("type", "probe_response")
                    put("payload", buildJsonObject {
                        put("name", serverDeviceName)
                        put("port", port)
                    })
                }
                conn.send(response.toString())
            }

            MessageTypes.CREATE_ROOM, MessageTypes.JOIN_ROOM -> {
                val username = when (type) {
                    MessageTypes.CREATE_ROOM -> payload?.let { json.decodeFromJsonElement<CreateRoomPayload>(it).username } ?: "Partner"
                    else -> payload?.let { json.decodeFromJsonElement<JoinRoomPayload>(it).username } ?: "Partner"
                }

                val existingSession = peerSessions.remove(conn)
                val userId = existingSession?.userId ?: UUID.randomUUID().toString().take(8)
                val isLoopback = conn.remoteSocketAddress?.address?.isLoopbackAddress == true
                val isConnHost = isLoopback || (username == serverDeviceName)
                val session = PeerSession(userId = userId, username = username, isHost = isConnHost)
                peerSessions[conn] = session

                val hostUser = UserInfo(
                    userId = if (isConnHost) userId else "host-local",
                    username = serverDeviceName,
                    isHost = true,
                    isConnected = true
                )

                val peerUsers = peerSessions.values.map {
                    UserInfo(userId = it.userId, username = it.username, isHost = it.isHost, isConnected = true)
                }

                val updatedUsers = (listOf(hostUser) + peerUsers).distinctBy { it.username }
                _roomState.value = _roomState.value.copy(
                    hostId = if (isConnHost) userId else _roomState.value.hostId.ifEmpty { "host-local" },
                    users = updatedUsers,
                    allowParticipantControl = true
                )
                _connectedPeerCount.value = peerSessions.size

                Timber.tag(TAG).i("Peer registered in P2P room: $username ($userId). Active room users: ${updatedUsers.size}")

                // Send approved join payload with full room state containing all users
                val approvedPayload = JoinApprovedPayload(
                    roomCode = ROOM_CODE_P2P,
                    userId = userId,
                    sessionToken = UUID.randomUUID().toString(),
                    state = _roomState.value
                )
                sendToPeer(conn, MessageTypes.JOIN_APPROVED, approvedPayload)

                // Send authoritative Virtual Timeline Session Snapshot to newly joined peer
                val snapshot = SessionSnapshotPayload(
                    seqId = currentSeqId,
                    isPlaying = virtualTimelineRate > 0.0,
                    refTimestamp = virtualTimelineRefTime,
                    refPosition = virtualTimelineRefPos,
                    playbackRate = virtualTimelineRate,
                    trackId = _roomState.value.currentTrack?.id,
                    trackInfo = _roomState.value.currentTrack,
                    queue = _roomState.value.queue,
                    serverTime = System.currentTimeMillis()
                )
                sendToPeer(conn, MessageTypes.SESSION_SNAPSHOT, snapshot)

                // Broadcast user joined to other peers
                val userJoinedPayload = UserJoinedPayload(userId = userId, username = username)
                broadcastMessage(MessageTypes.USER_JOINED, userJoinedPayload, excludePeer = conn)

                if (username != serverDeviceName) {
                    onPeerJoinedListener?.invoke(username)
                }
            }

            MessageTypes.CLOCK_SYNC_REQ -> {
                val req = payload?.let { json.decodeFromJsonElement<ClockSyncRequestPayload>(it) } ?: return
                val t2 = System.currentTimeMillis()
                val res = ClockSyncResponsePayload(
                    clientT1 = req.clientT1,
                    serverT2 = t2,
                    serverT3 = System.currentTimeMillis()
                )
                sendToPeer(conn, MessageTypes.CLOCK_SYNC_RES, res)
            }

            MessageTypes.BUFFER_LOCK -> {
                val lockPayload = payload?.let { json.decodeFromJsonElement<BufferLockPayload>(it) } ?: return
                val now = System.currentTimeMillis()
                val nextSeq = ++currentSeqId
                virtualTimelineRefTime = now
                virtualTimelineRefPos = lockPayload.position
                virtualTimelineRate = 0.0
                bufferedUserIds.clear()
                
                val pauseCmd = PauseCommandPayload(
                    seqId = nextSeq,
                    pausePosition = lockPayload.position,
                    serverTimestamp = now
                )
                broadcastMessage(MessageTypes.PAUSE_COMMAND, pauseCmd)
            }

            MessageTypes.BUFFER_READY_EVENT -> {
                // If room is already playing, never interrupt playback with redundant scheduled play
                if (virtualTimelineRate > 0.0) {
                    return
                }
                val readyPayload = payload?.let { json.decodeFromJsonElement<BufferReadyEventPayload>(it) } ?: return
                val session = peerSessions[conn] ?: return
                bufferedUserIds.add(session.userId)
                val totalPeers = peerSessions.size
                if (bufferedUserIds.size >= totalPeers && totalPeers > 0) {
                    val targetTime = System.currentTimeMillis() + 250L
                    val nextSeq = ++currentSeqId
                    val startPosSec = virtualTimelineRefPos
                    virtualTimelineRefTime = targetTime
                    virtualTimelineRate = 1.0
                    val playCmd = PlayScheduledPayload(
                        seqId = nextSeq,
                        executeAt = targetTime,
                        startPosition = startPosSec
                    )
                    broadcastMessage(MessageTypes.PLAY_SCHEDULED, playCmd)
                    bufferedUserIds.clear()
                }
            }

            MessageTypes.PLAYBACK_ACTION -> {
                val actionPayload = payload?.let { json.decodeFromJsonElement<PlaybackActionPayload>(it) } ?: return
                val session = peerSessions[conn]
                val nextVersion = ++currentVersion
                val enrichedAction = actionPayload.copy(
                    serverTime = System.currentTimeMillis(),
                    stateVersion = nextVersion
                )
                applyPlaybackAction(enrichedAction, session?.userId)
                // Broadcast playback sync with stateVersion & serverTime to all other peers
                broadcastMessage(MessageTypes.SYNC_PLAYBACK, enrichedAction, excludePeer = conn)
            }

            MessageTypes.BUFFER_READY -> {
                val bufferPayload = payload?.let { json.decodeFromJsonElement<BufferReadyPayload>(it) } ?: return
                val session = peerSessions[conn] ?: return
                handleBufferReady(session.userId, bufferPayload.trackId)
            }

            MessageTypes.BUFFER_WAIT -> {
                val waitPayload = payload?.let { json.decodeFromJsonElement<BufferWaitPayload>(it) } ?: return
                val session = peerSessions[conn]
                val senderId = session?.userId ?: "peer"
                currentBufferingTrackId = waitPayload.trackId
                bufferedUserIds.remove(senderId)
                broadcastMessage(MessageTypes.BUFFER_WAIT, waitPayload, excludePeer = conn)
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
                    queue = _roomState.value.queue,
                    stateVersion = _roomState.value.stateVersion
                )
                sendToPeer(conn, MessageTypes.SYNC_STATE, syncStatePayload)

                val snapshot = SessionSnapshotPayload(
                    seqId = currentSeqId,
                    isPlaying = virtualTimelineRate > 0.0,
                    refTimestamp = virtualTimelineRefTime,
                    refPosition = virtualTimelineRefPos,
                    playbackRate = virtualTimelineRate,
                    trackId = _roomState.value.currentTrack?.id,
                    trackInfo = _roomState.value.currentTrack,
                    queue = _roomState.value.queue,
                    serverTime = System.currentTimeMillis()
                )
                sendToPeer(conn, MessageTypes.SESSION_SNAPSHOT, snapshot)
            }

            MessageTypes.UPDATE_ROOM_SETTINGS -> {
                val updateSettings = payload?.let { json.decodeFromJsonElement<UpdateRoomSettingsPayload>(it) } ?: return
                _roomState.value = _roomState.value.copy(allowParticipantControl = updateSettings.allowParticipantControl)
                broadcastMessage(MessageTypes.ROOM_SETTINGS_CHANGED, updateSettings)
            }
        }
    }

    private fun applyPlaybackAction(action: PlaybackActionPayload, senderUserId: String? = null) {
        val currentState = _roomState.value
        val effectiveVersion = if (action.stateVersion > 0L) action.stateVersion else ++currentVersion
        when (action.action) {
            PlaybackActions.PLAY -> {
                serverBarrierJob?.cancel()
                bufferedUserIds.clear()
                currentBufferingTrackId = null

                val targetTime = System.currentTimeMillis() + 250L
                val startPosSec = (action.position ?: _roomState.value.position) / 1000.0
                val nextSeq = ++currentSeqId
                virtualTimelineRefTime = targetTime
                virtualTimelineRefPos = startPosSec
                virtualTimelineRate = 1.0

                _roomState.value = currentState.copy(
                    isPlaying = true,
                    position = (startPosSec * 1000).toLong(),
                    lastUpdate = System.currentTimeMillis(),
                    stateVersion = effectiveVersion
                )

                val playCmd = PlayScheduledPayload(
                    seqId = nextSeq,
                    executeAt = targetTime,
                    startPosition = startPosSec
                )
                broadcastMessage(MessageTypes.PLAY_SCHEDULED, playCmd)
            }
            PlaybackActions.PAUSE -> {
                serverBarrierJob?.cancel()
                bufferedUserIds.clear()
                currentBufferingTrackId = null

                val now = System.currentTimeMillis()
                val currentPosSec = if (action.position != null) action.position / 1000.0 else getTimelinePositionSeconds(now)
                val nextSeq = ++currentSeqId
                virtualTimelineRefTime = now
                virtualTimelineRefPos = currentPosSec
                virtualTimelineRate = 0.0

                _roomState.value = currentState.copy(
                    isPlaying = false,
                    position = (currentPosSec * 1000).toLong(),
                    lastUpdate = now,
                    stateVersion = effectiveVersion
                )

                val pauseCmd = PauseCommandPayload(
                    seqId = nextSeq,
                    pausePosition = currentPosSec,
                    serverTimestamp = now
                )
                broadcastMessage(MessageTypes.PAUSE_COMMAND, pauseCmd)
            }
            PlaybackActions.SEEK -> {
                val targetPosSec = (action.position ?: currentState.position) / 1000.0
                val now = System.currentTimeMillis()
                val nextSeq = ++currentSeqId
                val isCurrentlyPlaying = virtualTimelineRate > 0.0
                if (isCurrentlyPlaying) {
                    val targetTime = now + 250L
                    virtualTimelineRefTime = targetTime
                    virtualTimelineRefPos = targetPosSec
                    virtualTimelineRate = 1.0
                    val seekCmd = SeekCommandPayload(
                        seqId = nextSeq,
                        targetPosition = targetPosSec,
                        autoPlay = true,
                        executeAt = targetTime
                    )
                    broadcastMessage(MessageTypes.SEEK_COMMAND, seekCmd)
                } else {
                    virtualTimelineRefTime = now
                    virtualTimelineRefPos = targetPosSec
                    virtualTimelineRate = 0.0
                    val seekCmd = SeekCommandPayload(
                        seqId = nextSeq,
                        targetPosition = targetPosSec,
                        autoPlay = false,
                        executeAt = null
                    )
                    broadcastMessage(MessageTypes.SEEK_COMMAND, seekCmd)
                }

                _roomState.value = currentState.copy(
                    position = (targetPosSec * 1000).toLong(),
                    lastUpdate = now,
                    stateVersion = effectiveVersion
                )
            }
            PlaybackActions.CHANGE_TRACK -> {
                val trackId = action.trackInfo?.id
                currentBufferingTrackId = trackId
                bufferedUserIds.clear()
                senderUserId?.let { bufferedUserIds.add(it) }

                // CRITICAL: Reset virtual timeline to 0.0 for the new song!
                virtualTimelineRefPos = 0.0
                virtualTimelineRefTime = System.currentTimeMillis()
                virtualTimelineRate = 0.0

                _roomState.value = currentState.copy(
                    currentTrack = action.trackInfo,
                    isPlaying = false,
                    position = 0L,
                    lastUpdate = System.currentTimeMillis(),
                    stateVersion = effectiveVersion
                )

                // Broadcast system chat notification for the track change so peers can quote & react (deduplicated)
                action.trackInfo?.let { track ->
                    if (lastBroadcastSongChangeTrackId != track.id) {
                        lastBroadcastSongChangeTrackId = track.id
                        val songMsg = ChatMessagePayload(
                            userId = "SYSTEM",
                            username = "🎵 ${track.title}",
                            message = "${track.title} - ${track.artist}",
                            timestamp = System.currentTimeMillis(),
                            trackInfo = track
                        )
                        broadcastMessage(MessageTypes.CHAT, songMsg)
                    }
                }

                // If sender is ready and room has <= 1 connected peer or all buffered, release buffer barrier
                val totalPeers = peerSessions.size
                if (trackId != null && (bufferedUserIds.size >= totalPeers || totalPeers <= 1)) {
                    Timber.tag(TAG).i("Track changed and buffer barrier immediately satisfied for $trackId")
                    releaseBufferBarrier(trackId)
                } else if (trackId != null) {
                    // Start safety barrier timer: auto-release after 3.5s to prevent stuck barrier deadlocks
                    serverBarrierJob?.cancel()
                    serverBarrierJob = scope.launch {
                        delay(3500)
                        if (currentBufferingTrackId == trackId && bufferedUserIds.isNotEmpty()) {
                            Timber.tag(TAG).w("Server barrier timeout reached for $trackId, releasing room")
                            releaseBufferBarrier(trackId)
                        }
                    }
                }
            }
            PlaybackActions.QUEUE_ADD -> {
                val track = action.trackInfo
                if (track != null) {
                    val updatedQueue = if (action.insertNext == true) {
                        listOf(track) + currentState.queue
                    } else {
                        currentState.queue + track
                    }
                    _roomState.value = currentState.copy(queue = updatedQueue, stateVersion = effectiveVersion)
                }
            }
            PlaybackActions.QUEUE_REMOVE -> {
                val trackId = action.trackId
                if (trackId != null) {
                    _roomState.value = currentState.copy(
                        queue = currentState.queue.filter { it.id != trackId },
                        stateVersion = effectiveVersion
                    )
                }
            }
            PlaybackActions.QUEUE_CLEAR -> {
                _roomState.value = currentState.copy(queue = emptyList(), stateVersion = effectiveVersion)
            }
            PlaybackActions.SYNC_QUEUE -> {
                if (action.queue != null) {
                    _roomState.value = currentState.copy(queue = action.queue, stateVersion = effectiveVersion)
                }
            }
            PlaybackActions.SET_VOLUME -> {
                val vol = action.volume
                if (vol != null) {
                    _roomState.value = currentState.copy(volume = vol.coerceIn(0f, 1f), stateVersion = effectiveVersion)
                }
            }
        }
    }

    private fun releaseBufferBarrier(trackId: String) {
        serverBarrierJob?.cancel()
        val targetTime = System.currentTimeMillis() + 250L
        val nextSeq = ++currentSeqId
        val resumePosSec = virtualTimelineRefPos.coerceAtLeast(0.0)
        virtualTimelineRefTime = targetTime
        virtualTimelineRefPos = resumePosSec
        virtualTimelineRate = 1.0

        val nextVersion = ++currentVersion
        _roomState.value = _roomState.value.copy(
            isPlaying = true,
            position = (resumePosSec * 1000.0).toLong(),
            lastUpdate = targetTime,
            stateVersion = nextVersion
        )

        val playCmd = PlayScheduledPayload(
            seqId = nextSeq,
            executeAt = targetTime,
            startPosition = resumePosSec
        )
        broadcastMessage(MessageTypes.PLAY_SCHEDULED, playCmd)
        broadcastMessage(MessageTypes.BUFFER_COMPLETE, BufferCompletePayload(trackId))
        bufferedUserIds.clear()
        currentBufferingTrackId = null
    }

    private fun handleBufferReady(userId: String, trackId: String) {
        currentBufferingTrackId = trackId
        bufferedUserIds.add(userId)

        val totalPeers = peerSessions.size
        if (bufferedUserIds.size >= totalPeers || totalPeers <= 1) {
            Timber.tag(TAG).i("Cooperative buffer barrier reached: all peers ready for track $trackId")
            releaseBufferBarrier(trackId)
        } else {
            val waitingList = peerSessions.values.map { it.userId }.filterNot { bufferedUserIds.contains(it) }
            broadcastMessage(MessageTypes.BUFFER_WAIT, BufferWaitPayload(trackId, waitingList))
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
