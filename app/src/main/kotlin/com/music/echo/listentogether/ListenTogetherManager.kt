

package echo.music.iad1tya.listentogether

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import echo.music.iad1tya.constants.ListenTogetherSmartResyncKey
import echo.music.iad1tya.constants.ListenTogetherSyncVolumeKey
import echo.music.iad1tya.extensions.currentMetadata
import echo.music.iad1tya.extensions.metadata
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.models.MediaMetadata
import echo.music.iad1tya.models.MediaMetadata.Album
import echo.music.iad1tya.models.MediaMetadata.Artist
import echo.music.iad1tya.models.toMediaMetadata
import echo.music.iad1tya.playback.PlayerConnection
import echo.music.iad1tya.playback.queues.YouTubeQueue
import echo.music.iad1tya.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton


import com.music.echo.p2p.P2PPartnerManager

@Singleton
class ListenTogetherManager @Inject constructor(
    private val client: ListenTogetherClient,
    val p2pPartnerManager: P2PPartnerManager,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ListenTogetherManager"
        
        
        private const val SYNC_DEBOUNCE_THRESHOLD_MS = 1000L
        
        
        private const val POSITION_TOLERANCE_MS = 2000L
        
        
        private const val PLAYBACK_POSITION_TOLERANCE_MS = 3000L
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        initialize()
        observePreferences()
    }
    
    private var playerConnection: PlayerConnection? = null
    val playerConnectionFlow = MutableStateFlow<PlayerConnection?>(null)
    val currentConnection: PlayerConnection? get() = playerConnection
    private var eventCollectorJob: Job? = null
    private var queueObserverJob: Job? = null
    private var volumeObserverJob: Job? = null
    private var playerListenerRegistered = false

    private val syncHostVolumeEnabled = MutableStateFlow(true)
    private val smartResyncEnabled = MutableStateFlow(true)
    private val pauseOnDisconnectEnabled = MutableStateFlow(true)
    private var lastSyncedVolume: Float? = null
    private var previousMuteState: Boolean? = null
    private var muteForcedByPreference = false

    private var lastRole: RoomRole = RoomRole.NONE
    
    
    @Volatile
    private var isSyncing = false
    
    
    private var lastSyncedIsPlaying: Boolean? = null
    private var lastSyncedTrackId: String? = null
    
    
    private var lastSyncActionTime: Long = 0L
    
    
    private var bufferingTrackId: String? = null
    private var isWaitingForPeersBuffer: Boolean = false
    private var bufferWaitTimeoutJob: Job? = null
    
    
    private var activeSyncJob: Job? = null
    
    
    
    private var currentTrackGeneration: Int = 0

    
    private var pendingSyncState: SyncStatePayload? = null

    
    private var bufferCompleteReceivedForTrack: String? = null

    private fun normalizeTrackId(id: String?): String {
        return id?.substringAfterLast("/")?.trim() ?: ""
    }

    // -----------------------------------------------------------------
    // Virtual Timeline Model & Synchronization State
    // Position(t) = p_ref + r * (t - t_ref)
    // -----------------------------------------------------------------
    @Volatile private var timelineRefTime: Long = 0L       // t_ref in network timestamp (ms)
    @Volatile private var timelineRefPosSec: Double = 0.0  // p_ref in track seconds
    @Volatile var timelineRate: Double = 0.0               // r (0.0 when paused/buffering, 1.0 when playing)
    @Volatile private var lastAppliedSeqId: Long = 0L      // Sequence number filter
    @Volatile private var isApplyingRemoteState: Boolean = false // Local-Echo Suppression lock
    private var scheduledPlayJob: Job? = null
    private var syncControllerJob: Job? = null
    private var currentPlaybackSpeed = 1.0f

    fun getTimelinePositionMs(): Long {
        if (timelineRefTime <= 0L || timelineRate <= 0.0) {
            return (timelineRefPosSec * 1000.0).toLong()
        }
        val nowNetMs = client.toNetworkTime(SystemClock.elapsedRealtime())
        val elapsedSeconds = (nowNetMs - timelineRefTime).coerceAtLeast(0L) / 1000.0
        val posSeconds = (timelineRefPosSec + timelineRate * elapsedSeconds).coerceAtLeast(0.0)
        return (posSeconds * 1000.0).toLong()
    }

    
    val connectionState = client.connectionState
    val roomState = client.roomState
    val role = client.role
    val userId = client.userId
    val pendingJoinRequests = client.pendingJoinRequests
    val bufferingUsers = client.bufferingUsers
    val logs = client.logs
    val events = client.events
    val blockedUsernames = client.blockedUsernames
    val pendingSuggestions = client.pendingSuggestions
    val rtt = client.rtt

    val isInRoom: Boolean get() = client.isInRoom
    val isHost: Boolean get() = client.isHost
    val hasPersistedSession: Boolean get() = client.hasPersistedSession

    val allowParticipantControl: Boolean
        get() = roomState.value?.allowParticipantControl == true

    val canControlMusic: Boolean
        get() = !isInRoom || isHost || allowParticipantControl

    val isGuestPlaybackRestricted: Boolean
        get() = isInRoom && !canControlMusic

    val guestPlaybackRestricted = combine(client.roomState, client.role) { state, role ->
        role == RoomRole.GUEST && state?.allowParticipantControl != true
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), false)
    
    
    private val _chatMessages = MutableStateFlow<List<ChatMessagePayload>>(emptyList())
    val chatMessages = _chatMessages
    
    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            try {
                if (!canControlMusic || !isInRoom || isSyncing || isApplyingRemoteState) return
                val connection = playerConnection ?: return
                val player = connection.player

                Timber.tag(TAG).d("Play state changed: $playWhenReady (reason: $reason)")
                
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                    Timber.tag(TAG).d("Ignoring playWhenReady changed at end of media item")
                    return
                }

                // If user pressed play/pause while waiting on barrier, break barrier
                isWaitingForPeersBuffer = false
                bufferingTrackId = null
                bufferWaitTimeoutJob?.cancel()
                
                val currentTrackId = player.currentMediaItem?.mediaId
                if (currentTrackId != null && currentTrackId != lastSyncedTrackId) {
                    Timber.tag(TAG).d("[SYNC] Sending track change before play state: track = $currentTrackId")
                    player.currentMetadata?.let { metadata ->
                        sendTrackChangeInternal(metadata)
                        lastSyncedTrackId = currentTrackId
                        lastSyncedIsPlaying = false
                    }
                }
                
                sendPlayState(playWhenReady, player)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPlayWhenReadyChanged")
            }
        }
        
        private fun sendPlayState(playWhenReady: Boolean, player: Player) {
            try {
                val position = player.currentPosition
                
                if (playWhenReady) {
                    Timber.tag(TAG).d("Sending PLAY at position $position")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                    }
                    lastSyncedIsPlaying = true
                } else {
                    Timber.tag(TAG).d("Sending PAUSE at position $position")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.PAUSE, position = position)
                    }
                    lastSyncedIsPlaying = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in sendPlayState")
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            try {
                if (!canControlMusic || !isInRoom || isSyncing) return
                if (mediaItem == null) return
                
                val connection = playerConnection ?: return
                val player = connection.player
                
                val trackId = mediaItem.mediaId
                val currentMeta = player.currentMetadata
                val normTrackId = normalizeTrackId(trackId)
                val normMetaId = normalizeTrackId(currentMeta?.id)
                if (currentMeta != null && (normMetaId == normTrackId || normMetaId.isEmpty())) {
                    if (normTrackId != normalizeTrackId(lastSyncedTrackId)) {
                        lastSyncedTrackId = normTrackId
                        Timber.tag(TAG).d("Host sending track change on transition (reason=$reason): ${currentMeta.title}")
                        sendTrackChangeInternal(currentMeta)
                        
                        val isAutoTransition = (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                        val otherUsersCount = (roomState.value?.users?.size ?: 1) - 1
                        
                        if (isAutoTransition) {
                            // On natural auto-transition, ExoPlayer has already pre-buffered the next track gaplessly.
                            // NEVER pause the host! Keep playing seamlessly!
                            Timber.tag(TAG).d("Seamless auto-transition: continuing gapless playback without pausing host")
                            isWaitingForPeersBuffer = false
                            bufferingTrackId = null
                            lastSyncedIsPlaying = true
                            timelineRefPosSec = 0.0
                            timelineRate = 1.0
                            timelineRefTime = System.currentTimeMillis()
                            resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 4000L
                            client.sendBufferReady(trackId)
                        } else if (otherUsersCount > 0) {
                            Timber.tag(TAG).d("Manual track jump: pausing host until all peers buffer track $trackId")
                            isWaitingForPeersBuffer = true
                            bufferingTrackId = trackId
                            isSyncing = true
                            player.pause()
                            lastSyncedIsPlaying = false
                            scope.launch {
                                delay(400)
                                isSyncing = false
                            }
                            bufferWaitTimeoutJob?.cancel()
                            bufferWaitTimeoutJob = scope.launch {
                                delay(4000)
                                if (isWaitingForPeersBuffer && bufferingTrackId == trackId) {
                                    Timber.tag(TAG).w("Host buffer barrier wait timed out after 4s, resuming playback")
                                    isWaitingForPeersBuffer = false
                                    bufferingTrackId = null
                                    if (canControlMusic) {
                                        playerConnection?.play()
                                    }
                                }
                            }
                            if (player.playbackState == Player.STATE_READY) {
                                client.sendBufferReady(trackId)
                            }
                        } else {
                            if (player.playWhenReady) {
                                lastSyncedIsPlaying = true
                                val position = player.currentPosition
                                sendPlaybackActionWithSync {
                                    client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                                }
                            } else {
                                lastSyncedIsPlaying = false
                                val position = player.currentPosition
                                sendPlaybackActionWithSync {
                                    client.sendPlaybackAction(PlaybackActions.PAUSE, position = position)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onMediaItemTransition")
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            try {
                if (!isInRoom) return
                val connection = playerConnection ?: return
                val rawMediaId = connection.player.currentMediaItem?.mediaId ?: return
                val currentTrackId = normalizeTrackId(rawMediaId)
                if (currentTrackId.isEmpty()) return

                if (playbackState == Player.STATE_READY) {
                    applyPendingSyncIfReady()
                    val normComplete = normalizeTrackId(bufferCompleteReceivedForTrack)
                    val normBuffering = normalizeTrackId(bufferingTrackId)
                    if (normComplete.isNotEmpty() && normComplete == currentTrackId) {
                        Timber.tag(TAG).d("Buffer complete already received for track $currentTrackId, resuming playback on STATE_READY")
                        bufferCompleteReceivedForTrack = null
                        isWaitingForPeersBuffer = false
                        bufferingTrackId = null
                        isSyncing = true
                        connection.play()
                        lastSyncedIsPlaying = true
                        scope.launch {
                            delay(250)
                            isSyncing = false
                        }
                    } else if (normBuffering == currentTrackId || isWaitingForPeersBuffer) {
                        Timber.tag(TAG).d("Local playback STATE_READY for track $currentTrackId")
                        bufferingTrackId = null
                        isWaitingForPeersBuffer = false
                        client.sendBufferReady(currentTrackId)
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    // Mid-track buffering is handled gracefully by ExoPlayer and the Virtual Timeline
                    // drift slew engine. Do not stall or lock the room for all users during playback.
                    return
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPlaybackStateChanged")
            }
        }
        
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            try {
                if (isSyncing || isApplyingRemoteState || !canControlMusic || !isInRoom) return
                
                // Only send in-track SEEK if we stayed within the same media item (ignore track transitions)
                if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    if (bufferingTrackId != null || isWaitingForPeersBuffer) return
                    Timber.tag(TAG).d("Sending in-track SEEK to ${newPosition.positionMs}")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.SEEK, position = newPosition.positionMs)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onPositionDiscontinuity")
            }
        }
    }

    
    fun setPlayerConnection(connection: PlayerConnection?) {
        Timber.tag(TAG).d("setPlayerConnection: ${connection != null}, isInRoom: $isInRoom")
        
        try {
            
            val oldConnection = playerConnection
            if (playerListenerRegistered && oldConnection != null) {
                try {
                    oldConnection.player.removeListener(playerListener)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error removing old player listener")
                }
                playerListenerRegistered = false
            }
            oldConnection?.shouldBlockPlaybackChanges = null
            oldConnection?.onSkipPrevious = null
            oldConnection?.onSkipNext = null
            oldConnection?.onRestartSong = null
            
            playerConnection = connection
            playerConnectionFlow.value = connection
            refreshSyncCapabilities()
            if (isInRoom && isHost) {
                startQueueSyncObservation()
            }
            if (isInRoom && !isHost) {
                startSyncController()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in setPlayerConnection")
        }
    }

    private fun sendPlaybackActionWithSync(action: () -> Unit) {
        if (!canControlMusic) return
        action()
    }

    private var metadataObservationJob: Job? = null

    private fun refreshSyncCapabilities() {
        val connection = playerConnection ?: return

        connection.shouldBlockPlaybackChanges = { isGuestPlaybackRestricted }

        connection.onSkipPrevious = {
            try {
                if (canControlMusic && !isSyncing) {
                    Timber.tag(TAG).d("Skip Previous triggered")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.SKIP_PREV)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onSkipPrevious")
            }
        }
        connection.onSkipNext = {
            try {
                if (canControlMusic && !isSyncing) {
                    Timber.tag(TAG).d("Skip Next triggered")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.SKIP_NEXT)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onSkipNext")
            }
        }
        connection.onRestartSong = {
            try {
                if (canControlMusic && !isSyncing) {
                    Timber.tag(TAG).d("Restart Song triggered (sending 1ms as 0ms workaround)")
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(PlaybackActions.SEEK, position = 1L)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error in onRestartSong")
            }
        }

        if (isInRoom && canControlMusic) {
            startMetadataObservation()
            if (!playerListenerRegistered) {
                try {
                    connection.player.addListener(playerListener)
                    playerListenerRegistered = true
                    Timber.tag(TAG).d("Added player listener for room sync")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to add player listener")
                    playerListenerRegistered = false
                }
            }
            if (isHost) {
                startQueueSyncObservation()
                startHeartbeat()
                startVolumeSyncObservation()
            } else if (allowParticipantControl) {
                startQueueSyncObservation()
                stopHeartbeat()
                stopVolumeSyncObservation()
            }
        } else {
            stopMetadataObservation()
            if (playerListenerRegistered && !isHost) {
                try {
                    connection.player.removeListener(playerListener)
                    playerListenerRegistered = false
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to remove player listener")
                }
            }
            if (!isHost) {
                stopQueueSyncObservation()
            }
            if (!canControlMusic) {
                stopHeartbeat()
                stopVolumeSyncObservation()
            }
        }
        updateGuestMuteState()
    }

    private fun startMetadataObservation() {
        val connection = playerConnection ?: return
        metadataObservationJob?.cancel()
        metadataObservationJob = scope.launch {
            connection.mediaMetadata.collect { metadata ->
                if (metadata != null && isInRoom && canControlMusic && !isSyncing) {
                    if (metadata.id != lastSyncedTrackId) {
                        Timber.tag(TAG).d("[SYNC] mediaMetadata state changed to: ${metadata.title} (${metadata.id})")
                        lastSyncedTrackId = metadata.id
                        sendTrackChangeInternal(metadata)

                        val otherUsersCount = (roomState.value?.users?.size ?: 1) - 1
                        if (otherUsersCount > 0) {
                            Timber.tag(TAG).d("Cooperative buffer barrier (metadata obs): pausing until peers buffer ${metadata.id}")
                            isWaitingForPeersBuffer = true
                            bufferingTrackId = metadata.id
                            isSyncing = true
                            connection.pause()
                            lastSyncedIsPlaying = false
                            scope.launch {
                                delay(200)
                                isSyncing = false
                            }
                            bufferWaitTimeoutJob?.cancel()
                            bufferWaitTimeoutJob = scope.launch {
                                delay(3500)
                                if (isWaitingForPeersBuffer && bufferingTrackId == metadata.id) {
                                    Timber.tag(TAG).w("Metadata obs buffer barrier wait timed out after 3.5s, resuming playback")
                                    isWaitingForPeersBuffer = false
                                    bufferingTrackId = null
                                    if (canControlMusic) {
                                        playerConnection?.play()
                                        lastSyncedIsPlaying = true
                                    }
                                }
                            }
                            if (connection.player.playbackState == Player.STATE_READY) {
                                client.sendBufferReady(metadata.id)
                            }
                        } else {
                            val player = connection.player
                            if (player.playWhenReady) {
                                lastSyncedIsPlaying = true
                                val position = player.currentPosition
                                sendPlaybackActionWithSync {
                                    client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun stopMetadataObservation() {
        metadataObservationJob?.cancel()
        metadataObservationJob = null
    }

    private fun observePreferences() {
        scope.launch {
            context.dataStore.data
                .map { (try { it[ListenTogetherSyncVolumeKey] } catch(e: Exception) { null }) ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    syncHostVolumeEnabled.value = enabled
                }

            context.dataStore.data
                .map { (try { it[ListenTogetherSmartResyncKey] } catch(e: Exception) { null }) ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    smartResyncEnabled.value = enabled
                }

            context.dataStore.data
                .map { (try { it[echo.music.iad1tya.constants.ListenTogetherPauseOnDisconnectKey] } catch(e: Exception) { null }) ?: true }
                .distinctUntilChanged()
                .collect { enabled ->
                    pauseOnDisconnectEnabled.value = enabled
                }

            // System-wide overlay auto-starts on session connect if bubble is enabled and permission is granted.
            // The old per-toggle check (SystemOverlayBubbleKey) is removed; FloatingChatBubbleKey drives this now.
            context.dataStore.data
                .map { (try { it[echo.music.iad1tya.constants.ListenTogetherFloatingChatBubbleKey] } catch(e: Exception) { null }) ?: true }
                .distinctUntilChanged()
                .collect { bubbleEnabled ->
                    if (!bubbleEnabled && isInRoom) {
                        ListenTogetherOverlayService.stop(context)
                    }
                }
        }
    }

    /** Call after a session is successfully established (RoomCreated or JoinApproved). */
    private fun autoStartOverlayIfEnabled() {
        scope.launch {
            val bubbleEnabled = try {
                context.dataStore.data.map {
                    it[echo.music.iad1tya.constants.ListenTogetherFloatingChatBubbleKey] ?: true
                }.stateIn(scope, SharingStarted.Eagerly, true).value
            } catch (e: Exception) { true }
            if (bubbleEnabled) {
                ListenTogetherOverlayService.start(context)
            }
        }
    }

    
    fun initialize() {
        Timber.tag(TAG).d("Initializing ListenTogetherManager")
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch {
            client.events.collect { event ->
                try {
                    Timber.tag(TAG).d("Received event: $event")
                    handleEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling event: $event")
                }
            }
        }
        
        
        scope.launch {
            role.collect { newRole ->
                try {
                    val previousRole = lastRole
                    lastRole = newRole
                    refreshSyncCapabilities()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in role change handler")
                }
            }
        }

        scope.launch {
            roomState
                .map { it?.allowParticipantControl ?: false }
                .distinctUntilChanged()
                .collect {
                    refreshSyncCapabilities()
                }
        }
    }

    private fun handleEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.Connected -> {
                Timber.tag(TAG).d("Connected to server with userId: ${event.userId}")
            }
            
            is ListenTogetherEvent.RoomCreated -> {
                Timber.tag(TAG).d("Room created: ${event.roomCode}")
                autoStartOverlayIfEnabled()
                try {
                    
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as host")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener on room create")
                        }
                    }
                    
                    lastSyncedIsPlaying = player?.playWhenReady
                    lastSyncedTrackId = player?.currentMediaItem?.mediaId

                    
                    player?.currentMetadata?.let { metadata ->
                        Timber.tag(TAG).d("Room created with existing track: ${metadata.title}")
                        
                        sendTrackChangeInternal(metadata)
                        
                        val isPlaying = player.playWhenReady
                        if (isPlaying) {
                            lastSyncedIsPlaying = true
                            val position = player.currentPosition
                            Timber.tag(TAG).d("Host already playing on room create, sending PLAY at $position")
                            sendPlaybackActionWithSync {
                            client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                        }
                        }
                    }
                    startQueueSyncObservation()
                    startHeartbeat()
                    startVolumeSyncObservation()
                    startSyncController()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling RoomCreated event")
                }
            }
            
            is ListenTogetherEvent.JoinApproved -> {
                Timber.tag(TAG).d("Join approved for room: ${event.roomCode}, isHost: $isHost")
                autoStartOverlayIfEnabled()
                saveMuteStateOnJoin()
                lastAppliedSeqId = -1
                isSyncing = false
                isApplyingRemoteState = false

                val connection = playerConnection
                val player = connection?.player
                if (isHost) {
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Added player listener as host on JoinApproved")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to add player listener as host")
                        }
                    }
                    lastSyncedIsPlaying = player?.playWhenReady
                    lastSyncedTrackId = player?.currentMediaItem?.mediaId

                    player?.currentMetadata?.let { metadata ->
                        Timber.tag(TAG).d("Host seeding track on JoinApproved: ${metadata.title}")
                        sendTrackChangeInternal(metadata)
                        
                        val isPlaying = player.playWhenReady
                        if (isPlaying) {
                            lastSyncedIsPlaying = true
                            val position = player.currentPosition
                            Timber.tag(TAG).d("Host playing on JoinApproved, sending PLAY at $position")
                            sendPlaybackActionWithSync {
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                            }
                        }
                    }
                    sendCurrentQueueSync()
                    startQueueSyncObservation()
                    startHeartbeat()
                    startVolumeSyncObservation()
                } else {
                    startSyncController()
                    applyPlaybackState(
                        currentTrack = event.state.currentTrack,
                        isPlaying = event.state.isPlaying,
                        position = event.state.position,
                        queue = event.state.queue
                    )
                    applyHostVolumeIfNeeded(event.state.volume)
                    updateGuestMuteState()
                }
            }
            
            is ListenTogetherEvent.PlaybackSync -> {
                Timber.tag(TAG).d("PlaybackSync received: ${event.action.action}")
                if (isSyncing) return
                handlePlaybackSync(event.action)
            }
            
            is ListenTogetherEvent.UserJoined -> {
                Timber.tag(TAG).d("[SYNC] User joined: ${event.username}")
                
                if (isHost) {
                    try {
                        val connection = playerConnection
                        val player = connection?.player
                        player?.currentMetadata?.let { metadata ->
                            Timber.tag(TAG).d("[SYNC] Sending current track to newly joined user: ${metadata.title}")
                            sendTrackChangeInternal(metadata)
                            
                            if (player.playWhenReady) {
                                val pos = player.currentPosition
                                Timber.tag(TAG).d("[SYNC] Host playing, sending PLAY at $pos for new joiner")
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                            }
                        }
                        sendCurrentQueueSync()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error handling UserJoined event")
                    }
                }
            }

            is ListenTogetherEvent.BufferWait -> {
                Timber.tag(TAG).d("BufferWait: waiting for ${event.waitingFor.size} users on track ${event.trackId}")
                val connection = playerConnection
                // If room/player is already actively playing and progressing, ignore spurious mid-playback BufferWait
                if (connection != null && connection.player.isPlaying && timelineRate > 0.0) {
                    Timber.tag(TAG).d("Spurious BufferWait ignored: player is already actively playing")
                    return
                }
                isWaitingForPeersBuffer = true
                bufferingTrackId = event.trackId
                if (connection != null && connection.player.isPlaying) {
                    isSyncing = true
                    connection.pause()
                    scope.launch {
                        delay(250)
                        isSyncing = false
                    }
                }
                bufferWaitTimeoutJob?.cancel()
                bufferWaitTimeoutJob = scope.launch {
                    delay(7000)
                    if (isWaitingForPeersBuffer) {
                        Timber.tag(TAG).w("Buffer wait timed out after 7s, resuming playback")
                        isWaitingForPeersBuffer = false
                        if (canControlMusic) {
                            playerConnection?.play()
                        }
                    }
                }
            }
            
            is ListenTogetherEvent.BufferComplete -> {
                Timber.tag(TAG).d("BufferComplete for track: ${event.trackId}")
                bufferWaitTimeoutJob?.cancel()
                isWaitingForPeersBuffer = false
                bufferingTrackId = null
                bufferCompleteReceivedForTrack = event.trackId
                applyPendingSyncIfReady()

                val connection = playerConnection
                if (connection != null) {
                    val player = connection.player
                    if (player.playbackState == Player.STATE_READY) {
                        isSyncing = true
                        connection.play()
                        lastSyncedIsPlaying = true
                        scope.launch {
                            delay(250)
                            isSyncing = false
                        }
                    }
                }
            }
            
            is ListenTogetherEvent.SyncStateReceived -> {
                Timber.tag(TAG).d("SyncStateReceived: playing=${event.state.isPlaying}, pos=${event.state.position}, track=${event.state.currentTrack?.id}")
                if (isGuestPlaybackRestricted) {
                    handleSyncState(event.state)
                }
            }
            
            is ListenTogetherEvent.Kicked -> {
                Timber.tag(TAG).d("Kicked from room: ${event.reason}")
                cleanup()
            }
            
            is ListenTogetherEvent.Disconnected -> {
                Timber.tag(TAG).d("Disconnected from server")
                
                
            }

            is ListenTogetherEvent.Reconnecting -> {
                Timber.tag(TAG).d("Reconnecting: attempt ${event.attempt}/${event.maxAttempts}")
            }
            
            is ListenTogetherEvent.Reconnected -> {
                Timber.tag(TAG).d("Reconnected to room: ${event.roomCode}, isHost: ${event.isHost}")
                try {
                    
                    val connection = playerConnection
                    val player = connection?.player
                    if (player != null && !playerListenerRegistered) {
                        try {
                            player.addListener(playerListener)
                            playerListenerRegistered = true
                            Timber.tag(TAG).d("Re-added player listener after reconnect")
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to re-add player listener after reconnect")
                        }
                    }
                    
                    
                    if (event.isHost) {
                        
                        lastSyncedIsPlaying = player?.playWhenReady
                        lastSyncedTrackId = player?.currentMediaItem?.mediaId
                        
                        val currentMetadata = player?.currentMetadata
                        if (currentMetadata != null) {
                            
                            val serverTrackId = event.state.currentTrack?.id
                            if (serverTrackId != currentMetadata.id) {
                                Timber.tag(TAG).d("Reconnected as host, server track ($serverTrackId) differs from local (${currentMetadata.id}), syncing")
                                sendTrackChangeInternal(currentMetadata)
                            } else {
                                Timber.tag(TAG).d("Reconnected as host, server already has current track $serverTrackId")
                            }
                            
                            
                            scope.launch {
                                delay(500)
                                try {
                                    val currentPlayer = playerConnection?.player
                                    if (currentPlayer?.playWhenReady == true) {
                                        val pos = currentPlayer.currentPosition
                                        Timber.tag(TAG)
                                            .d("Reconnected host is playing, sending PLAY at $pos")
                                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                                    }
                                } catch (e: Exception) {
                                    Timber.tag(TAG).e(e, "Error sending play state after reconnect")
                                }
                            }
                        }
                    } else {
                        
                        Timber.tag(TAG).d("Reconnected as guest, syncing to host's current state")
                        applyPlaybackState(
                            currentTrack = event.state.currentTrack,
                            isPlaying = event.state.isPlaying,
                            position = event.state.position,
                            queue = event.state.queue,
                            bypassBuffer = true  
                        )
                        applyHostVolumeIfNeeded(event.state.volume)
                        
                        
                        
                        scope.launch {
                            delay(1000)
                            if (isGuestPlaybackRestricted && smartResyncEnabled.value) {
                                Timber.tag(TAG).d("Requesting fresh sync after reconnect (Smart Resync)")
                                requestSync()
                            }
                        }
                    }
                    startSyncController()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error handling Reconnected event")
                }
            }
            
            is ListenTogetherEvent.UserReconnected -> {
                Timber.tag(TAG).d("User reconnected: ${event.username}")
                
            }
            
            is ListenTogetherEvent.UserDisconnected -> {
                Timber.tag(TAG).d("User temporarily disconnected: ${event.username}")
                
            }

            is ListenTogetherEvent.HostChanged -> {
                Timber.tag(TAG).d("Host changed: new host is ${event.newHostName} (${event.newHostId})")
                refreshSyncCapabilities()

                val nowIsHost = event.newHostId == userId.value
                if (nowIsHost) {
                    val player = playerConnection?.player
                    val metadata = player?.currentMetadata
                    if (metadata != null) {
                        Timber.tag(TAG).d("New host sending current track: ${metadata.title}")
                        sendTrackChangeInternal(metadata)

                        if (player.playWhenReady) {
                            val position = player.currentPosition
                            Timber.tag(TAG).d("New host is playing, sending PLAY at $position")
                            sendPlaybackActionWithSync {
                                client.sendPlaybackAction(PlaybackActions.PLAY, position = position)
                            }
                        }
                    }
                }
            }
            
            is ListenTogetherEvent.JoinRequestReceived -> {
                Timber.tag(TAG).d("Join request received from ${event.username}")
                
            }

            is ListenTogetherEvent.LocalSuggestionApproved -> {
                try {
                    val mediaMetadata = event.payload.trackInfo.toMediaMetadata()
                    val mediaItem = mediaMetadata.toMediaItem()
                    playerConnection?.playNext(mediaItem)
                    Timber.tag(TAG).d("Approved suggestion added to queue: ${mediaMetadata.title}")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error adding approved suggestion to queue")
                }
            }
            
            is ListenTogetherEvent.ConnectionError -> {
                Timber.tag(TAG).e("Connection error: ${event.error}")
                cleanup()
            }

            is ListenTogetherEvent.ChatMessageReceived -> {
                Timber.tag(TAG).d("Chat message received from ${event.payload.username}")
                val current = _chatMessages.value
                val isDuplicateSongChange = event.payload.userId == "SYSTEM" && 
                    current.lastOrNull()?.let { last ->
                        last.userId == "SYSTEM" && (
                            (last.trackInfo?.id != null && last.trackInfo.id == event.payload.trackInfo?.id) ||
                            last.message == event.payload.message
                        )
                    } == true
                if (!isDuplicateSongChange) {
                    _chatMessages.value = current + event.payload
                }
            }

            is ListenTogetherEvent.UserLeft -> {
                Timber.tag(TAG).d("User left: ${event.username}")
                if (pauseOnDisconnectEnabled.value) {
                    try {
                        playerConnection?.player?.pause()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Error pausing on user left")
                    }
                }
            }

            is ListenTogetherEvent.RoomSettingsChanged -> {
                Timber.tag(TAG).d("Room settings changed: allowParticipantControl=${event.allowParticipantControl}")
                refreshSyncCapabilities()
            }

            is ListenTogetherEvent.PlayScheduled -> {
                handlePlayScheduled(event.payload)
            }

            is ListenTogetherEvent.PauseCommand -> {
                handlePauseCommand(event.payload)
            }

            is ListenTogetherEvent.SeekCommand -> {
                handleSeekCommand(event.payload)
            }

            is ListenTogetherEvent.BufferLock -> {
                handleBufferLock(event.payload)
            }

            is ListenTogetherEvent.SessionSnapshot -> {
                handleSessionSnapshot(event.payload)
            }

            else -> {  }
        }
    }
    
    private fun cleanup() {
        if (pauseOnDisconnectEnabled.value) {
            try {
                playerConnection?.player?.pause()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error pausing player on cleanup")
            }
        }
        if (lastRole == RoomRole.GUEST) {
            restoreGuestMuteState()
        }
        if (playerListenerRegistered) {
            playerConnection?.player?.removeListener(playerListener)
            playerListenerRegistered = false
        }
        stopMetadataObservation()
        stopQueueSyncObservation()
        stopHeartbeat()
        stopVolumeSyncObservation()
        ListenTogetherOverlayService.stop(context)
        
        bufferWaitTimeoutJob?.cancel()
        bufferWaitTimeoutJob = null
        isWaitingForPeersBuffer = false
        lastSyncedIsPlaying = null
        lastSyncedTrackId = null
        bufferingTrackId = null
        isSyncing = false
        bufferCompleteReceivedForTrack = null
        lastRole = RoomRole.NONE
        lastSyncActionTime = 0L  
        ++currentTrackGeneration  
        stopSyncController()
        scheduledPlayJob?.cancel()
        timelineRate = 0.0
        _chatMessages.value = emptyList() 
    }

    // -----------------------------------------------------------------
    // AudioPlayerAdapter & Pitch-Preserved Speed Slew
    // -----------------------------------------------------------------
    private fun applyPlaybackSpeed(speed: Float) {
        if (kotlin.math.abs(currentPlaybackSpeed - speed) < 0.01f) return
        val connection = playerConnection ?: return
        currentPlaybackSpeed = speed
        Timber.tag(TAG).d("SyncController: Soft Slew speed=$speed (pitch preserved)")
        try {
            connection.player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, 1.0f)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error applying playback speed")
        }
    }

    private fun resetPlaybackSpeed() {
        if (currentPlaybackSpeed != 1.0f) {
            applyPlaybackSpeed(1.0f)
        }
    }

    // -----------------------------------------------------------------
    // SyncController: 3-Tier Hysteresis Continuous Evaluation Loop
    // 1. Deadband (|drift| <= 35ms): no action
    // 2. Soft Slew (35ms < |drift| <= 1000ms): 0.95x / 1.05x speed slew
    // 3. Hard Seek (|drift| > 1000ms): direct seekTo
    // -----------------------------------------------------------------
    fun startSyncController() {
        syncControllerJob?.cancel()
        syncControllerJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(250L)
                if (isInRoom) {
                    evaluateDriftAndCorrect()
                }
            }
        }
    }

    fun stopSyncController() {
        syncControllerJob?.cancel()
        syncControllerJob = null
        resetPlaybackSpeed()
        consecutiveHardDriftTicks = 0
    }

    private var consecutiveHardDriftTicks = 0
    private var lastHardSeekTimestamp = 0L
    @Volatile private var resumeGracePeriodUntil = 0L

    private fun evaluateDriftAndCorrect() {
        // Host is master audio source: never seek or slew host playback!
        if (isHost) {
            resetPlaybackSpeed()
            consecutiveHardDriftTicks = 0
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now < resumeGracePeriodUntil) {
            consecutiveHardDriftTicks = 0
            return
        }
        if (isSyncing || isApplyingRemoteState || isWaitingForPeersBuffer || bufferingTrackId != null || timelineRate <= 0.0) {
            resetPlaybackSpeed()
            consecutiveHardDriftTicks = 0
            return
        }
        val connection = playerConnection ?: return
        val player = connection.player
        if (player.playbackState != Player.STATE_READY || !player.playWhenReady) {
            resetPlaybackSpeed()
            consecutiveHardDriftTicks = 0
            return
        }

        val expectedPosMs = getTimelinePositionMs()
        // If expected position is invalid or out of bounds, skip
        if (expectedPosMs <= 0L) {
            resetPlaybackSpeed()
            consecutiveHardDriftTicks = 0
            return
        }

        val actualPosMs = player.currentPosition
        val driftMs = actualPosMs - expectedPosMs // Δ_drift
        val absDrift = kotlin.math.abs(driftMs)

        when {
            // Tier 1: Deadband (|Δ| <= 35ms)
            absDrift <= 35 -> {
                consecutiveHardDriftTicks = 0
                resetPlaybackSpeed()
            }
            // Tier 2A: Gentle Soft Slew (35ms < |Δ| <= 500ms)
            absDrift <= 500 -> {
                consecutiveHardDriftTicks = 0
                val targetSpeed = if (driftMs > 0) 0.96f else 1.04f
                applyPlaybackSpeed(targetSpeed)
            }
            // Tier 2B: Dynamic Soft Slew (500ms < |Δ| <= 3000ms)
            absDrift <= 3000 -> {
                consecutiveHardDriftTicks = 0
                val targetSpeed = if (driftMs > 0) 0.90f else 1.10f
                applyPlaybackSpeed(targetSpeed)
            }
            // Tier 3: Hard Seek (|Δ| > 3000ms)
            else -> {
                resetPlaybackSpeed()
                // Prevent seek oscillation / storm: at least 5s cooldown between hard seeks
                if (now - lastHardSeekTimestamp < 5000L) {
                    consecutiveHardDriftTicks = 0
                    return
                }
                if (absDrift in 3001..60000) {
                    consecutiveHardDriftTicks++
                    if (consecutiveHardDriftTicks >= 6) {
                        consecutiveHardDriftTicks = 0
                        lastHardSeekTimestamp = now
                        resumeGracePeriodUntil = now + 2500L
                        val targetPos = (expectedPosMs + 100L).coerceAtLeast(0L)
                        Timber.tag(TAG).d("SyncController: Sustained Hard Seek triggered (drift=${driftMs}ms > 3000ms), seeking to $targetPos")
                        isSyncing = true
                        player.seekTo(targetPos)
                        scope.launch {
                            delay(500)
                            isSyncing = false
                        }
                    }
                } else {
                    consecutiveHardDriftTicks = 0
                }
            }
        }
    }

    // -----------------------------------------------------------------
    // Two-Phase Scheduled Play & Authoritative Command Handlers
    // -----------------------------------------------------------------
    private fun handlePlayScheduled(payload: PlayScheduledPayload) {
        if (payload.seqId <= lastAppliedSeqId) {
            Timber.tag(TAG).d("Discarding stale PLAY_SCHEDULED (v${payload.seqId} <= v$lastAppliedSeqId)")
            return
        }
        lastAppliedSeqId = payload.seqId
        timelineRefTime = payload.executeAt
        timelineRefPosSec = payload.startPosition
        timelineRate = 1.0

        if (isHost) {
            lastSyncedIsPlaying = true
            return
        }

        val targetLocalMonotonic = client.toLocalMonotonicTime(payload.executeAt)
        val nowMonotonic = SystemClock.elapsedRealtime()
        val rawDelay = targetLocalMonotonic - nowMonotonic
        val delayMs = if (rawDelay in 0L..1000L) rawDelay else 0L
        val startPosMs = (payload.startPosition * 1000.0).toLong()

        // Set resume grace period to allow ExoPlayer audio pipeline to stabilize without drift interference
        resumeGracePeriodUntil = SystemClock.elapsedRealtime() + delayMs + 3000L

        scheduledPlayJob?.cancel()
        scheduledPlayJob = scope.launch(Dispatchers.Main) {
            isApplyingRemoteState = true
            isSyncing = true
            try {
                val connection = playerConnection
                if (connection != null) {
                    val player = connection.player
                    val currentPos = player.currentPosition
                    val posDiff = kotlin.math.abs(currentPos - startPosMs)
                    // Only seek if far off (> 2500ms).
                    // If already paused near start position, seeking causes ExoPlayer decoder flushes and audio stutter!
                    if (posDiff > 2500L && startPosMs >= 0) {
                        Timber.tag(TAG).d("Two-Phase Scheduled Play: seeking from $currentPos to $startPosMs (diff ${posDiff}ms > 2500ms)")
                        player.seekTo(startPosMs)
                    } else {
                        Timber.tag(TAG).d("Two-Phase Scheduled Play: skipping seek, already near target (pos=$currentPos, target=$startPosMs, diff=${posDiff}ms)")
                    }
                    if (delayMs > 0) {
                        Timber.tag(TAG).d("Two-Phase Scheduled Play: waiting ${delayMs}ms to reach execute_at")
                        delay(delayMs)
                    }
                    Timber.tag(TAG).d("Two-Phase Scheduled Play: executing PLAY at $startPosMs")
                    connection.play()
                    lastSyncedIsPlaying = true
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing scheduled play")
            } finally {
                isSyncing = false
                isApplyingRemoteState = false
            }
        }
    }

    private fun handlePauseCommand(payload: PauseCommandPayload) {
        if (payload.seqId <= lastAppliedSeqId) {
            Timber.tag(TAG).d("Discarding stale PAUSE_COMMAND (v${payload.seqId} <= v$lastAppliedSeqId)")
            return
        }
        lastAppliedSeqId = payload.seqId
        scheduledPlayJob?.cancel()
        timelineRefTime = payload.serverTimestamp
        timelineRefPosSec = payload.pausePosition
        timelineRate = 0.0

        if (isHost) {
            lastSyncedIsPlaying = false
            resetPlaybackSpeed()
            return
        }

        val pausePosMs = (payload.pausePosition * 1000.0).toLong()
        isApplyingRemoteState = true
        isSyncing = true
        val connection = playerConnection
        scope.launch(Dispatchers.Main) {
            try {
                if (connection != null) {
                    val player = connection.player
                    connection.pause()
                    val diff = kotlin.math.abs(player.currentPosition - pausePosMs)
                    if (diff > 1500L && pausePosMs >= 0) {
                        Timber.tag(TAG).d("PauseCommand: aligning position from ${player.currentPosition} to $pausePosMs (diff ${diff}ms)")
                        player.seekTo(pausePosMs)
                    }
                    lastSyncedIsPlaying = false
                    resetPlaybackSpeed()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error executing pause command")
            } finally {
                isSyncing = false
                isApplyingRemoteState = false
            }
        }
    }

    private fun handleSeekCommand(payload: SeekCommandPayload) {
        if (payload.seqId <= lastAppliedSeqId) {
            Timber.tag(TAG).d("Discarding stale SEEK_COMMAND (v${payload.seqId} <= v$lastAppliedSeqId)")
            return
        }
        lastAppliedSeqId = payload.seqId
        timelineRefPosSec = payload.targetPosition

        val targetMs = (payload.targetPosition * 1000.0).toLong()
        val player = playerConnection?.player ?: return

        // Set grace period to prevent drift correction while the player seeks and stabilizes
        resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 4000L
        consecutiveHardDriftTicks = 0
        isApplyingRemoteState = true
        isSyncing = true

        // If local player is already at the target position (e.g. the user who dragged the seek bar locally),
        // we do NOT seek again to prevent decoder flushes and audio stutter!
        val currentPos = player.currentPosition
        val alreadyAtTarget = kotlin.math.abs(currentPos - targetMs) < 800L

        if (payload.autoPlay && payload.executeAt != null) {
            timelineRefTime = payload.executeAt
            timelineRate = 1.0
            val targetLocal = client.toLocalMonotonicTime(payload.executeAt)
            val rawDelay = targetLocal - SystemClock.elapsedRealtime()
            val delayMs = if (rawDelay in 0L..1000L) rawDelay else 0L
            scheduledPlayJob?.cancel()
            scheduledPlayJob = scope.launch(Dispatchers.Main) {
                try {
                    val connection = playerConnection ?: return@launch
                    if (!alreadyAtTarget) {
                        Timber.tag(TAG).d("handleSeekCommand: seeking player to $targetMs (was at $currentPos)")
                        connection.player.seekTo(targetMs)
                    } else {
                        Timber.tag(TAG).d("handleSeekCommand: player already at target ($currentPos approx $targetMs), skipping seekTo")
                    }
                    if (delayMs > 0) delay(delayMs)
                    if (!connection.player.isPlaying) {
                        connection.play()
                    }
                    delay(200)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error executing seek command")
                } finally {
                    isSyncing = false
                    isApplyingRemoteState = false
                }
            }
        } else {
            timelineRefTime = System.currentTimeMillis()
            timelineRate = 0.0
            scope.launch(Dispatchers.Main) {
                isApplyingRemoteState = true
                isSyncing = true
                try {
                    val connection = playerConnection ?: return@launch
                    if (!alreadyAtTarget) {
                        Timber.tag(TAG).d("handleSeekCommand: seeking player to $targetMs (was at $currentPos)")
                        connection.player.seekTo(targetMs)
                    } else {
                        Timber.tag(TAG).d("handleSeekCommand: player already at target ($currentPos approx $targetMs), skipping seekTo")
                    }
                    connection.pause()
                    delay(200)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error executing seek command")
                } finally {
                    isSyncing = false
                    isApplyingRemoteState = false
                }
            }
        }
    }

    private fun handleBufferLock(payload: BufferLockPayload) {
        scheduledPlayJob?.cancel()
        timelineRefPosSec = payload.position
        timelineRate = 0.0
        isApplyingRemoteState = true
        isSyncing = true
        scope.launch(Dispatchers.Main) {
            try {
                playerConnection?.pause()
                delay(150)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error handling buffer lock")
            } finally {
                isSyncing = false
                isApplyingRemoteState = false
            }
        }
    }

    private fun handleSessionSnapshot(payload: SessionSnapshotPayload) {
        if (isHost) return
        if (lastAppliedSeqId >= 0 && payload.seqId < lastAppliedSeqId) return
        lastAppliedSeqId = payload.seqId
        timelineRefTime = payload.refTimestamp
        timelineRefPosSec = payload.refPosition
        timelineRate = payload.playbackRate

        val expectedPosMs = getTimelinePositionMs()
        Timber.tag(TAG).d("Applying SESSION_SNAPSHOT: rate=${payload.playbackRate}, expectedPos=${expectedPosMs}ms, track=${payload.trackId}")

        val connection = playerConnection ?: return
        val player = connection.player
        val currentTrackId = normalizeTrackId(player.currentMediaItem?.mediaId)
        val snapshotTrackId = normalizeTrackId(payload.trackId ?: payload.trackInfo?.id)

        if (payload.trackInfo != null && (snapshotTrackId.isNotEmpty() && currentTrackId != snapshotTrackId)) {
            applyPlaybackState(
                currentTrack = payload.trackInfo,
                isPlaying = payload.isPlaying,
                position = expectedPosMs,
                queue = payload.queue,
                bypassBuffer = false
            )
        } else {
            isApplyingRemoteState = true
            isSyncing = true
            if (kotlin.math.abs(player.currentPosition - expectedPosMs) > 100) {
                player.seekTo(expectedPosMs)
            }
            if (payload.isPlaying) {
                connection.play()
                lastSyncedIsPlaying = true
            } else {
                connection.pause()
                lastSyncedIsPlaying = false
            }
            scope.launch {
                delay(150)
                isSyncing = false
                isApplyingRemoteState = false
            }
        }
    }

    private fun updateGuestMuteState() {
        
        val connection = playerConnection ?: return
        
        restoreGuestMuteState()
    }
    
    
    private fun saveMuteStateOnJoin() {
        val connection = playerConnection ?: return
        
        if (previousMuteState == null) {
            previousMuteState = connection.isMuted.value
            Timber.tag(TAG).d("Saved mute state on join: ${previousMuteState}")
        }
    }

    
    private fun restoreGuestMuteState() {
        val connection = playerConnection ?: return
        val savedState = previousMuteState
        
        if (savedState != null) {
            Timber.tag(TAG).d("Restoring mute state on leave: was muted=$savedState, currently muted=${connection.isMuted.value}")
            connection.setMuted(savedState)
        } else {
            
            
            if (connection.isMuted.value) {
                Timber.tag(TAG).d("No saved mute state on leave, unmuting player as fallback")
                connection.setMuted(false)
            }
        }
        
        previousMuteState = null
        muteForcedByPreference = false
    }

    private fun applyHostVolumeIfNeeded(volume: Float?) {
        if (!syncHostVolumeEnabled.value || isHost || !isInRoom) return
        val connection = playerConnection ?: return
        val target = volume?.coerceIn(0f, 1f) ?: return
        connection.service.playerVolume.value = target
    }

    private fun applyPendingSyncIfReady() {
        val pending = pendingSyncState ?: return
        val pendingTrackId = pending.currentTrack?.id ?: bufferingTrackId ?: return
        val completeForTrack = bufferCompleteReceivedForTrack

        if (completeForTrack != pendingTrackId) return

        val connection = playerConnection ?: return
        val player = connection.player

        Timber.tag(TAG).d("Applying pending sync: track=$pendingTrackId, pos=${pending.position}, play=${pending.isPlaying}")
        isSyncing = true

        val targetPos = pending.position
        val posDiff = kotlin.math.abs(player.currentPosition - targetPos)
        val willPlay = pending.isPlaying
        
        
        val tolerance = if (willPlay && player.playWhenReady) PLAYBACK_POSITION_TOLERANCE_MS else POSITION_TOLERANCE_MS
        
        if (posDiff > tolerance) {
            Timber.tag(TAG).d("Applying pending sync: seeking ${player.currentPosition} -> $targetPos (diff ${posDiff}ms > ${tolerance}ms)")
            connection.seekTo(targetPos)
        } else {
            Timber.tag(TAG).d("Applying pending sync: skipping seek (diff ${posDiff}ms < ${tolerance}ms)")
        }

        
        if (willPlay && !player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: starting playback")
            connection.play()
        } else if (!willPlay && player.playWhenReady) {
            Timber.tag(TAG).d("Applying pending sync: pausing playback")
            connection.pause()
        }

        scope.launch {
            delay(200)
            isSyncing = false
        }

        bufferingTrackId = null
        pendingSyncState = null
        bufferCompleteReceivedForTrack = null
    }

    private var lastAppliedStateVersion = 0L

    private fun handlePlaybackSync(action: PlaybackActionPayload) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot sync playback - no player connection")
            return
        }
        val player = connection.player

        if (action.stateVersion > 0L) {
            if (action.stateVersion < lastAppliedStateVersion) {
                Timber.tag(TAG).d("Partner: Dropping stale sync action (v${action.stateVersion} < applied v$lastAppliedStateVersion)")
                return
            }
            lastAppliedStateVersion = action.stateVersion
        }
        
        Timber.tag(TAG).d("Handling playback sync: ${action.action}, position: ${action.position}, v=${action.stateVersion}")

        isSyncing = true

        try {
            when (action.action) {
                PlaybackActions.PLAY -> {
                    // Under Server-Authoritative Virtual Timeline, PLAY is managed by PLAY_SCHEDULED
                    Timber.tag(TAG).d("Partner: PLAY in SYNC_PLAYBACK received (managed authoritatively by PLAY_SCHEDULED)")
                    return
                }
                
                PlaybackActions.PAUSE -> {
                    // Under Server-Authoritative Virtual Timeline, PAUSE is managed by PAUSE_COMMAND
                    Timber.tag(TAG).d("Partner: PAUSE in SYNC_PLAYBACK received (managed authoritatively by PAUSE_COMMAND)")
                    return
                }

                PlaybackActions.SEEK -> {
                    // Under Server-Authoritative Virtual Timeline, SEEK is managed authoritatively by SEEK_COMMAND
                    Timber.tag(TAG).d("Partner: SEEK in SYNC_PLAYBACK received (managed authoritatively by SEEK_COMMAND)")
                    return
                }
                
                PlaybackActions.CHANGE_TRACK -> {
                    action.trackInfo?.let { track ->
                        Timber.tag(TAG).d("Partner: CHANGE_TRACK to ${track.title}, queue size=${action.queue?.size}")
                        
                        lastSyncActionTime = 0L
                        val currentLocalTrackId = normalizeTrackId(player.currentMediaItem?.mediaId)
                        val incomingTrackId = normalizeTrackId(track.id)
                        
                        val isAlreadyOnTrack = currentLocalTrackId.isNotEmpty() && currentLocalTrackId == incomingTrackId
                        if (isAlreadyOnTrack) {
                            // Guest has already seamlessly transitioned to this track via ExoPlayer queue!
                            Timber.tag(TAG).d("Guest: Already on track ${track.title} gaplessly, skipping queue reload, pause, and seek")
                            lastSyncedTrackId = track.id
                            lastSyncedIsPlaying = true
                            bufferingTrackId = null
                            isWaitingForPeersBuffer = false
                            timelineRefPosSec = (player.currentPosition.coerceAtLeast(0L) / 1000.0)
                            timelineRate = 1.0
                            timelineRefTime = System.currentTimeMillis()
                            resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 4000L
                            if (!player.isPlaying && player.playWhenReady) {
                                connection.play()
                            }
                            return
                        }

                        timelineRefPosSec = 0.0
                        timelineRate = 0.0
                        timelineRefTime = System.currentTimeMillis()
                        resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 4000L
                        
                        if (action.queue != null && action.queue.isNotEmpty()) {
                            val queueTitle = action.queueTitle
                            applyPlaybackState(
                                currentTrack = track,
                                isPlaying = true, 
                                position = 0,
                                queue = action.queue,
                                queueTitle = queueTitle,
                                bypassBuffer = false
                            )
                        } else {
                            bufferingTrackId = track.id
                            syncToTrack(track, true, 0)
                        }
                    }
                }
                
                PlaybackActions.SKIP_NEXT -> {
                    Timber.tag(TAG).d("Guest: SKIP_NEXT")
                    connection.seekToNext()
                }

                PlaybackActions.SKIP_PREV -> {
                    Timber.tag(TAG).d("Guest: SKIP_PREV")
                    connection.seekToPrevious()
                }

                PlaybackActions.QUEUE_ADD -> {
                    val track = action.trackInfo
                    if (track == null) {
                        Timber.tag(TAG).w("QUEUE_ADD missing trackInfo")
                    } else {
                        Timber.tag(TAG).d("Guest: QUEUE_ADD ${track.title}, insertNext=${action.insertNext == true}")
                        scope.launch(Dispatchers.IO) {
                            
                            YouTube.queue(listOf(track.id)).onSuccess { list ->
                                val mediaItem = list.firstOrNull()?.toMediaMetadata()?.copy(
                                    suggestedBy = track.suggestedBy
                                )?.toMediaItem()
                                if (mediaItem != null) {
                                    launch(Dispatchers.Main) {
                                        
                                        connection.allowInternalSync = true
                                        if (action.insertNext == true) {
                                            connection.playNext(mediaItem)
                                        } else {
                                            connection.addToQueue(mediaItem)
                                        }
                                        connection.allowInternalSync = false
                                    }
                                } else {
                                    Timber.tag(TAG).w("QUEUE_ADD failed to resolve media item for ${track.id}")
                                }
                            }.onFailure {
                                Timber.tag(TAG).e(it, "QUEUE_ADD metadata fetch failed")
                            }
                        }
                    }
                }

                PlaybackActions.QUEUE_REMOVE -> {
                    val removeId = action.trackId
                    if (removeId.isNullOrEmpty()) {
                        Timber.tag(TAG).w("QUEUE_REMOVE missing trackId")
                    } else {
                        
                        val startIndex = player.currentMediaItemIndex + 1
                        var removeIndex = -1
                        val total = player.mediaItemCount
                        for (i in startIndex until total) {
                            val id = player.getMediaItemAt(i).mediaId
                            if (id == removeId) { removeIndex = i; break }
                        }
                        if (removeIndex >= 0) {
                            Timber.tag(TAG).d("Guest: QUEUE_REMOVE index=$removeIndex id=$removeId")
                            player.removeMediaItem(removeIndex)
                        } else {
                            Timber.tag(TAG).w("QUEUE_REMOVE id not found in queue: $removeId")
                        }
                    }
                }

                PlaybackActions.QUEUE_CLEAR -> {
                    val currentIndex = player.currentMediaItemIndex
                    val count = player.mediaItemCount
                    val itemsAfter = count - (currentIndex + 1)
                    if (itemsAfter > 0) {
                        Timber.tag(TAG).d("Guest: QUEUE_CLEAR removing $itemsAfter items after current")
                        player.removeMediaItems(currentIndex + 1, count - (currentIndex + 1))
                    }
                }

                PlaybackActions.SET_VOLUME -> {
                    applyHostVolumeIfNeeded(action.volume)
                }

                PlaybackActions.SYNC_QUEUE -> {
                    val queue = action.queue
                    val queueTitle = action.queueTitle
                    if (queue != null) {
                        Timber.tag(TAG).d("Guest: SYNC_QUEUE size=${queue.size}")
                        
                        activeSyncJob?.cancel()
                        
                        scope.launch(Dispatchers.Main) {
                            if (playerConnection !== connection) return@launch
                            val player = connection.player
                            
                            
                            val mediaItems = queue.map { track ->
                                track.toMediaMetadata().toMediaItem()
                            }
                            
                            
                            val currentId = player.currentMediaItem?.mediaId
                            var newIndex = -1
                            if (currentId != null) {
                                newIndex = mediaItems.indexOfFirst { it.mediaId == currentId }
                            }
                            
                            val currentPos = player.currentPosition
                            val wasPlaying = player.isPlaying
                            
                            connection.allowInternalSync = true
                            if (newIndex != -1) {
                                player.setMediaItems(mediaItems, newIndex, currentPos)
                            } else {
                                player.setMediaItems(mediaItems)
                            }
                            connection.allowInternalSync = false

                            
                            if (wasPlaying && !player.isPlaying) {
                                connection.play()
                            }
                            
                            
                            try {
                                connection.service.queueTitle = queueTitle
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to set queue title during SYNC_QUEUE")
                            }
                        }
                    }
                }
            }
        } finally {
            if (action.action != PlaybackActions.CHANGE_TRACK) {
                scope.launch {
                    delay(200)
                    isSyncing = false
                }
            }
        }
    }
    
    private fun handleSyncState(state: SyncStatePayload) {
        if (state.stateVersion > 0L) {
            if (state.stateVersion < lastAppliedStateVersion) {
                Timber.tag(TAG).d("Partner: Dropping stale SyncState (v${state.stateVersion} < applied v$lastAppliedStateVersion)")
                return
            }
            lastAppliedStateVersion = state.stateVersion
        }
        val clockOffset = client.serverTimeOffset.value
        val now = System.currentTimeMillis()
        val projectedNow = now + clockOffset
        val adjustedPos = if (state.isPlaying) {
            state.position + kotlin.math.max(0L, projectedNow - state.lastUpdate)
        } else {
            state.position
        }

        Timber.tag(TAG).d("handleSyncState: playing=${state.isPlaying}, pos=${state.position} -> adj=$adjustedPos (clockOffset=${clockOffset}ms), track=${state.currentTrack?.id}")
        
        applyPlaybackState(
            currentTrack = state.currentTrack,
            isPlaying = state.isPlaying,
            position = adjustedPos,
            queue = state.queue,
            bypassBuffer = true  
        )
        applyHostVolumeIfNeeded(state.volume)
    }

    private fun applyPlaybackState(
        currentTrack: TrackInfo?,
        isPlaying: Boolean,
        position: Long,
        queue: List<TrackInfo>?,
        queueTitle: String? = null,  
        bypassBuffer: Boolean = false
    ) {
        val connection = playerConnection
        if (connection == null) {
            Timber.tag(TAG).w("Cannot apply playback state - no player")
            return
        }
        val player = connection.player

        Timber.tag(TAG).d("Applying playback state: track=${currentTrack?.id}, pos=$position, queue=${queue?.size}, bypassBuffer=$bypassBuffer")

        
        activeSyncJob?.cancel()

        
        if (currentTrack == null) {
            Timber.tag(TAG).d("No track in state, pausing")
            val generation = ++currentTrackGeneration
            scope.launch(Dispatchers.Main) {
                
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration")
                    return@launch
                }
                
                if (playerConnection !== connection) return@launch
                isSyncing = true
                connection.allowInternalSync = true
                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }
                    player.setMediaItems(mediaItems)
                } else if (queue != null) {
                    player.clearMediaItems()
                }
                connection.pause()
                try {
                    connection.service.queueTitle = queueTitle
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title for empty state")
                }
                connection.allowInternalSync = false
                isSyncing = false
            }
            return
        }

        bufferingTrackId = currentTrack.id
        val generation = ++currentTrackGeneration
        
        scope.launch(Dispatchers.Main) {
            
            if (currentTrackGeneration != generation) {
                Timber.tag(TAG).d("Skipping stale track generation: $generation vs current $currentTrackGeneration (track ${currentTrack.id})")
                return@launch
            }
            
            if (playerConnection !== connection) return@launch
            isSyncing = true
            connection.allowInternalSync = true
            lastSyncedTrackId = currentTrack.id
            lastSyncedIsPlaying = isPlaying

            try {
                
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Stale generation detected before setMediaItems: $generation vs $currentTrackGeneration")
                    return@launch
                }
                
                
                if (queue != null && queue.isNotEmpty()) {
                    val mediaItems = queue.map { it.toMediaMetadata().toMediaItem() }
                    val currentIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                    val newIds = mediaItems.map { it.mediaId }
                    var startIndex = mediaItems.indexOfFirst { it.mediaId == currentTrack.id }
                    if (startIndex == -1) startIndex = 0

                    if (currentIds == newIds && startIndex in 0 until player.mediaItemCount) {
                        Timber.tag(TAG).d("Reusing identical queue, seeking to index $startIndex at pos $position")
                        player.seekTo(startIndex, position)
                    } else {
                        Timber.tag(TAG).d("Setting new queue of size ${mediaItems.size} at index $startIndex pos $position")
                        player.setMediaItems(mediaItems, startIndex, position)
                    }
                } else {
                    Timber.tag(TAG).d("No queue in state, loading single track")
                    val item = currentTrack.toMediaMetadata().toMediaItem()
                    player.setMediaItems(listOf(item), 0, position)
                }
                
                player.prepare()
                connection.seekTo(position)  

                
                try {
                    connection.service.queueTitle = queueTitle ?: "Listen Together"
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to set queue title during applyPlaybackState")
                }
                
                if (bypassBuffer) {
                    
                    Timber.tag(TAG).d("Bypass buffer: immediately applying play=$isPlaying at pos=$position")
                    
                    
                    var attempts = 0
                    while (player.playbackState != Player.STATE_READY && attempts < 100) {
                        delay(50)
                        attempts++
                    }
                    if (player.playbackState == Player.STATE_READY) {
                        Timber.tag(TAG).d("Player ready after ${attempts * 50}ms, seeking to $position")
                        player.seekTo(position)
                        if (isPlaying) {
                            connection.play()
                            Timber.tag(TAG).d("Bypass: PLAY issued")
                        } else {
                            connection.pause()
                            Timber.tag(TAG).d("Bypass: PAUSE issued")
                        }
                    } else {
                        Timber.tag(TAG).w("Player not ready after 5s timeout during bypass sync")
                    }
                    
                    
                    pendingSyncState = null
                    bufferingTrackId = null
                    bufferCompleteReceivedForTrack = null
                } else {
                    val normCurrent = normalizeTrackId(currentTrack.id)
                    val normComplete = normalizeTrackId(bufferCompleteReceivedForTrack)
                    if (normComplete.isNotEmpty() && normComplete == normCurrent) {
                        Timber.tag(TAG).d("BufferComplete already received for track ${currentTrack.id}, starting playback directly")
                        bufferCompleteReceivedForTrack = null
                        isWaitingForPeersBuffer = false
                        bufferingTrackId = null
                        connection.play()
                        lastSyncedIsPlaying = true
                    } else {
                        connection.pause()
                        pendingSyncState = SyncStatePayload(
                            currentTrack = currentTrack,
                            isPlaying = isPlaying,
                            position = position,
                            lastUpdate = System.currentTimeMillis()
                        )
                        applyPendingSyncIfReady()
                        client.sendBufferReady(currentTrack.id)
                    }
                }
                
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error applying playback state")
            } finally {
                connection.allowInternalSync = false
                delay(400)
                isSyncing = false
            }
        }
    }

    private fun syncToTrack(track: TrackInfo, shouldPlay: Boolean, position: Long) {
        Timber.tag(TAG).d("syncToTrack: ${track.title}, play: $shouldPlay, pos: $position")

        
        bufferingTrackId = track.id
        val generation = currentTrackGeneration
        
        activeSyncJob?.cancel()
        activeSyncJob = scope.launch(Dispatchers.IO) {
            try {
                
                if (currentTrackGeneration != generation) {
                    Timber.tag(TAG).d("Skipping stale syncToTrack for ${track.id} (generation $generation vs $currentTrackGeneration)")
                    isSyncing = false
                    return@launch
                }
                
                
                YouTube.queue(listOf(track.id)).onSuccess { queue ->
                    Timber.tag(TAG).d("Got queue for track ${track.id}")
                    launch(Dispatchers.Main) {
                        
                        if (currentTrackGeneration != generation) {
                            Timber.tag(TAG).d("Skipping stale track application for ${track.id} (generation $generation vs $currentTrackGeneration)")
                            isSyncing = false
                            return@launch
                        }
                        
                        val connection = playerConnection ?: run {
                            isSyncing = false
                            return@launch
                        }
                        if (playerConnection !== connection) {
                            isSyncing = false
                            return@launch
                        }
                        isSyncing = true
                        
                        connection.allowInternalSync = true
                        connection.playQueue(
                            YouTubeQueue(
                                endpoint = WatchEndpoint(videoId = track.id),
                                preloadItem = queue.firstOrNull()?.toMediaMetadata()
                            )
                        )
                        try {
                            connection.service.queueTitle = "Listen Together" 
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Failed to set queue title")
                        }
                        connection.allowInternalSync = false
                        
                        
                        var waitCount = 0
                        while (waitCount < 40) { 
                            
                            if (currentTrackGeneration != generation) {
                                Timber.tag(TAG).d("Generation changed while waiting for player ready - aborting sync for ${track.id}")
                                isSyncing = false
                                return@launch
                            }
                            try {
                                val player = connection.player
                                if (player.playbackState == Player.STATE_READY) {
                                    Timber.tag(TAG).d("Player ready after ${waitCount * 50}ms")
                                    break
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Error checking player state")
                                break
                            }
                            delay(50)
                            waitCount++
                        }

                        
                        
                        connection.pause()

                        
                        pendingSyncState = SyncStatePayload(
                            currentTrack = track,
                            isPlaying = shouldPlay,
                            position = position,
                            lastUpdate = System.currentTimeMillis()
                        )

                        
                        applyPendingSyncIfReady()

                        
                        client.sendBufferReady(track.id)
                        Timber.tag(TAG).d("Sent buffer ready for ${track.id}, pending sync stored: pos=$position, play=$shouldPlay")

                        
                        delay(100)
                        isSyncing = false
                    }
                }.onFailure { e ->
                    Timber.tag(TAG).e(e, "Failed to load track ${track.id}")
                    playerConnection?.allowInternalSync = false
                    isSyncing = false
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error syncing to track")
                playerConnection?.allowInternalSync = false
                isSyncing = false
            }
        }
    }

    

    
    fun connect() {
        Timber.tag(TAG).d("Connecting to server")
        client.connect()
    }

    
    fun disconnect() {
        Timber.tag(TAG).d("Disconnecting")
        cleanup()
        p2pPartnerManager.disconnect()
        client.disconnect()
    }

    
    fun createRoom(username: String) {
        Timber.tag(TAG).d("Creating room with username: $username")
        client.createRoom(username)
    }

    
    fun joinRoom(roomCode: String, username: String) {
        Timber.tag(TAG).d("Joining room $roomCode as $username")
        client.joinRoom(roomCode, username)
    }

    
    fun leaveRoom() {
        Timber.tag(TAG).d("Leaving room")
        cleanup()
        p2pPartnerManager.disconnect()
        client.leaveRoom()
    }

    fun connectToPartner(partnerAddress: String, username: String) {
        Timber.tag(TAG).d("Connecting to P2P Partner: $partnerAddress as $username")
        p2pPartnerManager.connectToPartner(partnerAddress, username)
    }

    fun hostP2PSession(username: String) {
        Timber.tag(TAG).d("Hosting P2P session as $username")
        p2pPartnerManager.hostLocalSession(username)
    }

    fun disconnectP2P() {
        Timber.tag(TAG).d("Disconnecting P2P session")
        cleanup()
        p2pPartnerManager.disconnect()
        client.disconnect()
    }

    fun startPeerDiscovery() {
        p2pPartnerManager.discovery.startDiscovery()
    }

    fun stopPeerDiscovery() {
        p2pPartnerManager.discovery.stopDiscovery()
    }


    
    fun approveJoin(userId: String) = client.approveJoin(userId)

    
    fun rejectJoin(userId: String, reason: String? = null) = client.rejectJoin(userId, reason)

    
    fun kickUser(userId: String, reason: String? = null) = client.kickUser(userId, reason)

    
    fun blockUser(username: String) = client.blockUser(username)

    
    fun unblockUser(username: String) = client.unblockUser(username)

    
    fun getBlockedUsernames(): Set<String> = blockedUsernames.value

    
    fun transferHost(newHostId: String) = client.transferHost(newHostId)

    fun updateRoomSettings(allowParticipantControl: Boolean) {
        client.updateRoomSettings(allowParticipantControl)
        refreshSyncCapabilities()
    }

    
    fun sendTrackChange(metadata: MediaMetadata) {
        if (!canControlMusic || isSyncing) return
        sendTrackChangeInternal(metadata)
    }
    
    
    private fun sendTrackChangeInternal(metadata: MediaMetadata) {
        if (!canControlMusic) return
        
        timelineRefPosSec = 0.0
        timelineRate = 0.0
        timelineRefTime = System.currentTimeMillis()
        resumeGracePeriodUntil = SystemClock.elapsedRealtime() + 4000L
        
        
        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
        
        val trackInfo = TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )
        
        Timber.tag(TAG).d("Sending track change: ${trackInfo.title}, duration: $durationMs")
        
        
        val currentQueue = try {
            playerConnection?.queueWindows?.value?.map { it.toTrackInfo() }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current queue")
            null
        }
        val currentTitle = try {
            playerConnection?.queueTitle?.value
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get current title")
            null
        }
        
        sendPlaybackActionWithSync {
            client.sendPlaybackAction(
                PlaybackActions.CHANGE_TRACK,
                queueTitle = currentTitle,
                trackInfo = trackInfo,
                queue = currentQueue
            )
        }
    }

    fun sendCurrentQueueSync() {
        val connection = playerConnection ?: return
        try {
            val tracks = connection.queueWindows.value.map { it.toTrackInfo() }
            if (tracks.isNotEmpty()) {
                val queueTitle = try {
                    connection.queueTitle.value
                } catch (e: Exception) {
                    null
                }
                Timber.tag(TAG).d("sendCurrentQueueSync: syncing ${tracks.size} items")
                sendPlaybackActionWithSync {
                    client.sendPlaybackAction(
                        PlaybackActions.SYNC_QUEUE,
                        queueTitle = queueTitle,
                        queue = tracks
                    )
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error sending current queue sync")
        }
    }

    private fun startQueueSyncObservation() {
        queueObserverJob?.cancel()
        val connection = playerConnection ?: return
    
        Timber.tag(TAG).d("Starting queue sync observation")
        queueObserverJob = scope.launch {
            connection.queueWindows
                .map { windows ->
                    windows.map { it.toTrackInfo() }
                }
                .distinctUntilChanged()
                .collectLatest { tracks ->
                    if (!canControlMusic || !isInRoom) return@collectLatest
                    if (isSyncing || tracks.isEmpty()) return@collectLatest
                
                    delay(500) 
                
                    Timber.tag(TAG).d("Sending SYNC_QUEUE with ${tracks.size} items")
                    val queueTitle = try {
                        connection.queueTitle.value
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to get queue title")
                        null
                    }
                    sendPlaybackActionWithSync {
                        client.sendPlaybackAction(
                            PlaybackActions.SYNC_QUEUE,
                            queueTitle = queueTitle,
                            queue = tracks
                        )
                    }
                }
        }
    }

    private fun startVolumeSyncObservation() {
        if (volumeObserverJob?.isActive == true) return

        Timber.tag(TAG).d("Starting volume sync observation")
        volumeObserverJob = scope.launch {
            playerConnection?.service?.playerVolume
                ?.collectLatest { volume ->
                    if (!isHost || !isInRoom || !syncHostVolumeEnabled.value) return@collectLatest

                    val normalized = volume.coerceIn(0f, 1f)
                    val last = lastSyncedVolume
                    if (last != null && kotlin.math.abs(last - normalized) < 0.01f) return@collectLatest

                    lastSyncedVolume = normalized
                    client.sendPlaybackAction(PlaybackActions.SET_VOLUME, volume = normalized)
                }
        }
    }

    private fun stopVolumeSyncObservation() {
        volumeObserverJob?.cancel()
        volumeObserverJob = null
        lastSyncedVolume = null
    }

    private fun androidx.media3.common.Timeline.Window.toTrackInfo(): TrackInfo {
        val metadata = mediaItem.metadata ?: return TrackInfo("unknown", "Unknown", "Unknown", "", 0, "")
        val durationMs = if (metadata.duration > 0) metadata.duration.toLong() * 1000 else 180000L
        return TrackInfo(
            id = metadata.id,
            title = metadata.title,
            artist = metadata.artists.joinToString(", ") { it.name },
            album = metadata.album?.title,
            duration = durationMs,
            thumbnail = metadata.thumbnailUrl,
            suggestedBy = metadata.suggestedBy
        )
    }

    private fun stopQueueSyncObservation() {
        queueObserverJob?.cancel()
        queueObserverJob = null
    }

    private fun TrackInfo.toMediaMetadata(): MediaMetadata {
        return MediaMetadata(
            id = id,
            title = title,
            artists = listOf(Artist(id = "", name = artist)),
            album = if (album != null) Album(id = "", title = album) else null,
            duration = (duration / 1000).toInt(),
            thumbnailUrl = thumbnail,
            suggestedBy = suggestedBy
        )
    }

    
    fun requestSync() {
        if (!isGuestPlaybackRestricted) {
            Timber.tag(TAG).d("requestSync: not applicable (isGuestPlaybackRestricted=$isGuestPlaybackRestricted)")
            return
        }
        Timber.tag(TAG).d("Requesting sync from server")
        client.requestSync()
    }

    
    fun clearLogs() = client.clearLogs()

    

    
    fun suggestTrack(track: TrackInfo) = client.suggestTrack(track)

    
    fun approveSuggestion(suggestionId: String) {
        if (!isHost) return
        
        client.approveSuggestion(suggestionId)
    }

    
    fun rejectSuggestion(suggestionId: String, reason: String? = null) = client.rejectSuggestion(suggestionId, reason)
    
    
    fun forceReconnect() {
        Timber.tag(TAG).d("Forcing reconnection")
        client.forceReconnect()
    }
    
    
    fun getPersistedRoomCode(): String? = client.getPersistedRoomCode()
    
    
    fun getSessionAge(): Long = client.getSessionAge()

    
    private var heartbeatJob: Job? = null

    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (heartbeatJob?.isActive == true && isInRoom && isHost) {
                delay(10000L) 
                playerConnection?.player?.let { player ->
                    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
                        val pos = player.currentPosition
                        Timber.tag(TAG).d("Host heartbeat: sending PLAY at pos $pos")
                        client.sendPlaybackAction(PlaybackActions.PLAY, position = pos)
                    }
                }
            }
        }
        Timber.tag(TAG).d("Host heartbeat started (10s interval)")
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        Timber.tag(TAG).d("Host heartbeat stopped")
    }

    
    fun sendChatMessage(message: String, replyTo: RepliedMessage? = null) {
        if (message.isBlank()) return
        client.sendChatMessage(message, replyTo)
    }
}
