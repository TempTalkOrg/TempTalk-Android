package com.difft.android.network.speedtest

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DomainSpeedTester @Inject constructor(
    @SpeedTest private val client: OkHttpClient
) {

    suspend fun testHosts(hosts: List<String>): List<HostSpeedResult> = coroutineScope {
        hosts.map { host ->
            async { probeHost(host) }
        }.awaitAll()
            .sortedWith(compareByDescending<HostSpeedResult> { it.isAvailable }.thenBy { it.latencyMs })
    }

    private fun probeHost(host: String): HostSpeedResult {
        val url = "https://$host/chat/?t=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).get().build()

        val startTime = System.currentTimeMillis()
        return try {
            client.newCall(request).execute().use { response ->
                val latency = System.currentTimeMillis() - startTime
                L.d { "[SpeedTest] probe $host: ${response.code}, ${latency}ms" }
                HostSpeedResult(host = host, latencyMs = latency, isAvailable = true)
            }
        } catch (e: IOException) {
            val latency = System.currentTimeMillis() - startTime
            L.d { "[SpeedTest] probe $host failed: ${e.message}, ${latency}ms" }
            HostSpeedResult(host = host, latencyMs = Long.MAX_VALUE, isAvailable = false)
        }
    }
}
