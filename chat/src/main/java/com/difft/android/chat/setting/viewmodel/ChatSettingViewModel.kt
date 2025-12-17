package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import android.text.TextUtils
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import autodispose2.autoDispose
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.tencent.wcdb.base.Value
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
import io.reactivex.rxjava3.subjects.CompletableSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.wcdb

@HiltViewModel(assistedFactory = ChatSettingViewModelFactory::class)
class ChatSettingViewModel @AssistedInject constructor(
    @Assisted
    val conversation: For,
    private val messageArchiveManager: MessageArchiveManager,
    private val conversationSettingsManager: ConversationSettingsManager,
    @ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
) : ViewModel() {
    private val autoDisposeCompletable = CompletableSubject.create()

    private val _conversationSet = MutableStateFlow<ConversationSetResponseBody?>(null)
    val conversationSet: StateFlow<ConversationSetResponseBody?> = _conversationSet.asStateFlow()

    /**
     * 获取当前会话配置的快照值
     * 用于需要同步获取配置的场景（如发送消息时）
     */
    val currentConversationSet: ConversationSetResponseBody?
        get() = _conversationSet.value

    /**
     * 获取消息过期时间（秒）
     * 优先从当前配置获取，如果未加载则使用默认值
     * @return 消息过期时间（秒），Int类型用于消息发送
     */
    fun getMessageExpirySeconds(): Int {
        return currentConversationSet?.messageExpiry?.toInt()
            ?: messageArchiveManager.getDefaultMessageArchiveTime().toInt()
    }

    init {
        // 监听配置更新通知，自动刷新配置
        conversationSettingsManager.conversationSettingUpdate
            .onEach { conversationId ->
                if (conversationId == conversation.id) {
                    L.i { "[ChatSettings] Received conversationSettingUpdate for ${conversation.id}" }
                    refreshConversationConfigs()
                }
            }
            .launchIn(viewModelScope)

        // 监听 archiveTimeUpdate，当 messageExpiry 变化时更新 conversationSet
        MessageArchiveUtil.archiveTimeUpdate
            .filter { it.first == conversation.id }
            .onEach { (_, newMessageExpiry) ->
                _conversationSet.value?.let { currentSet ->
                    if (currentSet.messageExpiry != newMessageExpiry) {
                        L.i { "[ChatSettings] archiveTimeUpdate received, updating messageExpiry: ${currentSet.messageExpiry} -> $newMessageExpiry" }
                        _conversationSet.value = currentSet.copy(messageExpiry = newMessageExpiry)
                    }
                }
            }
            .launchIn(viewModelScope)

        // 初始化时加载配置
        refreshConversationConfigs()
    }

    private fun updateConversationSetResponseBody(conversationSetResponseBody: ConversationSetResponseBody) {
        _conversationSet.value = conversationSetResponseBody
    }

    fun updateSelectedOption(activity: Activity, time: Long) {
        ComposeDialogManager.showWait(activity, "")
        messageArchiveManager.updateMessageArchiveTime(conversation, time)
            .compose(RxUtil.getCompletableTransformer())
            .autoDispose(autoDisposeCompletable)
            .subscribe({
                ComposeDialogManager.dismissWait()
            }, {
                ComposeDialogManager.dismissWait()
                it.printStackTrace()
                it.message?.let { message -> ToastUtil.show(message) }
            })
    }


    fun setConversationConfigs(
        activity: Activity,
        conversation: String,
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        needFinishActivity: Boolean = false,
        successTips: String? = null
    ) {
        httpClient.httpService
            .fetchConversationSet(
                SecureSharedPrefsUtil.getBasicAuth(),
                ConversationSetRequestBody(
                    conversation,
                    remark,
                    muteStatus,
                    blockStatus,
                    confidentialMode
                )
            )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(activity as LifecycleOwner))
            .subscribe({
                if (it.status == 0) {
                    it.data?.let { conversationSet ->
                        // 更新数据库中的配置
                        viewModelScope.launch(Dispatchers.IO) {
                            updateCachedConfig(conversation, conversationSet)
                        }
                        updateConversationSetResponseBody(conversationSet)
                    }
                    if (!TextUtils.isEmpty(successTips)) {
                        successTips?.let { message -> ToastUtil.show(message) }
                        if (needFinishActivity) {
                            viewModelScope.launch {
                                kotlinx.coroutines.delay(2000)
                                activity.finish()
                            }
                        }
                    } else {
                        if (needFinishActivity) {
                            activity.finish()
                        }
                    }
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
                it.printStackTrace()
                L.w { "[ChatSettings] setConversationConfigs error:" + it.stackTraceToString() }
                ToastUtil.show(activity.getString(R.string.operation_failed))
            }
    }

    /**
     * 刷新会话配置，采用缓存优先策略：
     * 1. 先从数据库加载缓存配置，立即更新 UI（包括触发 archiveTimeUpdate）
     * 2. 同时从网络请求最新配置
     * 3. 网络请求成功后更新数据库并再次更新 UI
     */
    private fun refreshConversationConfigs() {
        val conversationId = conversation.id

        viewModelScope.launch {
            // Step 1: 从数据库加载缓存配置
            val cachedConfig = loadCachedConfig(conversationId)
            if (cachedConfig != null) {
                L.i { "[ChatSettings] Loaded cached config: $cachedConfig" }
                updateConversationSetResponseBody(cachedConfig)
            }

            // Step 2: 从网络请求最新配置
            try {
                val response = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchGetConversationSet(
                        SecureSharedPrefsUtil.getBasicAuth(),
                        GetConversationSetRequestBody(listOf(conversationId))
                    ).await()
                }

                if (response.status == 0) {
                    response.data?.let { conversationSets ->
                        val conversationSet = conversationSets.conversations.find { body -> body.conversation == conversationId }
                        conversationSet?.let { set ->
                            // 处理 messageExpiry，与 ConversationSettingsManager 保持一致
                            val finalMessageExpiry = when {
                                set.conversation == globalServices.myId -> 0L
                                set.messageExpiry >= 0 -> set.messageExpiry
                                else -> messageArchiveManager.getDefaultMessageArchiveTime()
                            }
                            val finalSet = set.copy(messageExpiry = finalMessageExpiry)
                            // 更新数据库缓存（包括 messageExpiry）
                            withContext(Dispatchers.IO) {
                                updateCachedConfig(conversationId, finalSet)
                            }
                            L.i { "[ChatSettings] Updated config from server: $finalSet (raw messageExpiry: ${set.messageExpiry})" }
                            // 再次更新 UI
                            updateConversationSetResponseBody(finalSet)
                        }
                    }
                } else {
                    L.w { "[ChatSettings] Server returned error: ${response.reason}" }
                }
            } catch (e: Exception) {
                L.w { "[ChatSettings] refreshConversationConfigs network error: ${e.message}" }
                // 网络错误时不处理，因为已经使用了缓存配置
            }
        }
    }

    /**
     * 从数据库加载缓存的会话配置
     */
    private suspend fun loadCachedConfig(conversationId: String): ConversationSetResponseBody? {
        return withContext(Dispatchers.IO) {
            try {
                val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(conversationId))
                if (room != null) {
                    ConversationSetResponseBody(
                        conversation = conversationId,
                        muteStatus = room.muteStatus,
                        confidentialMode = room.confidentialMode,
                        messageExpiry = room.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime(),
                        messageClearAnchor = room.messageClearAnchor ?: 0L
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                L.e { "[ChatSettings] loadCachedConfig error: ${e.message}" }
                null
            }
        }
    }

    /**
     * 更新数据库中的会话配置缓存
     * 批量更新 muteStatus, confidentialMode, messageExpiry, messageClearAnchor
     * 与 ConversationSettingsManager.updateRoomSetting 保持一致
     */
    private fun updateCachedConfig(conversationId: String, config: ConversationSetResponseBody) {
        try {
            wcdb.room.updateRow(
                arrayOf(
                    Value(config.muteStatus),
                    Value(config.confidentialMode),
                    Value(config.messageExpiry),
                    Value(config.messageClearAnchor)
                ),
                arrayOf(
                    DBRoomModel.muteStatus,
                    DBRoomModel.confidentialMode,
                    DBRoomModel.messageExpiry,
                    DBRoomModel.messageClearAnchor
                ),
                DBRoomModel.roomId.eq(conversationId)
            )
            // 通知会话列表刷新
            RoomChangeTracker.trackRoom(conversationId, RoomChangeType.REFRESH)
            L.i { "[ChatSettings] Updated cached config for $conversationId" }
        } catch (e: Exception) {
            L.e { "[ChatSettings] updateCachedConfig error: ${e.message}" }
        }
    }

    override fun onCleared() {
        autoDisposeCompletable.onComplete()

        super.onCleared()
    }
}