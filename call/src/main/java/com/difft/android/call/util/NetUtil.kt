package com.difft.android.call.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
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
            .readTimeout(3, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UserAgentManager.getUserAgent())
            .header("Range", "bytes=0-0")
            .get()
            .build()

        val startTime = System.currentTimeMillis()
        try {
            client.newCall(request).execute().use { response ->
                // 消费响应体以避免连接池资源泄漏（GET + Range 请求需要读取响应体）
                response.body?.bytes()
                status = SpeedResponseStatus.SUCCESS
            }
        } catch (e: Throwable) {
            L.e { "[call] NetUtil measureUrlResponseTime, url:$url error: ${e.message}" }
            status = SpeedResponseStatus.ERROR
        }

        val endTime = System.currentTimeMillis()

        return UrlSpeedResponse(status, endTime - startTime)
    }



    @Suppress("DEPRECATION")
    fun checkNet(context: Context): Boolean {
        return try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                // 仅依赖 INTERNET 能力，避免在部分机型/系统上因未验证导致误判
                return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
            return connectivityManager.activeNetworkInfo?.isConnected == true
        } catch (e: Exception) {
            false
        }
    }
}