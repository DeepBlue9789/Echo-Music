package com.music.jiosaavn.crypto

import android.util.Base64
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object JioSaavnDecryptor {
    private const val CIPHER_KEY = "38346591"

    /**
     * Decrypts JioSaavn DES-encrypted media URL and elevates the bitrate suffix to 320 kbps.
     */
    fun decryptMediaUrl(encryptedUrl: String): String? {
        if (encryptedUrl.isBlank()) return null
        return runCatching {
            val keyBytes = CIPHER_KEY.toByteArray(StandardCharsets.UTF_8)
            val secretKey = SecretKeySpec(keyBytes, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)

            val decodedBytes = try {
                java.util.Base64.getDecoder().decode(encryptedUrl.trim())
            } catch (e: Throwable) {
                android.util.Base64.decode(encryptedUrl.trim(), android.util.Base64.DEFAULT)
            }
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val rawUrl = String(decryptedBytes, StandardCharsets.UTF_8)

            // Elevate quality suffix to 320 kbps
            rawUrl.replace(Regex("(_96|_160)\\.mp4"), "_320.mp4")
                .replace(Regex("(_96|_160)\\.m4a"), "_320.m4a")
        }.getOrNull()
    }
}
