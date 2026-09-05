package com.music.jiosaavn

import android.os.Build
import android.text.Html
import com.music.jiosaavn.api.JioSaavnClient
import com.music.jiosaavn.crypto.JioSaavnDecryptor
import com.music.jiosaavn.models.JioSaavnResolvedTrack
import com.music.jiosaavn.models.JioSaavnSongRaw
import kotlin.math.abs

object JioSaavnAudioResolver {

    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remastered|live|acoustic|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remastered|live|acoustic|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
    )

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) {
            cleaned = cleaned.replace(pattern, "")
        }
        return cleaned.trim()
    }

    private fun unescapeHtml(text: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString().trim()
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(text).toString().trim()
            }
        } catch (e: Exception) {
            text.replace("&amp;", "&")
                .replace("&#039;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
        }
    }

    /**
     * Resolves a matching track on JioSaavn and returns a 320 kbps stream URL.
     * Returns null if no exact or duration-compatible match is found, triggering fallback to YouTube Opus.
     */
    suspend fun resolveStream(
        title: String?,
        artist: String?,
        durationSec: Int? = null,
        durationToleranceSec: Int = 4
    ): JioSaavnResolvedTrack? {
        if (title.isNullOrBlank()) return null

        val cleanTitle = cleanTitle(title)
        val cleanArtist = artist?.replace(" - Topic", "")?.trim().orEmpty()

        // 1. Search with title and artist
        val primaryQuery = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
        var results = JioSaavnClient.searchSongs(primaryQuery, limit = 5)

        // Fallback to title alone if no results
        if (results.isEmpty() && cleanArtist.isNotBlank()) {
            results = JioSaavnClient.searchSongs(cleanTitle, limit = 5)
        }

        if (results.isEmpty()) return null

        // 2. Find best candidate with duration matching
        val matchedSong = findBestMatch(
            candidates = results,
            targetTitle = cleanTitle,
            targetDurationSec = durationSec,
            durationToleranceSec = durationToleranceSec
        ) ?: return null

        // 3. Decrypt 320kbps media URL
        val streamUrl = JioSaavnDecryptor.decryptMediaUrl(matchedSong.encryptedMediaUrl)
            ?: return null

        return JioSaavnResolvedTrack(
            id = matchedSong.id,
            title = unescapeHtml(matchedSong.song),
            artist = unescapeHtml(matchedSong.primaryArtists),
            durationSec = matchedSong.durationSeconds,
            streamUrl = streamUrl
        )
    }

    private fun findBestMatch(
        candidates: List<JioSaavnSongRaw>,
        targetTitle: String,
        targetDurationSec: Int?,
        durationToleranceSec: Int
    ): JioSaavnSongRaw? {
        val normalizedTarget = normalizeString(targetTitle)

        // Filter candidates with valid encrypted media urls
        val eligible = candidates.filter { it.encryptedMediaUrl.isNotBlank() }

        // If target duration is known, strictly require duration match
        if (targetDurationSec != null && targetDurationSec > 0) {
            val durationMatched = eligible.filter {
                abs(it.durationSeconds - targetDurationSec) <= durationToleranceSec
            }

            // Among duration matched, find best title match
            durationMatched.forEach { candidate ->
                val candidateTitle = normalizeString(unescapeHtml(candidate.song))
                if (isTitleCompatible(candidateTitle, normalizedTarget)) {
                    return candidate
                }
            }

            // If strict title compatibility didn't match, check first duration match if title overlaps
            durationMatched.firstOrNull { candidate ->
                val candidateTitle = normalizeString(unescapeHtml(candidate.song))
                candidateTitle.contains(normalizedTarget) || normalizedTarget.contains(candidateTitle)
            }?.let { return it }

            return null
        }

        // Duration unknown - fallback to title matching
        return eligible.firstOrNull { candidate ->
            val candidateTitle = normalizeString(unescapeHtml(candidate.song))
            isTitleCompatible(candidateTitle, normalizedTarget)
        }
    }

    private fun normalizeString(str: String): String {
        return str.lowercase()
            .replace(Regex("""[^a-z0-9\s]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun isTitleCompatible(candidate: String, target: String): Boolean {
        if (candidate == target) return true
        if (candidate.startsWith(target) || target.startsWith(candidate)) return true
        val targetWords = target.split(" ").filter { it.length > 2 }
        if (targetWords.isNotEmpty() && targetWords.all { candidate.contains(it) }) return true
        return false
    }
}
