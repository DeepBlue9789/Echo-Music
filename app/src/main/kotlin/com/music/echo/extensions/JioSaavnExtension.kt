package com.music.echo.extensions

import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import com.music.jiosaavn.JioSaavnAudioResolver
import com.music.jiosaavn.api.JioSaavnClient
import com.music.jiosaavn.crypto.JioSaavnDecryptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

object JioSaavnExtension {
    const val ID = "builtin_jiosaavn"
    const val NAME = "JioSaavn (Built-in)"
    const val URL = "builtin://jiosaavn"
    const val DESCRIPTION = "320 kbps High Quality Audio (Indian & International Catalog)"

    fun createEndpoint(enabled: Boolean = true): AddonEndpoint {
        return AddonEndpoint(
            id = ID,
            name = NAME,
            url = URL,
            enabled = enabled
        )
    }

    fun isBuiltinId(id: String): Boolean = id == ID || id.startsWith("addon_${ID}")

    suspend fun search(query: String): List<SongItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val results = JioSaavnClient.searchSongs(query, limit = 15)
            results.map { song ->
                val primaryArtist = song.moreInfo?.artistMap?.primaryArtists?.joinToString(", ") { it.name }
                    ?: song.singers.ifBlank { "Unknown Artist" }
                val highResThumbnail = (song.image ?: "")
                    .replace("150x150", "500x500")
                    .replace("50x50", "500x500")

                SongItem(
                    id = "addon_${ID}_${song.id}",
                    title = song.song.replace("&quot;", "\"").replace("&amp;", "&"),
                    artists = listOf(Artist(name = primaryArtist, id = null)),
                    thumbnail = highResThumbnail,
                    duration = song.durationSeconds
                )
            }
        } catch (e: Exception) {
            Timber.tag("JioSaavnExtension").e(e, "Error searching JioSaavn")
            emptyList()
        }
    }

    suspend fun resolveStream(
        title: String?,
        artist: String?,
        durationSec: Int?
    ): AddonStreamResponse? = withContext(Dispatchers.IO) {
        try {
            val resolved = JioSaavnAudioResolver.resolveStream(
                title = title,
                artist = artist,
                durationSec = durationSec
            ) ?: return@withContext null

            Timber.tag("JioSaavnExtension").i("Resolved JioSaavn stream: ${resolved.title} by ${resolved.artist}")

            AddonStreamResponse(
                url = resolved.streamUrl,
                format = "m4a",
                quality = "320kbps",
                streamQuality = "320kbps",
                audioQuality = "AUDIO_QUALITY_HIGH",
                codec = "AAC"
            )
        } catch (e: Exception) {
            Timber.tag("JioSaavnExtension").e(e, "Error resolving JioSaavn stream")
            null
        }
    }

    suspend fun resolveDirectTrack(trackId: String): AddonStreamResponse? = withContext(Dispatchers.IO) {
        try {
            val songs = JioSaavnClient.searchSongs(trackId, limit = 5)
            val matched = songs.firstOrNull { it.id == trackId } ?: songs.firstOrNull()
            if (matched != null) {
                val streamUrl = JioSaavnDecryptor.decryptMediaUrl(matched.effectiveEncryptedMediaUrl)
                if (!streamUrl.isNullOrBlank()) {
                    return@withContext AddonStreamResponse(
                        url = streamUrl,
                        format = "m4a",
                        quality = "320kbps",
                        streamQuality = "320kbps",
                        audioQuality = "AUDIO_QUALITY_HIGH",
                        codec = "AAC"
                    )
                }
            }
            null
        } catch (e: Exception) {
            Timber.tag("JioSaavnExtension").e(e, "Error resolving direct JioSaavn track $trackId")
            null
        }
    }
}
