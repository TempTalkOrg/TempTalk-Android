package com.difft.android.network.config

import android.annotation.SuppressLint
import android.content.Context
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.user.NewGlobalConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.RxUtil
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
import kotlinx.coroutines.launch
import javax.inject.Inject

class GlobalConfigsManager @Inject constructor(
    @ChativeHttpClientModule.NoHeader
    private val httpClient2: Lazy<ChativeHttpClient>,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: UserManager,
    @ChativeHttpClientModule.Chat
    private val chatHttpClient: Lazy<ChativeHttpClient>,
) : IGlobalConfigsManager {
    var index = 0

    private val globalConfigUrls: ArrayList<String> by lazy {
        if (environmentHelper.isThatEnvironment(environmentHelper.ENVIRONMENT_DEVELOPMENT)) {
            arrayListOf(
                "https://aly-c-config-1307206075.oss-accelerate.aliyuncs.com/testenv/TChative-MultiGlobalConfigureationFile.json"
            )
        } else {
            arrayListOf(
                "https://d3repcs3hxhwgl.cloudfront.net/Chative-MultiGlobalConfigureationFile.json",
                "https://aly-c-config-1307206075.oss-accelerate.aliyuncs.com/Chative-MultiGlobalConfigureationFile.json",
                "https://chative-config-files.s3.me-central-1.amazonaws.com/Chative-MultiGlobalConfigureationFile.json"
            )
        }
    }

    @SuppressLint("CheckResult")
    override fun getAndSaveGlobalConfigs(context: Context) {
        if (index < globalConfigUrls.size) {
            val url = globalConfigUrls[index]
            httpClient2.get().httpService.getNewGlobalConfigs(url)
                .compose(RxUtil.getSchedulerComposer())
                .subscribe({
                    index++
                    if (it.code == 0) {
                        L.i { "[GlobalConfigsManager] get global configs success:$url" }
                        index = 0
                        inMemoryGlobalConfig = it
                        val configs = Gson().toJson(it)
                        SharedPrefsUtil.putString(SharedPrefsUtil.SP_NEW_CONFIG, configs)
                        it.data?.emojiReaction?.let { emojis ->
                            updateMostUseEmojis(emojis)
                        }
                    } else {
                        L.i { "[GlobalConfigsManager] get global configs fail:$url code:${it.code}" }
                        getAndSaveGlobalConfigs(context)
                    }
                }, {
                    L.e { "[GlobalConfigsManager] get global configs fail:$url code:$it" }
                    index++
                    getAndSaveGlobalConfigs(context)
                    it.printStackTrace()
                })
        }
    }

    private var inMemoryGlobalConfig: NewGlobalConfig? = null

    override fun getNewGlobalConfigs(): NewGlobalConfig? {
        if (inMemoryGlobalConfig != null) return inMemoryGlobalConfig

        try {
            SharedPrefsUtil.getString(SharedPrefsUtil.SP_NEW_CONFIG)?.let {
                val newGlobalConfig = Gson().fromJson(it, NewGlobalConfig::class.java)
                inMemoryGlobalConfig = newGlobalConfig
                return newGlobalConfig
            } ?: run {
                return null
            }
        } catch (e: Exception) {
            L.e { "[global] getNewGlobalConfigs error:${e.message}" }
            return null
        }
    }

    private fun updateMostUseEmojis(emojis: List<String>) {
        val currentMostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")
        if (currentMostUseEmojis.isNullOrEmpty()) {
            userManager.update {
                this.mostUseEmojis = emojis.joinToString(",")
            }
        } else {
            val newMostUseEmojis = (currentMostUseEmojis + emojis).intersect(emojis.toSet()).distinct()
            L.i { "[emoji] updateMostUseEmojis newMostUseEmojis:${newMostUseEmojis.size}" }
            if (newMostUseEmojis.isNotEmpty()) {
                userManager.update {
                    this.mostUseEmojis = newMostUseEmojis.joinToString(",")
                }
            }
        }
    }

    override fun updateMostUseEmoji(emoji: String) {
        val currentMostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")?.toMutableList()
        L.i { "[emoji] updateMostUseEmoji currentMostUseEmojis:${currentMostUseEmojis?.size ?: 0}" }
        if (!currentMostUseEmojis.isNullOrEmpty()) {
            currentMostUseEmojis.remove(emoji)
            currentMostUseEmojis.add(0, emoji)
            L.i { "[emoji] updateMostUseEmoji new MostUseEmojis:${currentMostUseEmojis.size}" }
            userManager.update {
                this.mostUseEmojis = currentMostUseEmojis.joinToString(",")
            }
        }
    }

    private val defaultEmojiReaction = listOf("👍", "😄", "😢", "👌", "🎉", "😂", "❤️", "🤝", "👏", "✅", "🔥", "🙏", "🚀", "❌", "💪", "👀", "👆", "🙌", "🤔", "👋", "🫶", "🙇‍♀️", "💯", "❗", "⛔", "📱", "🤙", "🫠", "🐂", "🐻", "✍️", "🫡")

    private fun getEmojis(): List<String> {
        val emojiReaction = getNewGlobalConfigs()?.data?.emojiReaction
        L.i { "[emoji] getEmojis emojiReaction in GlobalConfigs:${emojiReaction?.size ?: 0}" }
        if (emojiReaction.isNullOrEmpty()) {
            return defaultEmojiReaction
        }
        return emojiReaction
    }

    override fun getMostUseEmojis(): List<String> {
        val mostUseEmojis = userManager.getUserData()?.mostUseEmojis?.split(",")
        L.i { "[emoji] getMostUseEmojis mostUseEmojis:${mostUseEmojis?.size ?: 0}" }
        if (mostUseEmojis.isNullOrEmpty()) {
            return getEmojis()
        }
        return mostUseEmojis
    }

    fun syncMineConfigs() {
        appScope.launch(Dispatchers.IO) {
            try {
                val contact = chatHttpClient.get().httpService
                    .fetchContactors(
                        baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                        body = ContactsRequestBody(listOf(globalServices.myId))
                    ).blockingGet().data?.contacts?.firstOrNull()
                if (contact != null) {
                    userManager.update {
                        saveToPhotos = contact.privateConfigs?.saveToPhotos ?: false
                        globalNotification = contact.privateConfigs?.globalNotification ?: GlobalNotificationType.ALL.value
                    }
                    L.i { "[MeViewModel] syncMineConfig success" }
                } else {
                    L.w { "[MeViewModel] syncMineConfig: contact is null" }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                L.e { "[MeViewModel] syncMineConfig fail:" + e.stackTraceToString() }
            }
        }
    }
}