package com.music.jiosaavn

import com.music.jiosaavn.crypto.JioSaavnDecryptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JioSaavnDecryptorTest {

    @Test
    fun testDecryptMediaUrlAndBitrateElevation() {
        // Sample encrypted string from our live verification test for "Believer"
        val encrypted = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDyyhgaxVOal3PGm0YNmM9WgspoyIz9Yf/yFcJvtE+o5lr6n9ja0+ZPexw7tS9a8Gtq"
        val decrypted = JioSaavnDecryptor.decryptMediaUrl(encrypted)

        assertNotNull(decrypted)
        assertTrue(decrypted!!.startsWith("https://"))
        assertTrue(decrypted.endsWith("_320.mp4") || decrypted.endsWith("_320.m4a"))
        assertEquals("https://aac.saavncdn.com/382/72e21a6f8eb7d2ca780df6d09d52f5e4_320.mp4", decrypted)
    }

    @Test
    fun testEmptyOrInvalidEncryptedUrlReturnsNull() {
        assertEquals(null, JioSaavnDecryptor.decryptMediaUrl(""))
        assertEquals(null, JioSaavnDecryptor.decryptMediaUrl("invalid_base64_!@#$"))
    }
}
