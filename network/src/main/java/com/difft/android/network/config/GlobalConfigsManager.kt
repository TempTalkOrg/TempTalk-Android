package com.difft.android.network.config

import android.content.Context
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.SecureConfigStore
import com.difft.android.base.user.ActiveConversation
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.TunnelDomains
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.IGlobalConfigsManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    private val secureConfigStore: SecureConfigStore,
    private val gson: Gson,
) : IGlobalConfigsManager {

    companion object {
        private const val FOREGROUND_REFRESH_INTERVAL_MS = 5 * 60 * 1000L  
        private const val BACKGROUND_REFRESH_INTERVAL_MS = 60 * 60 * 1000L 
        private const val DEFAULT_CONFIG_FILE_NAME = "default_global_config.json"

        





        private val UNINITIALIZED_CONFIG_REF = Any()
    }

    
    @VisibleForTesting
    internal var refreshGate = RefreshGate { SystemClock.elapsedRealtime() }

    @Volatile
    private var currentInterval: Long = FOREGROUND_REFRESH_INTERVAL_MS

    
    private val stateChangeSignal = Channel<Unit>(Channel.CONFLATED)

    private val isEncryptedConfigEnabled = BuildConfig.CONFIG_PSK.isNotEmpty().also { enabled ->
        if (!enabled) L.w { "[GlobalConfigsManager] CONFIG_PSK not set — running in plaintext config mode" }
    }

    @VisibleForTesting
    internal val globalConfigUrls: List<String> by lazy {
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
                inMemoryGlobalConfig = loadInitialConfig()
            } catch (e: Exception) {
                L.e { "[GlobalConfigsManager] Failed to init config cache: ${e.message}" }
            }
            while (isActive) {
                refreshMutex.withLock {
                    doRefreshIfNeeded()
                }
                
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

    



    fun onAppStateChanged(isForeground: Boolean) {
        currentInterval = if (isForeground) FOREGROUND_REFRESH_INTERVAL_MS else BACKGROUND_REFRESH_INTERVAL_MS
        
        stateChangeSignal.trySend(Unit)
    }

    




    @VisibleForTesting
    internal suspend fun doRefreshIfNeeded() {
        val gate = refreshGate
        if (!gate.isDue(currentInterval)) return
        val sinceS = gate.sinceLastMs()?.div(1000)
        L.i { "[GlobalConfigsManager] Refresh triggered sinceLastS=${sinceS ?: "never"}" }
        if (fetchGlobalConfigsWithRetry()) gate.markRefreshed()
    }

    
    @VisibleForTesting
    internal suspend fun fetchGlobalConfigsWithRetry(): Boolean {
        if (globalConfigUrls.isEmpty()) {
            L.e { "[GlobalConfigsManager] No config URLs available" }
            return false
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
                    saveConfigToStore(config)
                    config.data?.emojiReaction?.let { emojis ->
                        updateMostUseEmojis(emojis)
                    }
                    return true
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
        return false
    }

    private fun decryptGlobalConfig(encrypted: EncryptedGlobalConfigResponse): NewGlobalConfig {
        val decryptedJson = GlobalConfigCrypto.decryptGlobalConfig(encrypted)
        L.i { "[GlobalConfigsManager] Decrypted config, keyId=${encrypted.keyId}" }
        val innerData = gson.fromJson(decryptedJson, Data::class.java)
        return NewGlobalConfig(code = encrypted.code, data = innerData)
    }

    






    private suspend fun saveConfigToStore(config: NewGlobalConfig) {
        try {
            val configJson = gson.toJson(config)
            secureConfigStore.saveConfig(configJson)
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] save config to store error: ${e.stackTraceToString()}" }
        }
    }

    override fun getNewGlobalConfigs(): NewGlobalConfig? {
        return inMemoryGlobalConfig ?: loadInitialConfigBlocking().also { inMemoryGlobalConfig = it }
    }

    










    private val embeddedConfig: NewGlobalConfig? by lazy {
        try {
            val json = context.assets.open(DEFAULT_CONFIG_FILE_NAME).bufferedReader().use { it.readText() }
            gson.fromJson(json, NewGlobalConfig::class.java)
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] load embedded assets error: ${e.stackTraceToString()}" }
            null
        }
    }

    




    @Volatile
    private var proxyDomainsConfigRef: Any? = UNINITIALIZED_CONFIG_REF

    @Volatile
    private var cachedProxyChatDomains: List<String> = emptyList()

    @Volatile
    private var cachedProxyCallDomains: List<String> = emptyList()

    private val proxyDomainsLock = Any()

    




    private fun ensureProxyDomainsCache() {
        val cfg = getNewGlobalConfigs()
        if (cfg === proxyDomainsConfigRef) return
        synchronized(proxyDomainsLock) {
            if (cfg === proxyDomainsConfigRef) return
            cachedProxyChatDomains = proxyTunnelDomainsFrom(cfg) { it?.chat }
            cachedProxyCallDomains = proxyTunnelDomainsFrom(cfg) { it?.call }
            proxyDomainsConfigRef = cfg
        }
    }

    
    private fun proxyTunnelDomainsFrom(
        cfg: NewGlobalConfig?,
        select: (TunnelDomains?) -> List<String>?,
    ): List<String> {
        val live = normalizeDomains(select(cfg?.data?.proxy?.tunnelDomains))
        if (live.isNotEmpty()) return live
        return normalizeDomains(select(embeddedConfig?.data?.proxy?.tunnelDomains))
    }

    private fun normalizeDomains(raw: List<String>?): List<String> =
        raw?.asSequence()
            ?.map { it.trim().lowercase().trimEnd('.') }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            ?.toList()
            .orEmpty()

    override fun getProxyTunnelChatDomains(): List<String> {
        ensureProxyDomainsCache()
        return cachedProxyChatDomains
    }

    override fun getProxyTunnelCallDomains(): List<String> {
        ensureProxyDomainsCache()
        return cachedProxyCallDomains
    }

    




    
    @Suppress("BanRunBlockingOutsideTests")
    private fun loadInitialConfigBlocking(): NewGlobalConfig? =
        runBlocking(Dispatchers.IO) { loadInitialConfig() }

    




    private suspend fun loadInitialConfig(): NewGlobalConfig? {
        try {
            val cached = secureConfigStore.configFlow.first()
            if (cached.isNotEmpty()) {
                return gson.fromJson(cached, NewGlobalConfig::class.java).also {
                    L.i { "[GlobalConfigsManager] Loaded config from secure_config.pb" }
                }
            }
        } catch (e: Exception) {
            L.e { "[GlobalConfigsManager] load from secure_config.pb error: ${e.stackTraceToString()}" }
        }

        
        return try {
            val json = context.assets.open(DEFAULT_CONFIG_FILE_NAME).bufferedReader().use { it.readText() }
            gson.fromJson(json, NewGlobalConfig::class.java).also {
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
                    .fetchContactors(body = ContactsRequestBody(listOf(globalServices.myId)))
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

    
    fun getActiveConversationConfig(): ActiveConversation {
        return getNewGlobalConfigs()?.data?.disappearanceTimeInterval?.activeConversation
            ?: ActiveConversation()
    }

    
    fun getGroupConfidentialMemberLimit(): Int {
        return getNewGlobalConfigs()?.data?.group?.confidentialModeThreshold ?: 20
    }

    





    fun isGroupEncryptionEnabled(): Boolean {
        return getNewGlobalConfigs()?.data?.group?.encryptionEnabled ?: false
    }

    



    fun isGroupEncryptionKeyResetEnabled(): Boolean {
        return getNewGlobalConfigs()?.data?.group?.encryptionKeyResetEnabled ?: false
    }
}
