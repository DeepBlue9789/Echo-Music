package com.music.jiosaavn.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Dns
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

object JioSaavnDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    private val jsonConfig = Json {
        isLenient = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private data class DohResponse(
        val Answer: List<DohAnswer>? = null
    )

    @Serializable
    private data class DohAnswer(
        val type: Int = 0,
        val data: String = ""
    )

    // Known Akamai edge IPs for JioSaavn services in case of local network/VPN DNS failure
    private val hardcodedFallbacks = mapOf(
        "www.jiosaavn.com" to listOf(
            "184.84.167.8",
            "184.84.167.25",
            "23.199.67.104",
            "23.199.67.99"
        ),
        "aac.saavncdn.com" to listOf(
            "184.84.167.11",
            "184.84.167.9",
            "23.57.75.35",
            "23.57.75.195",
            "23.57.75.34"
        )
    )

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { return it }

        // 1. Try system DNS first
        try {
            val systemAddresses = Dns.SYSTEM.lookup(hostname)
            if (systemAddresses.isNotEmpty()) {
                val ipv4 = systemAddresses.filterIsInstance<Inet4Address>()
                val result = if (ipv4.isNotEmpty()) ipv4 else systemAddresses
                cache[hostname] = result
                return result
            }
        } catch (e: Exception) {
            Timber.tag("JioSaavnDns").w("System DNS failed for $hostname: ${e.message}")
        }

        // 2. Immediate hardcoded fallback for known JioSaavn domains (instant 0ms, 100% reliable)
        hardcodedFallbacks[hostname]?.let { ipList ->
            val fallbackAddresses = ipList.mapNotNull { ip ->
                runCatching { InetAddress.getByName(ip) }.getOrNull()
            }
            if (fallbackAddresses.isNotEmpty()) {
                Timber.tag("JioSaavnDns").i("Resolved $hostname via fallback IPs: $fallbackAddresses")
                cache[hostname] = fallbackAddresses
                return fallbackAddresses
            }
        }

        // 3. Try DNS-over-HTTPS fallback via IP literals
        val dohAddresses = resolveViaDoh(hostname)
        if (dohAddresses.isNotEmpty()) {
            Timber.tag("JioSaavnDns").i("Resolved $hostname via DoH: $dohAddresses")
            cache[hostname] = dohAddresses
            return dohAddresses
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\": All DNS fallbacks failed")
    }

    private fun resolveViaDoh(hostname: String): List<InetAddress> {
        queryDohEndpoint("https://1.1.1.1/dns-query?name=$hostname&type=A", acceptJson = true)?.let { return it }
        queryDohEndpoint("https://8.8.8.8/resolve?name=$hostname&type=A", acceptJson = false)?.let { return it }
        return emptyList()
    }

    private fun queryDohEndpoint(urlString: String, acceptJson: Boolean): List<InetAddress>? {
        return runCatching {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2000
                readTimeout = 2000
                requestMethod = "GET"
                if (acceptJson) {
                    setRequestProperty("Accept", "application/dns-json")
                }
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = jsonConfig.decodeFromString<DohResponse>(body)
                val addresses = parsed.Answer.orEmpty()
                    .filter { it.type == 1 && it.data.isNotBlank() }
                    .mapNotNull {
                        runCatching { InetAddress.getByName(it.data.trim()) }.getOrNull()
                    }
                addresses.ifEmpty { null }
            } else {
                null
            }
        }.getOrNull()
    }
}
