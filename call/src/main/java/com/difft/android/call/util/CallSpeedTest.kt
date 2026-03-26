package com.difft.android.call.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.SpeedResponseStatus
import com.difft.android.call.data.UrlSpeedResponse
import com.difft.android.network.config.UserAgentManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object CallSpeedTest {

    private val client = OkHttpClient.Builder()
        .readTimeout(3, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * 测量给定URL的响应时间
     *
     * @param url 要测量的URL地址
     * @return [UrlSpeedResponse] 包含响应状态和响应时间的对象
     */
    fun measureUrlResponseTime(url: String): UrlSpeedResponse {
        var status: SpeedResponseStatus

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgentManager.getUserAgent())
            .header("Range", "bytes=0-0")
            .get()
            .build()

        val startTime = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                response.body?.bytes()
                status = SpeedResponseStatus.SUCCESS
            }
        } catch (e: Throwable) {
            L.e { "[call] CallSpeedTest measureUrlResponseTime, url:$url error: ${e.message}" }
            status = SpeedResponseStatus.ERROR
        }

        val endTime = System.currentTimeMillis()

        return UrlSpeedResponse(status, endTime - startTime)
    }
}
