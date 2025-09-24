package com.difft.android.call.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.data.SpeedResponseStatus
import com.difft.android.call.data.UrlSpeedResponse
import com.difft.android.network.config.UserAgentManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit


object NetUtil {

    /**
     * 测量给定URL的响应时间
     *
     * @param url 要测量的URL地址
     * @return [UrlSpeedResponse] 包含响应状态和响应时间的对象
     */
    fun measureUrlResponseTime(url: String): UrlSpeedResponse {
        var status: SpeedResponseStatus
        val client = OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgentManager.getUserAgent())
            .head()
            .build()

        val startTime = System.currentTimeMillis()
        var response: Response? = null
        try {
            response = client.newCall(request).execute()
            status = SpeedResponseStatus.SUCCESS
        } catch (e: Throwable) {
            L.e { "[call] NetUtil measureUrlResponseTime, url:$url error: ${e.message}" }
            status = SpeedResponseStatus.ERROR
        } finally {
            response?.close()
        }

        val endTime = System.currentTimeMillis()

        return UrlSpeedResponse(status, endTime - startTime)
    }
}