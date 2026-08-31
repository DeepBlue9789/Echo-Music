

package echo.music.iad1tya.listentogether

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


object MessageTypes {
    
    const val CREATE_ROOM = "create_room"
    const val JOIN_ROOM = "join_room"
    const val LEAVE_ROOM = "leave_room"
    const val APPROVE_JOIN = "approve_join"
    const val REJECT_JOIN = "reject_join"
    const val PLAYBACK_ACTION = "playback_action"
    const val BUFFER_READY = "buffer_ready"
    const val KICK_USER = "kick_user"
    const val TRANSFER_HOST = "transfer_host"
    const val PING = "ping"
    const val CHAT = "chat"
    const val REQUEST_SYNC = "request_sync"
    const val RECONNECT = "reconnect"
    const val SUGGEST_TRACK = "suggest_track"
    const val APPROVE_SUGGESTION = "approve_suggestion"
    const val REJECT_SUGGESTION = "reject_suggestion"
    const val UPDATE_ROOM_SETTINGS = "update_room_settings"

    
    const val ROOM_CREATED = "room_created"
    const val JOIN_REQUEST = "join_request"
    const val JOIN_APPROVED = "join_approved"
    const val JOIN_REJECTED = "join_rejected"
    const val USER_JOINED = "user_joined"
    const val USER_LEFT = "user_left"
    const val SYNC_PLAYBACK = "sync_playback"
    const val BUFFER_WAIT = "buffer_wait"
    const val BUFFER_COMPLETE = "buffer_complete"
    const val ERROR = "error"
    const val PONG = "pong"
    const val HOST_CHANGED = "host_changed"
    const val KICKED = "kicked"
    const val SYNC_STATE = "sync_state"
    const val RECONNECTED = "reconnected"
    const val USER_RECONNECTED = "user_reconnected"
    const val USER_DISCONNECTED = "user_disconnected"
    const val SUGGESTION_RECEIVED = "suggestion_received"
    const val SUGGESTION_APPROVED = "suggestion_approved"
    const val SUGGESTION_REJECTED = "suggestion_rejected"
    const val ROOM_SETTINGS_CHANGED = "room_settings_changed"
    
    // Virtual Timeline Sync Protocol
    const val CLOCK_SYNC_REQ = "CLOCK_SYNC_REQ"
    const val CLOCK_SYNC_RES = "CLOCK_SYNC_RES"
    const val PLAY_SCHEDULED = "PLAY_SCHEDULED"
    const val PAUSE_COMMAND = "PAUSE_COMMAND"
    const val SEEK_COMMAND = "SEEK_COMMAND"
    const val BUFFER_LOCK = "BUFFER_LOCK"
    const val BUFFER_READY_EVENT = "BUFFER_READY_EVENT"
    const val SESSION_SNAPSHOT = "SESSION_SNAPSHOT"
}


object PlaybackActions {
    const val PLAY = "play"
    const val PAUSE = "pause"
    const val SEEK = "seek"
    const val SKIP_NEXT = "skip_next"
    const val SKIP_PREV = "skip_prev"
    const val CHANGE_TRACK = "change_track"
    const val QUEUE_ADD = "queue_add"
    const val QUEUE_REMOVE = "queue_remove"
    const val QUEUE_CLEAR = "queue_clear"
    const val SYNC_QUEUE = "sync_queue"
    const val SET_VOLUME = "set_volume"
}


@Serializable
data class Message(
    val type: String,
    val payload: JsonElement? = null
)


@Serializable
data class TrackInfo(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long, 
    val thumbnail: String? = null,
    @SerialName("suggested_by") val suggestedBy: String? = null
)


@Serializable
data class UserInfo(
    @SerialName("user_id") val userId: String,
    val username: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("is_connected") val isConnected: Boolean = true
)


@Serializable
data class RoomState(
    @SerialName("room_code") val roomCode: String,
    @SerialName("host_id") val hostId: String,
    val users: List<UserInfo>,
    @SerialName("current_track") val currentTrack: TrackInfo? = null,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long, 
    @SerialName("last_update") val lastUpdate: Long, 
    val volume: Float = 1f,
    val queue: List<TrackInfo> = emptyList(),
    @SerialName("allow_participant_control") val allowParticipantControl: Boolean = false,
    @SerialName("state_version") val stateVersion: Long = 0L
)

@Serializable
data class UpdateRoomSettingsPayload(
    @SerialName("allow_participant_control") val allowParticipantControl: Boolean
)



@Serializable
data class CreateRoomPayload(
    val username: String
)

@Serializable
data class JoinRoomPayload(
    @SerialName("room_code") val roomCode: String,
    val username: String
)

@Serializable
data class ApproveJoinPayload(
    @SerialName("user_id") val userId: String
)

@Serializable
data class RejectJoinPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null
)

@Serializable
data class PlaybackActionPayload(
    val action: String,
    @SerialName("track_id") val trackId: String? = null,
    val position: Long? = null, 
    @SerialName("track_info") val trackInfo: TrackInfo? = null,
    @SerialName("insert_next") val insertNext: Boolean? = null,
    val queue: List<TrackInfo>? = null,
    @SerialName("queue_title") val queueTitle: String? = null,
    val volume: Float? = null,
    @SerialName("server_time") val serverTime: Long? = null,
    @SerialName("state_version") val stateVersion: Long = 0L
)

@Serializable
data class PongPayload(
    @SerialName("server_time") val serverTime: Long = 0L
)

@Serializable
data class BufferReadyPayload(
    @SerialName("track_id") val trackId: String
)

@Serializable
data class KickUserPayload(
    @SerialName("user_id") val userId: String,
    val reason: String? = null
)

@Serializable
data class TransferHostPayload(
    @SerialName("new_host_id") val newHostId: String
)

@Serializable
data class ChatPayload(
    val message: String,
    @SerialName("reply_to") val replyTo: RepliedMessage? = null
)

@Serializable
data class RepliedMessage(
    val username: String,
    val message: String,
    val thumbnail: String? = null
)



@Serializable
data class SuggestTrackPayload(
    @SerialName("track_info") val trackInfo: TrackInfo
)

@Serializable
data class SuggestionReceivedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("from_user_id") val fromUserId: String,
    @SerialName("from_username") val fromUsername: String,
    @SerialName("track_info") val trackInfo: TrackInfo
)

@Serializable
data class ApproveSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String
)

@Serializable
data class RejectSuggestionPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    val reason: String? = null
)

@Serializable
data class SuggestionApprovedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    @SerialName("track_info") val trackInfo: TrackInfo
)

@Serializable
data class SuggestionRejectedPayload(
    @SerialName("suggestion_id") val suggestionId: String,
    val reason: String? = null
)



@Serializable
data class RoomCreatedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String
)

@Serializable
data class JoinRequestPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class JoinApprovedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    @SerialName("session_token") val sessionToken: String,
    val state: RoomState
)

@Serializable
data class JoinRejectedPayload(
    val reason: String
)

@Serializable
data class UserJoinedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class UserLeftPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class BufferWaitPayload(
    @SerialName("track_id") val trackId: String,
    @SerialName("waiting_for") val waitingFor: List<String>
)

@Serializable
data class BufferCompletePayload(
    @SerialName("track_id") val trackId: String
)

@Serializable
data class ErrorPayload(
    val code: String,
    val message: String
)

@Serializable
data class ChatMessagePayload(
    @SerialName("user_id") val userId: String,
    val username: String,
    val message: String,
    val timestamp: Long,
    @SerialName("reply_to") val replyTo: RepliedMessage? = null,
    @SerialName("track_info") val trackInfo: TrackInfo? = null
)

@Serializable
data class HostChangedPayload(
    @SerialName("new_host_id") val newHostId: String,
    @SerialName("new_host_name") val newHostName: String
)

@Serializable
data class KickedPayload(
    val reason: String
)


@Serializable
data class SyncStatePayload(
    @SerialName("current_track") val currentTrack: TrackInfo?,
    @SerialName("is_playing") val isPlaying: Boolean,
    val position: Long,
    @SerialName("last_update") val lastUpdate: Long,
    val queue: List<TrackInfo>? = null,
    val volume: Float? = null,
    @SerialName("state_version") val stateVersion: Long = 0L
)



@Serializable
data class ReconnectPayload(
    @SerialName("session_token") val sessionToken: String
)

@Serializable
data class ReconnectedPayload(
    @SerialName("room_code") val roomCode: String,
    @SerialName("user_id") val userId: String,
    val state: RoomState,
    @SerialName("is_host") val isHost: Boolean
)

@Serializable
data class UserReconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

@Serializable
data class UserDisconnectedPayload(
    @SerialName("user_id") val userId: String,
    val username: String
)

// -------------------------------------------------------------
// Virtual Timeline Models & Mathematical Synchronization Engine
// -------------------------------------------------------------

@Serializable
data class ClockSyncRequestPayload(
    @SerialName("client_t1") val clientT1: Long
)

@Serializable
data class ClockSyncResponsePayload(
    @SerialName("client_t1") val clientT1: Long,
    @SerialName("server_t2") val serverT2: Long,
    @SerialName("server_t3") val serverT3: Long
)

@Serializable
data class PlayScheduledPayload(
    @SerialName("seq_id") val seqId: Long,
    @SerialName("execute_at") val executeAt: Long, // Synchronized network epoch timestamp (ms)
    @SerialName("start_position") val startPosition: Double // Track position in seconds
)

@Serializable
data class PauseCommandPayload(
    @SerialName("seq_id") val seqId: Long,
    @SerialName("pause_position") val pausePosition: Double, // Authoritative track position in seconds
    @SerialName("server_timestamp") val serverTimestamp: Long
)

@Serializable
data class SeekCommandPayload(
    @SerialName("seq_id") val seqId: Long,
    @SerialName("target_position") val targetPosition: Double,
    @SerialName("auto_play") val autoPlay: Boolean,
    @SerialName("execute_at") val executeAt: Long? = null
)

@Serializable
data class BufferLockPayload(
    @SerialName("client_id") val clientId: String,
    val position: Double
)

@Serializable
data class BufferReadyEventPayload(
    @SerialName("client_id") val clientId: String,
    @SerialName("buffered_ahead_seconds") val bufferedAheadSeconds: Double
)

@Serializable
data class SessionSnapshotPayload(
    @SerialName("seq_id") val seqId: Long,
    @SerialName("is_playing") val isPlaying: Boolean,
    @SerialName("ref_timestamp") val refTimestamp: Long,
    @SerialName("ref_position") val refPosition: Double,
    @SerialName("playback_rate") val playbackRate: Double,
    @SerialName("track_id") val trackId: String?,
    @SerialName("track_info") val trackInfo: TrackInfo? = null,
    val queue: List<TrackInfo> = emptyList(),
    @SerialName("server_time") val serverTime: Long
)
