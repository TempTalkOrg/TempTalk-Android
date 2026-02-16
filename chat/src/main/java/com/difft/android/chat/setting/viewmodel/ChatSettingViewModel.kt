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
import com.difft.android.base.utils.globalServices
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.SAVE_TO_PHOTOS_SET_DEFAULT
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.ConversationSetRequestBody
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
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
    @param:ChativeHttpClientModule.Chat
    private val httpClient: ChativeHttpClient,
    private val dbRoomStore: DBRoomStore
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
        // 监听配置更新通知，直接更新对应字段
        conversationSettingsManager.conversationSettingUpdate
            .filter { it.conversationId == conversation.id }
            .onEach { update ->
                L.i { "[ChatSettings] Received conversationSettingUpdate: $update" }
                handleConversationSettingUpdate(update)
            }
            .launchIn(viewModelScope)

        // 初始化时加载配置
        refreshConversationConfigs()
    }

    /**
     * Handle conversation setting update event, directly update changed fields
     * null means the field has not changed, keep original value
     * For saveToPhotos: SAVE_TO_PHOTOS_SET_DEFAULT (-1) means "set to null (default)"
     */
    private fun handleConversationSettingUpdate(update: ConversationSettingUpdate) {
        // Handle saveToPhotos specially: -1 means "set to null (default)"
        val newSaveToPhotos = when (update.saveToPhotos) {
            null -> null // no change, will use current value
            SAVE_TO_PHOTOS_SET_DEFAULT -> null // set to default (null)
            else -> update.saveToPhotos // 0 or 1
        }
        val hasSaveToPhotosUpdate = update.saveToPhotos != null

        _conversationSet.value?.let { current ->
            _conversationSet.value = current.copy(
                muteStatus = update.muteStatus ?: current.muteStatus,
                blockStatus = update.blockStatus ?: current.blockStatus,
                confidentialMode = update.confidentialMode ?: current.confidentialMode,
                messageExpiry = update.messageExpiry ?: current.messageExpiry,
                messageClearAnchor = update.messageClearAnchor ?: current.messageClearAnchor,
                saveToPhotos = if (hasSaveToPhotosUpdate) newSaveToPhotos else current.saveToPhotos
            )
        } ?: run {
            // If no current config, create a new config with update values
            _conversationSet.value = ConversationSetResponseBody(
                conversation = update.conversationId,
                muteStatus = update.muteStatus ?: 0,
                blockStatus = update.blockStatus ?: 0,
                confidentialMode = update.confidentialMode ?: 0,
                messageExpiry = update.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime(),
                messageClearAnchor = update.messageClearAnchor ?: 0L,
                saveToPhotos = newSaveToPhotos
            )
        }
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
                L.w { "[ChatSettingViewModel] updateSelectedOption error: ${it.stackTraceToString()}" }
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
                        // 合并当前值和返回值，保留 messageExpiry 和 messageClearAnchor
                        // 因为 setConversationConfigs API 不修改这两个字段
                        val current = _conversationSet.value
                        val mergedConfig = if (current != null) {
                            conversationSet.copy(
                                messageExpiry = current.messageExpiry,
                                messageClearAnchor = current.messageClearAnchor
                            )
                        } else {
                            conversationSet
                        }
                        // 更新数据库中的配置
                        viewModelScope.launch(Dispatchers.IO) {
                            updateCachedConfig(conversation, mergedConfig)
                        }
                        updateConversationSetResponseBody(mergedConfig)
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
                L.w { "[ChatSettings] setConversationConfigs error:" + it.stackTraceToString() }
                ToastUtil.show(activity.getString(R.string.operation_failed))
            }
    }

    /**
     * 刷新会话配置，采用缓存优先策略：
     * 1. 先从数据库加载缓存配置，立即更新 UI
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
                            // Preserve local saveToPhotos value (not synced to server)
                            val localSaveToPhotos = _conversationSet.value?.saveToPhotos
                            val finalSet = set.copy(
                                messageExpiry = finalMessageExpiry,
                                saveToPhotos = localSaveToPhotos
                            )
                            // Update database cache (saveToPhotos is not included, uses separate updateSaveToPhotos)
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
     * Load cached conversation config from database
     */
    private suspend fun loadCachedConfig(conversationId: String): ConversationSetResponseBody? {
        return withContext(Dispatchers.IO) {
            try {
                val room = wcdb.room.getFirstObject(DBRoomModel.roomId.eq(conversationId))
                if (room != null) {
                    ConversationSetResponseBody(
                        conversation = conversationId,
                        muteStatus = room.muteStatus,
                        blockStatus = room.blockStatus,
                        confidentialMode = room.confidentialMode,
                        messageExpiry = room.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime(),
                        messageClearAnchor = room.messageClearAnchor ?: 0L,
                        saveToPhotos = room.saveToPhotos
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
     * Update conversation config cache in database
     * Note: saveToPhotos is local-only and uses separate updateSaveToPhotos method
     */
    private fun updateCachedConfig(conversationId: String, config: ConversationSetResponseBody) {
        try {
            dbRoomStore.updateConversationSettings(
                roomId = conversationId,
                muteStatus = config.muteStatus,
                blockStatus = config.blockStatus,
                confidentialMode = config.confidentialMode,
                messageExpiry = config.messageExpiry,
                messageClearAnchor = config.messageClearAnchor
            )
            L.i { "[ChatSettings] Updated cached config for $conversationId" }
        } catch (e: Exception) {
            L.e { "[ChatSettings] updateCachedConfig error: ${e.message}" }
        }
    }

    /**
     * Update save to photos setting for the conversation (local only, no server sync)
     * @param saveToPhotos Save to photos setting (null: follow global, 0: disabled, 1: enabled)
     */
    fun setSaveToPhotos(saveToPhotos: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Update database
                dbRoomStore.updateSaveToPhotos(conversation.id, saveToPhotos)

                // Update in-memory state
                withContext(Dispatchers.Main) {
                    _conversationSet.value?.let { current ->
                        _conversationSet.value = current.copy(saveToPhotos = saveToPhotos)
                    }
                }

                // Notify other listeners (e.g., GroupInfoActivity, SingleChatSettingActivity)
                conversationSettingsManager.emitSaveToPhotosUpdate(conversation.id, saveToPhotos)

                L.i { "[ChatSettings] setSaveToPhotos updated locally: $saveToPhotos" }
            } catch (e: Exception) {
                L.e { "[ChatSettings] setSaveToPhotos error: ${e.message}" }
            }
        }
    }

    override fun onCleared() {
        autoDisposeCompletable.onComplete()

        super.onCleared()
    }
}