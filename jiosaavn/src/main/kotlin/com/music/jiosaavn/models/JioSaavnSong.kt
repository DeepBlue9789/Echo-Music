package com.music.jiosaavn.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class JioSaavnSearchResponse(
    val results: List<JioSaavnSongRaw> = emptyList(),
    val total: Int = 0
)

@Serializable
data class JioSaavnSongRaw(
    val id: String = "",
    val title: String = "",
    val song: String = "",
    val album: String = "",
    val year: String = "",
    @SerialName("primary_artists")
    val primaryArtists: String = "",
    val singers: String = "",
    val duration: String = "0",
    @SerialName("320kbps")
    val has320kbps: JsonElement? = null,
    @SerialName("encrypted_media_url")
    val encryptedMediaUrl: String = "",
    @SerialName("media_preview_url")
    val mediaPreviewUrl: String = "",
    val image: String = "",
    @SerialName("more_info")
    val moreInfo: JioSaavnMoreInfo? = null
) {
    val effectiveTitle: String
        get() = song.ifBlank { title }

    val effectiveArtists: String
        get() = primaryArtists.ifBlank {
            moreInfo?.artistMap?.primaryArtists?.joinToString(", ") { it.name }
                ?: moreInfo?.music.orEmpty()
        }

    val effectiveEncryptedMediaUrl: String
        get() = encryptedMediaUrl.ifBlank { moreInfo?.encryptedMediaUrl.orEmpty() }

    val is320Available: Boolean
        get() {
            val prim = (has320kbps ?: moreInfo?.has320kbps)?.jsonPrimitive ?: return true
            return prim.booleanOrNull ?: (prim.content.equals("true", ignoreCase = true))
        }

    val durationSeconds: Int
        get() {
            val d = duration.toIntOrNull() ?: moreInfo?.duration?.toIntOrNull() ?: 0
            return d
        }
}

@Serializable
data class JioSaavnMoreInfo(
    val music: String = "",
    val album: String = "",
    @SerialName("320kbps")
    val has320kbps: JsonElement? = null,
    @SerialName("encrypted_media_url")
    val encryptedMediaUrl: String = "",
    val duration: String = "0",
    val artistMap: JioSaavnArtistMap? = null
)

@Serializable
data class JioSaavnArtistMap(
    @SerialName("primary_artists")
    val primaryArtists: List<JioSaavnArtist> = emptyList(),
    val artists: List<JioSaavnArtist> = emptyList()
)

@Serializable
data class JioSaavnArtist(
    val id: String = "",
    val name: String = "",
    val role: String = ""
)

data class JioSaavnResolvedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val streamUrl: String,
    val bitrate: Int = 320000,
    val mimeType: String = "audio/mp4; codecs=\"mp4a.40.2\""
)
