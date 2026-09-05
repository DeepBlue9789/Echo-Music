package com.music.jiosaavn

import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class JioSaavnApiFetchTest {
    @Test
    fun testResolveGodMode() = kotlinx.coroutines.runBlocking {
        val resolved = com.music.jiosaavn.JioSaavnAudioResolver.resolveStream(
            title = "God Mode (From \"Karuppu\")",
            artist = "Sai Abhyankkar",
            durationSec = 241
        )
        println("RESOLVED RESULT: $resolved")
        org.junit.Assert.assertNotNull(resolved)
        org.junit.Assert.assertTrue(resolved!!.streamUrl.startsWith("https://"))
    }

    @Test
    fun testDnsResolution() {
        val saavnIps = com.music.jiosaavn.api.JioSaavnDns.lookup("www.jiosaavn.com")
        println("Resolved www.jiosaavn.com: $saavnIps")
        org.junit.Assert.assertTrue(saavnIps.isNotEmpty())

        val cdnIps = com.music.jiosaavn.api.JioSaavnDns.lookup("aac.saavncdn.com")
        println("Resolved aac.saavncdn.com: $cdnIps")
        org.junit.Assert.assertTrue(cdnIps.isNotEmpty())
    }
}
