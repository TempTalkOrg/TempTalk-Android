package com.difft.android.network.config

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ContactsRequestBody
import com.google.gson.Gson
import dagger.Lazy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

class GlobalConfigsManager @Inject constructor(
    @ChativeHttpClientModule.NoHeader
    private val httpClient2: Lazy<ChativeHttpClient>,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: UserManager,
    @ChativeHttpClientModule.Chat
    private val chatHttpClient: Lazy<ChativeHttpClient>,
) : IGlobalConfigsManager {

    companion object {
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val globalConfigUrls: List<String> by lazy {
        if (environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT)) {
            listOf(
                "https://aly-c-config-1307206075.oss-accelerate.aliyuncs.com/testenv/TChative-MultiGlobalConfigureationFile.json"
            )
        } else {
            listOf(
                "https://d3repcs3hxhwgl.cloudfront.net/Chative-MultiGlobalConfigureationFile.json",
                "https://aly-c-config-1307206075.oss-accelerate.aliyuncs.com/Chative-MultiGlobalConfigureationFile.json",
                "https://chative-config-files.s3.me-central-1.amazonaws.com/Chative-MultiGlobalConfigureationFile.json"
            )
        }
    }

    @Volatile
    private var inMemoryGlobalConfig: NewGlobalConfig? = null

    private val refreshMutex = Mutex()
    private var periodicRefreshJob: Job? = null

    override fun getAndSaveGlobalConfigs(context: Context) {
        if (periodicRefreshJob?.isActive == true) {
            L.d { "[GlobalConfigsManager] Refresh job already running, skip" }
            return
        }

        periodicRefreshJob = appScope.launch(Dispatchers.IO) {
            // Immediate refresh on first call
            L.i { "[GlobalConfigsManager] Starting global config refresh" }
            refreshMutex.withLock {
                fetchGlobalConfigsWithRetry()
            }

            // Then periodic refresh every 5 minutes
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                L.i { "[GlobalConfigsManager] Periodic refresh triggered" }
                refreshMutex.withLock {
                    fetchGlobalConfigsWithRetry()
                }
            }
        }
    }

    private suspend fun fetchGlobalConfigsWithRetry() {
        for ((index, url) in globalConfigUrls.withIndex()) {
            try {
                val config = httpClient2.get().httpService.getNewGlobalConfigs(url)

                if (config.code == 0) {
                    L.i { "[GlobalConfigsManager] get global configs success: $url" }
                    inMemoryGlobalConfig = config
                    saveConfigToPrefs(config)
                    config.data?.emojiReaction?.let { emojis ->
                        updateMostUseEmojis(emojis)
                    }
                    return // Success, exit retry loop
                } else {
                    L.i { "[GlobalConfigsManager] get global configs fail: $url code:${config.code}" }
                }
            } catch (e: Exception) {
                L.e { "[GlobalConfigsManager] get global configs fail: $url error:${e.stackTraceToString()}" }
                if (index == globalConfigUrls.lastIndex) {
                    L.e { "[GlobalConfigsManager] All URLs failed" }
                }
            }
        }
    }

    private fun saveConfigToPrefs(config: NewGlobalConfig) {
        try {
            val configJson = Gson().toJson(config)
            SharedPrefsUtil.putString(SharedPrefsUtil.SP_NEW_CONFIG, configJson)
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] save config to prefs error: ${e.stackTraceToString()}" }
        }
    }

    override fun getNewGlobalConfigs(): NewGlobalConfig? {
        inMemoryGlobalConfig?.let { return it }

        return try {
            SharedPrefsUtil.getString(SharedPrefsUtil.SP_NEW_CONFIG)?.let { json ->
                Gson().fromJson(json, NewGlobalConfig::class.java).also {
                    inMemoryGlobalConfig = it
                }
            }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] getNewGlobalConfigs error: ${e.stackTraceToString()}" }
            null
        }
    }

    private fun updateMostUseEmojis(emojis: List<String>) {
        val currentMostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")
        if (currentMostUseEmojis.isNullOrEmpty()) {
            userManager.update {
                this.mostUseEmojis = emojis.joinToString(",")
            }
        } else {
            // Keep intersection of current emojis and server emojis
            val newMostUseEmojis = currentMostUseEmojis.filter { it in emojis }
            L.i { "[GlobalConfigsManager][emoji] updateMostUseEmojis newMostUseEmojis: ${newMostUseEmojis.size}" }
            if (newMostUseEmojis.isNotEmpty()) {
                userManager.update {
                    this.mostUseEmojis = newMostUseEmojis.joinToString(",")
                }
            }
        }
    }

    override fun updateMostUseEmoji(emoji: String) {
        val currentMostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")?.toMutableList()
        L.i { "[GlobalConfigsManager][emoji] updateMostUseEmoji currentMostUseEmojis: ${currentMostUseEmojis?.size ?: 0}" }

        if (!currentMostUseEmojis.isNullOrEmpty()) {
            currentMostUseEmojis.remove(emoji)
            currentMostUseEmojis.add(0, emoji)
            L.i { "[GlobalConfigsManager][emoji] updateMostUseEmoji new MostUseEmojis: ${currentMostUseEmojis.size}" }
            userManager.update {
                this.mostUseEmojis = currentMostUseEmojis.joinToString(",")
            }
        }
    }

    private val defaultEmojiReaction = listOf(
        "👍", "😄", "😢", "👌", "🎉", "😂", "❤️", "🤝", "👏", "✅", "🔥", "🙏"
    )

    private fun getEmojis(): List<String> {
        val emojiReaction = getNewGlobalConfigs()?.data?.emojiReaction
        L.i { "[GlobalConfigsManager][emoji] getEmojis emojiReaction in GlobalConfigs: ${emojiReaction?.size ?: 0}" }
        return if (emojiReaction.isNullOrEmpty()) defaultEmojiReaction else emojiReaction
    }

    override fun getMostUseEmojis(): List<String> {
        val mostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")
        L.i { "[GlobalConfigsManager][emoji] getMostUseEmojis mostUseEmojis: ${mostUseEmojis?.size ?: 0}" }
        return if (mostUseEmojis.isNullOrEmpty()) getEmojis() else mostUseEmojis
    }

    fun syncMineConfigs() {
        appScope.launch(Dispatchers.IO) {
            try {
                val contact = chatHttpClient.get().httpService
                    .fetchContactors(
                        baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                        body = ContactsRequestBody(listOf(globalServices.myId))
                    )
                    .blockingGet()
                    .data?.contacts?.firstOrNull()

                if (contact != null) {
                    userManager.update {
                        saveToPhotos = contact.privateConfigs?.saveToPhotos ?: false
                        globalNotification = contact.privateConfigs?.globalNotification ?: GlobalNotificationType.ALL.value
                    }
                    L.i { "[GlobalConfigsManager] syncMineConfig success" }
                } else {
                    L.w { "[GlobalConfigsManager] syncMineConfig: contact is null" }
                }
            } catch (e: Exception) {
                L.e { "[GlobalConfigsManager] syncMineConfig fail: ${e.stackTraceToString()}" }
            }
        }
    }
}