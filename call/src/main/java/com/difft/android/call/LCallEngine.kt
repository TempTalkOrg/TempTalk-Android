package com.difft.android.call

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.call.BuildConfig.*
import com.difft.android.call.data.ServerUrlSpeedInfo
import com.difft.android.call.data.SpeedResponseStatus
import com.difft.android.call.data.UrlSpeedResponse
import com.difft.android.call.util.NetUtil
import com.difft.android.network.config.GlobalConfigsManager
import io.livekit.android.LiveKit
import io.livekit.android.util.LKLog
import io.livekit.android.util.LoggingLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


object LCallEngine {

    private const val SPEED_TEST_INTERVAL = 5 * 60 * 1000L
    private const val ERROR_RESET_THRESHOLD = 15 * 60 * 1000L
    private const val MAX_ERROR_COUNT = 3

    private var speedTestJob: Job? = null

    private var serverUrlsSpeedInfo = mutableListOf<ServerUrlSpeedInfo>()

    private var availableServerUrls = emptyList<String>()

    var configuredDomainCount: Int = 0
        private set

    var isServerUrlSpeedTesting: Boolean = false
        private set

    private lateinit var configsManager: GlobalConfigsManager


    fun init(context: Context, globalConfigsManager: GlobalConfigsManager, scope: CoroutineScope) {
        LiveKit.loggingLevel = if (DEBUG) LoggingLevel.VERBOSE else LoggingLevel.DEBUG
        LiveKit.enableWebRTCLogging = false

        configsManager = globalConfigsManager

        registerNetworkConnectionListener(context, scope)

        LKLog.registerLogCallback(object : LKLog.LogCallback {
            override fun onLog(level: LoggingLevel, message: String?, throwable: Throwable?) {
                val lLevel = when (level ) {
                    LoggingLevel.VERBOSE -> Log.VERBOSE

                    // current log more information, treat debug as info
                    LoggingLevel.DEBUG -> Log.INFO

                    LoggingLevel.INFO -> Log.INFO
                    LoggingLevel.WARN -> Log.WARN
                    LoggingLevel.ERROR -> Log.ERROR
                    else -> 0
                }
//                val lLevel = level.toAndroidLogPriority()

                if (throwable != null && message != null) {
                    L.log(lLevel, throwable) { "[livekit] $message" }
                } else if (throwable != null) {
                    L.log(lLevel, throwable)
                } else if (message != null) {
                    L.log(lLevel) { "[livekit] $message" }
                }
            }
        })

        LiveKit.init(context)

        periodicallyMeasureServerUrlsSpeed(scope)
    }

    private fun periodicallyMeasureServerUrlsSpeed(scope: CoroutineScope) {
        if (isServerUrlSpeedTesting) return
        isServerUrlSpeedTesting = true
        speedTestJob?.cancel()
        speedTestJob = scope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val urlList = LCallManager.fetchCallServiceUrlAndCache().ifEmpty {
                        getServerUrlFromConfigs()
                    }
                    L.d { "[Call] getCallServiceUrl:${urlList}" }
                    configuredDomainCount = urlList.size

                    urlList.forEach { url ->
                        val urlSpeedResponse = NetUtil.measureUrlResponseTime(url)
                        val testTime = System.currentTimeMillis()
                        L.i { "[call] LCallEngine measuring server URLs speed: $url, response time = ${urlSpeedResponse.speed}ms" }
                        updateServerUrlSpeedInfo(url, urlSpeedResponse, testTime)
                    }
                    // 过滤异常集群（连续3次错误）
                    val filterInfos = serverUrlsSpeedInfo.filter { it.errorCount < MAX_ERROR_COUNT && it.status == SpeedResponseStatus.SUCCESS }
                    // 按响应时间排序
                    availableServerUrls = if (filterInfos.isNotEmpty()) filterInfos.sortedBy { it.lastResponseTime }.map { it.url } else emptyList()
                    L.i { "[call] LCallEngine - Measuring available server URLs: $availableServerUrls" }
                } catch (e: Exception) {
                    L.e { "[call] LCallEngine Error measuring server URLs speed: ${e.message}" }
                }

                delay(SPEED_TEST_INTERVAL) // 延迟5分钟执行下一次测试
            }
        }
    }


    private fun updateServerUrlSpeedInfo(url: String, urlSpeedResponse: UrlSpeedResponse, testTime: Long) {
        serverUrlsSpeedInfo.find { it.url == url }?.let { info ->
            info.lastResponseTime = urlSpeedResponse.speed
            info.lastTestTime = testTime
            info.errorCount = if (urlSpeedResponse.status == SpeedResponseStatus.SUCCESS && info.errorCount != 0 && (testTime - info.errorTime > ERROR_RESET_THRESHOLD)) {
                0 // 如果15分钟内未发生错误，重置错误计数器
            } else if (urlSpeedResponse.status == SpeedResponseStatus.ERROR) {
                info.errorCount + 1
            } else {
                info.errorCount
            }
            info.status = urlSpeedResponse.status
            info.errorTime = if (urlSpeedResponse.status != SpeedResponseStatus.ERROR) 0 else testTime
        } ?: run {
            serverUrlsSpeedInfo.add(
                ServerUrlSpeedInfo(
                    url = url,
                    lastResponseTime = urlSpeedResponse.speed,
                    lastTestTime = testTime,
                    errorCount = if (urlSpeedResponse.status != SpeedResponseStatus.ERROR) 0 else 1,
                    status = urlSpeedResponse.status,
                    errorTime = if (urlSpeedResponse.status != SpeedResponseStatus.ERROR) 0 else testTime
                )
            )
        }
    }


    private fun registerNetworkConnectionListener(context: Context, scope: CoroutineScope) {
        val networkConnectionListener = NetworkConnectionListener(context) { isNetworkUnavailable ->
            L.d { "[Call] NetworkConnectionListener isNetworkUnavailable:${isNetworkUnavailable()}" }
            if (!isNetworkUnavailable()) {
                scope.launch(Dispatchers.IO) {
                    LCallManager.fetchCallServiceUrlAndCache()
                }
            }
        }
        networkConnectionListener.register()
    }


    private fun getServerUrlFromConfigs(): List<String> {
        val configs = configsManager.getNewGlobalConfigs()
        val domainListConfig = configs?.data?.domains ?: return emptyList()
        val domainsLabelsConfig = configs.data?.services?.firstOrNull { it.name == "livekit" }?.domains ?: return emptyList()
        val urlList = domainListConfig.filter { it.label in domainsLabelsConfig }
            .map { "https://${it.domain}" }
        return urlList.distinct()
    }


    fun getAvailableServerUrls(): List<String> {
        return availableServerUrls.ifEmpty {
            availableServerUrls = LCallManager.getCallServiceUrl().ifEmpty {
                getServerUrlFromConfigs()
            }
            availableServerUrls
        }
    }

}