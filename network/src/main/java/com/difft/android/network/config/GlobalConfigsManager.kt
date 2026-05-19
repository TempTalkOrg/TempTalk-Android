package com.difft.android.network.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.ActiveConversation
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
import com.difft.android.base.user.Data
import com.difft.android.network.BuildConfig
import com.difft.android.network.responses.EncryptedGlobalConfigResponse
import com.google.gson.Gson
import org.json.JSONObject
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalConfigsManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ChativeHttpClientModule.NoHeader
    private val httpClient2: Lazy<ChativeHttpClient>,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: UserManager,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: Lazy<ChativeHttpClient>,
) : IGlobalConfigsManager {

    companion object {
        private const val FOREGROUND_REFRESH_INTERVAL_MS = 5 * 60 * 1000L  // 5 minutes
        private const val BACKGROUND_REFRESH_INTERVAL_MS = 60 * 60 * 1000L // 1 hour
        private const val DEFAULT_CONFIG_FILE_NAME = "default_global_config.json"
        private const val PREFS_FILE_NAME = "secure_global_config"
        private const val PREFS_KEY_CONFIG = "config"
    }

    @Volatile
    private var lastRefreshTime: Long = 0L

    @Volatile
    private var currentInterval: Long = FOREGROUND_REFRESH_INTERVAL_MS

    // Channel to signal state changes and interrupt the delay
    private val stateChangeSignal = Channel<Unit>(Channel.CONFLATED)

    private val isEncryptedConfigEnabled = BuildConfig.CONFIG_PSK.isNotEmpty().also { enabled ->
        if (!enabled) L.w { "[GlobalConfigsManager] CONFIG_PSK not set — running in plaintext config mode" }
    }

    private val configPrefs: SharedPreferences by lazy {
        val start = System.currentTimeMillis()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            L.i { "[GlobalConfigsManager] EncryptedSharedPreferences init took ${System.currentTimeMillis() - start}ms" }
        }
    }

    private val globalConfigUrls: List<String> by lazy {
        if (isEncryptedConfigEnabled) {
            parseConfigUrls(BuildConfig.CONFIG_URLS)
        } else {
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
    }

    private fun parseConfigUrls(json: String): List<String> {
        val env = if (environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT)) "test" else "prod"
        return try {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray(env)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] Failed to parse config URLs: ${e.message}" }
            emptyList()
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

        currentInterval = if (AppForegroundObserver.isForegrounded()) {
            FOREGROUND_REFRESH_INTERVAL_MS
        } else {
            BACKGROUND_REFRESH_INTERVAL_MS
        }

        periodicRefreshJob = appScope.launch(Dispatchers.IO) {
            L.i { "[GlobalConfigsManager] Starting refresh job" }
            try {
                inMemoryGlobalConfig = initialConfig
            } catch (e: Exception) {
                L.e { "[GlobalConfigsManager] Failed to init config cache: ${e.message}" }
            }
            while (isActive) {
                refreshMutex.withLock {
                    doRefreshIfNeeded()
                }
                // Wait for either: interval timeout OR state change signal
                val signaled = withTimeoutOrNull(currentInterval) {
                    stateChangeSignal.receive()
                    true
                }
                if (signaled == true) {
                    L.i { "[GlobalConfigsManager] State changed, interval now ${currentInterval / 1000}s" }
                }
            }
        }
    }

    /**
     * Call when app foreground/background state changes.
     * Updates the interval and triggers immediate check.
     */
    fun onAppStateChanged(isForeground: Boolean) {
        currentInterval = if (isForeground) FOREGROUND_REFRESH_INTERVAL_MS else BACKGROUND_REFRESH_INTERVAL_MS
        // Signal to interrupt current delay and check immediately
        stateChangeSignal.trySend(Unit)
    }

    private suspend fun doRefreshIfNeeded() {
        val timeSinceLastRefresh = System.currentTimeMillis() - lastRefreshTime

        // First call (lastRefreshTime = 0) or exceeded interval
        if (lastRefreshTime == 0L || timeSinceLastRefresh >= currentInterval) {
            L.i { "[GlobalConfigsManager] Refresh triggered (${timeSinceLastRefresh / 1000}s since last)" }
            fetchGlobalConfigsWithRetry()
            lastRefreshTime = System.currentTimeMillis()
        } else {
            L.d { "[GlobalConfigsManager] Skip refresh, ${(currentInterval - timeSinceLastRefresh) / 1000}s remaining" }
        }
    }

    private suspend fun fetchGlobalConfigsWithRetry() {
        if (globalConfigUrls.isEmpty()) {
            L.e { "[GlobalConfigsManager] No config URLs available" }
            return
        }
        for ((index, url) in globalConfigUrls.withIndex()) {
            try {
                val config = if (isEncryptedConfigEnabled) {
                    val encrypted = httpClient2.get().httpService.getEncryptedGlobalConfig(url)
                    decryptGlobalConfig(encrypted)
                } else {
                    httpClient2.get().httpService.getGlobalConfig(url)
                }

                if (config.code == 0) {
                    L.i { "[GlobalConfigsManager] get global configs success: $url" }
                    inMemoryGlobalConfig = config
                    saveConfigToPrefs(config)
                    removeLegacyConfig()
                    config.data?.emojiReaction?.let { emojis ->
                        updateMostUseEmojis(emojis)
                    }
                    return
                } else {
                    L.i { "[GlobalConfigsManager] get global configs fail: $url code:${config.code}" }
                }
            } catch (e: SecurityException) {
                L.e { "[GlobalConfigsManager] Security verification failed: $url error:${e.message}" }
            } catch (e: Exception) {
                L.e { "[GlobalConfigsManager] get global configs fail: $url error:${e.stackTraceToString()}" }
            }
            if (index == globalConfigUrls.lastIndex) {
                L.e { "[GlobalConfigsManager] All URLs failed, using cached config" }
            }
        }
    }

    private fun decryptGlobalConfig(encrypted: EncryptedGlobalConfigResponse): NewGlobalConfig {
        val decryptedJson = GlobalConfigCrypto.decryptGlobalConfig(encrypted)
        L.i { "[GlobalConfigsManager] Decrypted config, keyId=${encrypted.keyId}" }
        val innerData = Gson().fromJson(decryptedJson, Data::class.java)
        return NewGlobalConfig(code = encrypted.code, data = innerData)
    }

    private fun removeLegacyConfig() {
        try {
            if (SharedPrefsUtil.getString(SharedPrefsUtil.SP_NEW_CONFIG) != null) {
                SharedPrefsUtil.remove(SharedPrefsUtil.SP_NEW_CONFIG)
                L.i { "[GlobalConfigsManager] Removed legacy config from SharedPrefsUtil" }
            }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] Failed to remove legacy config: ${e.message}" }
        }
    }

    private fun saveConfigToPrefs(config: NewGlobalConfig) {
        try {
            val configJson = Gson().toJson(config)
            configPrefs.edit { putString(PREFS_KEY_CONFIG, configJson) }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] save config to prefs error: ${e.stackTraceToString()}" }
        }
    }

    override fun getNewGlobalConfigs(): NewGlobalConfig? {
        return inMemoryGlobalConfig ?: initialConfig.also { inMemoryGlobalConfig = it }
    }

    private val initialConfig: NewGlobalConfig? by lazy {
        try {
            configPrefs.getString(PREFS_KEY_CONFIG, null)?.let { json ->
                return@lazy Gson().fromJson(json, NewGlobalConfig::class.java).also {
                    L.i { "[GlobalConfigsManager] Loaded config from encrypted prefs" }
                }
            }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] load from encrypted prefs error: ${e.stackTraceToString()}" }
        }

        // Fallback to assets
        try {
            val json = context.assets.open(DEFAULT_CONFIG_FILE_NAME).bufferedReader().use { it.readText() }
            Gson().fromJson(json, NewGlobalConfig::class.java).also {
                L.i { "[GlobalConfigsManager] Loaded default config from assets" }
            }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] load from assets error: ${e.stackTraceToString()}" }
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

    override fun getMostUseEmojis(): List<String> {
        val mostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")
        L.i { "[GlobalConfigsManager][emoji] getMostUseEmojis mostUseEmojis: ${mostUseEmojis?.size ?: 0}" }
        if (!mostUseEmojis.isNullOrEmpty()) {
            return mostUseEmojis
        }
        val emojiReaction = getNewGlobalConfigs()?.data?.emojiReaction
        L.i { "[GlobalConfigsManager][emoji] getEmojis emojiReaction in GlobalConfigs: ${emojiReaction?.size ?: 0}" }
        return emojiReaction ?: emptyList()
    }

    fun syncMineConfigs() {
        appScope.launch(Dispatchers.IO) {
            try {
                val contact = chatHttpClient.get().httpService
                    .fetchContactors(
                        baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                        body = ContactsRequestBody(listOf(globalServices.myId))
                    )
                    .data?.contacts?.firstOrNull()

                if (contact != null) {
                    userManager.update {
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

    /**
     * 获取活跃会话过期配置
     * 用于控制空会话的清理时间
     */
    fun getActiveConversationConfig(): ActiveConversation {
        return getNewGlobalConfigs()?.data?.disappearanceTimeInterval?.activeConversation
            ?: ActiveConversation()
    }

    /**
     * 获取群机密消息人数限制
     * 群人数 >= 此值时，隐藏机密消息开关
     */
    fun getGroupConfidentialMemberLimit(): Int {
        return getNewGlobalConfigs()?.data?.group?.confidentialModeThreshold ?: 20
    }

    /**
     * Group encryption feature flag.
     * When disabled: new groups are created as plain (non-encrypted),
     * and the upgrade entry is hidden on the group info page.
     * Existing encrypted groups remain displayed as encrypted regardless of this flag.
     */
    fun isGroupEncryptionEnabled(): Boolean {
        return getNewGlobalConfigs()?.data?.group?.encryptionEnabled ?: false
    }
}