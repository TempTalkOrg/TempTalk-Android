package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
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
    private val _conversationSet = MutableStateFlow<ConversationSetResponseBody?>(null)
    val conversationSet: StateFlow<ConversationSetResponseBody?> = _conversationSet.asStateFlow()

    /**
     * Message expiry derived from [conversationSet], falling back to the default archive time
     * when no config is loaded. Exposed as [StateFlow] so Compose can collect via
     * `collectAsState()` without invoking flow operators inside composition
     * (see lint rule FlowOperatorInvokedInComposition).
     */
    val messageExpiry: StateFlow<Long> = conversationSet
        .map { it?.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            messageArchiveManager.getDefaultMessageArchiveTime()
        )

    /**
     * Save-to-photos value derived from [conversationSet]. Exposed as [StateFlow] so Compose
     * can collect via `collectAsState()` without invoking flow operators inside composition.
     */
    val saveToPhotos: StateFlow<Int?> = conversationSet
        .map { it?.saveToPhotos }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
            _conversationSet.value = current
                .applying(
                    muteStatus = update.muteStatus,
                    blockStatus = update.blockStatus,
                    confidentialMode = update.confidentialMode
                )
                .copy(
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

    /**
     * Applies the fields a config change carries; null means "not changed, keep the current value".
     * Covers exactly the fields `setConversationConfigs` can request.
     */
    private fun ConversationSetResponseBody.applying(
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null
    ): ConversationSetResponseBody = copy(
        remark = remark ?: this.remark,
        muteStatus = muteStatus ?: this.muteStatus,
        blockStatus = blockStatus ?: this.blockStatus,
        confidentialMode = confidentialMode ?: this.confidentialMode
    )

    /**
     * Normalises a server-provided expiry: the self conversation has none, a negative value means
     * "use the default archive time". Mirrors `ConversationSettingsManager`.
     */
    private fun normalizeMessageExpiry(conversationId: String, messageExpiry: Long): Long = when {
        conversationId == globalServices.myId -> 0L
        messageExpiry >= 0 -> messageExpiry
        else -> messageArchiveManager.getDefaultMessageArchiveTime()
    }

    fun updateSelectedOption(activity: Activity, time: Long) {
        ComposeDialogManager.showWait(activity, "")
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    messageArchiveManager.updateMessageArchiveTime(conversation, time)
                }
                ComposeDialogManager.dismissWait()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                L.w { "[ChatSettingViewModel] updateSelectedOption error: ${e.stackTraceToString()}" }
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }


    /**
     * Applies conversation config changes. On success [conversationSet] re-emits, which is how a
     * controlled toggle learns it may move; on failure it does not emit at all, so the toggle stays
     * where it was.
     *
     * @return the request's job, so a caller can gate its UI on it (see
     * `DifftToggleView.guardWhile`).
     */
    fun setConversationConfigs(
        activity: Activity,
        conversation: String,
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        needFinishActivity: Boolean = false,
        successTips: String? = null
    ): Job {
        return viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService
                        .fetchConversationSet(
                            (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                            ConversationSetRequestBody(
                                conversation = conversation,
                                remark = remark,
                                muteStatus = muteStatus,
                                blockStatus = blockStatus,
                                confidentialMode = confidentialMode
                            )
                        )
                }
                if (result.status == 0) {
                    val echoed = result.data
                    // Merge against the value the flow holds right now and emit before persisting,
                    // so a config update landing during the DB write is not overwritten by the
                    // stale snapshot this request started from. The write itself only covers the
                    // requested columns, so a concurrent change to any other column is untouched.
                    val mergedConfig = _conversationSet.updateAndGet { latest ->
                        when {
                            // setConversationConfigs never changes the expiry fields, so keep the
                            // local ones; saveToPhotos is local-only and never echoed.
                            echoed != null -> echoed.copy(
                                messageExpiry = latest?.messageExpiry
                                    ?: normalizeMessageExpiry(echoed.conversation, echoed.messageExpiry),
                                messageClearAnchor = latest?.messageClearAnchor ?: echoed.messageClearAnchor,
                                saveToPhotos = latest?.saveToPhotos
                            )
                            // Accepted with nothing echoed back: apply the values this request
                            // asked for, exactly as an update event would. A re-fetch instead would
                            // discard them, report nothing when the GET fails, and could land after
                            // a newer write.
                            latest != null -> latest.applying(remark, muteStatus, blockStatus, confidentialMode)
                            else -> null
                        }
                    }
                    if (mergedConfig == null) {
                        // No echo and no local config to merge into: pull the whole thing, and
                        // await it so this job (and the caller's guard) stays alive until the
                        // config is loaded.
                        refreshConversationConfigs().join()
                        if (_conversationSet.value == null) {
                            ToastUtil.show(activity.getString(R.string.operation_failed))
                        }
                    } else {
                        // Only what was requested, in both branches: the untouched columns keep
                        // their stored values instead of being rewritten from an in-memory config.
                        withContext(Dispatchers.IO) {
                            updateCachedSettings(
                                conversationId = conversation,
                                muteStatus = muteStatus,
                                blockStatus = blockStatus,
                                confidentialMode = confidentialMode
                            )
                        }
                    }
                    if (!TextUtils.isEmpty(successTips)) {
                        successTips?.let { message -> ToastUtil.show(message) }
                        if (needFinishActivity) {
                            kotlinx.coroutines.delay(2000)
                            activity.finish()
                        }
                    } else {
                        if (needFinishActivity) {
                            activity.finish()
                        }
                    }
                } else {
                    ToastUtil.show(result.reason ?: activity.getString(R.string.operation_failed))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatSettings] setConversationConfigs error:" + e.stackTraceToString() }
                ToastUtil.show(activity.getString(R.string.operation_failed))
            }
        }
    }

    /**
     * 刷新会话配置，采用缓存优先策略：
     * 1. 先从数据库加载缓存配置，立即更新 UI
     * 2. 同时从网络请求最新配置
     * 3. 网络请求成功后更新数据库并再次更新 UI
     */
    private fun refreshConversationConfigs(): Job {
        val conversationId = conversation.id

        return viewModelScope.launch {
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
                        (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                        GetConversationSetRequestBody(listOf(conversationId))
                    )
                }

                if (response.status == 0) {
                    response.data?.let { conversationSets ->
                        val conversationSet = conversationSets.conversations.find { body -> body.conversation == conversationId }
                        conversationSet?.let { set ->
                            val finalMessageExpiry = normalizeMessageExpiry(set.conversation, set.messageExpiry)
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
            } catch (e: CancellationException) {
                throw e
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
    private fun updateCachedConfig(conversationId: String, config: ConversationSetResponseBody) =
        updateCachedSettings(
            conversationId = conversationId,
            muteStatus = config.muteStatus,
            blockStatus = config.blockStatus,
            confidentialMode = config.confidentialMode,
            messageExpiry = config.messageExpiry,
            messageClearAnchor = config.messageClearAnchor
        )

    /**
     * Update conversation config cache in database; a null field leaves its column alone.
     * Note: saveToPhotos is local-only and uses separate updateSaveToPhotos method
     */
    private fun updateCachedSettings(
        conversationId: String,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        messageExpiry: Long? = null,
        messageClearAnchor: Long? = null
    ) {
        // The store skips null columns and writes nothing when all are null; mirror that here so
        // the log never claims a write that did not happen.
        if (listOf(muteStatus, blockStatus, confidentialMode, messageExpiry, messageClearAnchor).all { it == null }) return
        try {
            dbRoomStore.updateConversationSettings(
                roomId = conversationId,
                muteStatus = muteStatus,
                blockStatus = blockStatus,
                confidentialMode = confidentialMode,
                messageExpiry = messageExpiry,
                messageClearAnchor = messageClearAnchor
            )
            L.i { "[ChatSettings] cached settings updated conversationId=$conversationId mute=$muteStatus block=$blockStatus confidential=$confidentialMode expiry=$messageExpiry anchor=$messageClearAnchor" }
        } catch (e: Exception) {
            L.e { "[ChatSettings] updateCachedSettings failed conversationId=$conversationId: ${e.stackTraceToString()}" }
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
        super.onCleared()
    }
}