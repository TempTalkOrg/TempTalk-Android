package com.difft.android.chat.setting.viewmodel

import android.app.Activity
import android.text.TextUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.SAVE_TO_PHOTOS_SET_DEFAULT
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.sync.ConfidentialModeSyncCoordinator
import com.difft.android.chat.setting.sync.ConversationSettingsApplier
import com.difft.android.chat.setting.sync.ServerConfigSnapshot
import com.difft.android.chat.setting.sync.SnapshotSource
import com.difft.android.chat.translate.backend.TranslateBackend
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    private val dbRoomStore: DBRoomStore,
    private val settingsApplier: ConversationSettingsApplier,
    private val confidentialSync: ConfidentialModeSyncCoordinator,
    translateBackend: TranslateBackend,
) : ViewModel() {

    



    val translateSupported: Boolean = translateBackend.isSupported

    private val _conversationSet = MutableStateFlow<ConversationSetResponseBody?>(null)
    val conversationSet: StateFlow<ConversationSetResponseBody?> = _conversationSet.asStateFlow()

    





    val messageExpiry: StateFlow<Long> = conversationSet
        .map { it?.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime() }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            messageArchiveManager.getDefaultMessageArchiveTime()
        )

    



    val saveToPhotos: StateFlow<Int?> = conversationSet
        .map { it?.saveToPhotos }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    



    val translateSetting: StateFlow<Int> = conversationSet
        .map { it?.translateSetting ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    
    val currentConversationSet: ConversationSetResponseBody?
        get() = _conversationSet.value

    



    fun getMessageExpirySeconds(): Int {
        return currentConversationSet?.messageExpiry?.toInt()
            ?: messageArchiveManager.getDefaultMessageArchiveTime().toInt()
    }

    init {
        conversationSettingsManager.conversationSettingUpdate
            .filter { it.conversationId == conversation.id }
            .onEach { update ->
                L.i { "[ChatSettings] Received conversationSettingUpdate: $update" }
                handleConversationSettingUpdate(update)
            }
            .launchIn(viewModelScope)

        refreshConversationConfigs()
    }

    




    private fun handleConversationSettingUpdate(update: ConversationSettingUpdate) {
        
        val newSaveToPhotos = when (update.saveToPhotos) {
            null -> null 
            SAVE_TO_PHOTOS_SET_DEFAULT -> null 
            else -> update.saveToPhotos 
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
                    saveToPhotos = if (hasSaveToPhotosUpdate) newSaveToPhotos else current.saveToPhotos,
                    translateSetting = update.translateSetting ?: current.translateSetting,
                    allowMentionMe = update.allowMentionMe ?: current.allowMentionMe,
                    allowMentionAll = update.allowMentionAll ?: current.allowMentionAll,
                    allowGroupCall = update.allowGroupCall ?: current.allowGroupCall
                )
        } ?: run {
            
            _conversationSet.value = ConversationSetResponseBody(
                conversation = update.conversationId,
                muteStatus = update.muteStatus ?: 0,
                blockStatus = update.blockStatus ?: 0,
                confidentialMode = update.confidentialMode ?: 0,
                messageExpiry = update.messageExpiry ?: messageArchiveManager.getDefaultMessageArchiveTime(),
                messageClearAnchor = update.messageClearAnchor ?: 0L,
                saveToPhotos = newSaveToPhotos,
                translateSetting = update.translateSetting ?: 0,
                allowMentionMe = update.allowMentionMe,
                allowMentionAll = update.allowMentionAll,
                allowGroupCall = update.allowGroupCall
            )
        }
    }

    private fun updateConversationSetResponseBody(conversationSetResponseBody: ConversationSetResponseBody) {
        _conversationSet.value = conversationSetResponseBody
    }

    



    private fun ConversationSetResponseBody.applying(
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        confidentialMode: Int? = null,
        allowMentionMe: Boolean? = null,
        allowMentionAll: Boolean? = null,
        allowGroupCall: Boolean? = null
    ): ConversationSetResponseBody = copy(
        remark = remark ?: this.remark,
        muteStatus = muteStatus ?: this.muteStatus,
        blockStatus = blockStatus ?: this.blockStatus,
        confidentialMode = confidentialMode ?: this.confidentialMode,
        allowMentionMe = allowMentionMe ?: this.allowMentionMe,
        allowMentionAll = allowMentionAll ?: this.allowMentionAll,
        allowGroupCall = allowGroupCall ?: this.allowGroupCall
    )

    private val _archiveBusy = MutableStateFlow(false)

    
    val archiveBusy: StateFlow<Boolean> = _archiveBusy.asStateFlow()

    private val _archiveErrors = Channel<String>(Channel.BUFFERED)

    
    val archiveErrors: Flow<String> = _archiveErrors.receiveAsFlow()

    




    fun updateArchiveTime(time: Long) {
        if (!_archiveBusy.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    messageArchiveManager.updateMessageArchiveTime(conversation, time)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatSettingViewModel] updateArchiveTime error: ${e.stackTraceToString()}" }
                e.message?.let { _archiveErrors.trySend(it) }
            } finally {
                _archiveBusy.value = false
            }
        }
    }


    







    fun setConversationConfigs(
        activity: Activity,
        conversation: String,
        remark: String? = null,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        needFinishActivity: Boolean = false,
        successTips: String? = null,
        allowMentionMe: Boolean? = null,
        allowMentionAll: Boolean? = null,
        allowGroupCall: Boolean? = null
    ): Job {
        return viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    httpClient.httpService
                        .fetchConversationSet(
                            ConversationSetRequestBody(
                                conversation = conversation,
                                remark = remark,
                                muteStatus = muteStatus,
                                blockStatus = blockStatus,
                                allowMentionMe = allowMentionMe,
                                allowMentionAll = allowMentionAll,
                                allowGroupCall = allowGroupCall
                            )
                        )
                }
                if (result.status == 0) {
                    val echoed = result.data
                    if (echoed != null) {
                        
                        
                        
                        
                        val applied = withContext(Dispatchers.IO) {
                            dbRoomStore.createRoomIfNotExist(this@ChatSettingViewModel.conversation)
                            settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromEcho(echoed), SnapshotSource.Echo)
                        }
                        reflectRow(applied, fallback = null)
                    } else {
                        
                        
                        
                        val mergedConfig = _conversationSet.updateAndGet { latest ->
                            latest?.applying(remark, muteStatus, blockStatus, allowMentionMe = allowMentionMe, allowMentionAll = allowMentionAll, allowGroupCall = allowGroupCall)
                        }
                        if (mergedConfig == null) {
                            
                            
                            refreshConversationConfigs().join()
                            if (_conversationSet.value == null) {
                                ToastUtil.show(activity.getString(R.string.operation_failed))
                            }
                        } else {
                            
                            withContext(Dispatchers.IO) {
                                updateCachedSettings(
                                    conversationId = conversation,
                                    muteStatus = muteStatus,
                                    blockStatus = blockStatus,
                                    allowMentionMe = allowMentionMe,
                                    allowMentionAll = allowMentionAll,
                                    allowGroupCall = allowGroupCall
                                )
                            }
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

    
    private fun refreshConversationConfigs(): Job {
        val conversationId = conversation.id

        return viewModelScope.launch {
            val cachedConfig = loadCachedConfig(conversationId)
            if (cachedConfig != null) {
                L.i { "[ChatSettings] cached config ${cachedConfig.toLogString()}" }
                updateConversationSetResponseBody(cachedConfig)
            }

            try {
                val response = withContext(Dispatchers.IO) {
                    httpClient.httpService.fetchGetConversationSet(
                        GetConversationSetRequestBody(listOf(conversationId))
                    )
                }

                if (response.status == 0) {
                    response.data?.let { conversationSets ->
                        val conversationSet = conversationSets.conversations.find { body -> body.conversation == conversationId }
                        conversationSet?.let { set ->
                            
                            
                            
                            val applied = withContext(Dispatchers.IO) {
                                dbRoomStore.createRoomIfNotExist(conversation)
                                settingsApplier.applyServerSnapshot(ServerConfigSnapshot.fromPull(set), SnapshotSource.Pull)
                            }
                            L.i { "[ChatSettings] server config ${set.toLogString()} rawExpiry=${set.messageExpiry}" }
                            reflectRow(
                                applied,
                                fallback = set.copy(messageExpiry = normalizeMessageExpiry(set.conversation, set.messageExpiry))
                            )
                        }
                    }
                } else {
                    L.w { "[ChatSettings] Server returned error: ${response.reason}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatSettings] refreshConversationConfigs network error: ${e.message}" }
                
            }
        }
    }

    


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
                        saveToPhotos = room.saveToPhotos,
                        translateSetting = room.translateSetting,
                        allowMentionMe = room.allowMentionMe,
                        allowMentionAll = room.allowMentionAll,
                        allowGroupCall = room.allowGroupCall
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

    






    private suspend fun reflectRow(applied: ConversationSettingUpdate?, fallback: ConversationSetResponseBody?) {
        if (_conversationSet.value != null) {
            applied?.let { handleConversationSettingUpdate(it) }
            return
        }
        val fromRow = loadCachedConfig(conversation.id)
        when {
            fromRow != null -> _conversationSet.update { current -> current ?: fromRow }
            applied != null -> handleConversationSettingUpdate(applied)
            fallback != null -> _conversationSet.update { current -> current ?: fallback }
        }
    }

    



    private fun normalizeMessageExpiry(conversationId: String, messageExpiry: Long): Long = when {
        conversationId == globalServices.myId -> 0L
        messageExpiry >= 0 -> messageExpiry
        else -> messageArchiveManager.getDefaultMessageArchiveTime()
    }

    



    fun setConfidentialMode(target: Int) {
        viewModelScope.launch {
            val written = confidentialSync.setConfidentialMode(conversation, target)
            if (written) {
                reflectRow(ConversationSettingUpdate(conversationId = conversation.id, confidentialMode = target), fallback = null)
            } else {
                L.w { "[ChatSettings] confidential toggle local write failed conversationId=${conversation.id}" }
            }
        }
    }

    



    private fun updateCachedSettings(
        conversationId: String,
        muteStatus: Int? = null,
        blockStatus: Int? = null,
        allowMentionMe: Boolean? = null,
        allowMentionAll: Boolean? = null,
        allowGroupCall: Boolean? = null
    ) {
        
        
        if (listOf<Any?>(muteStatus, blockStatus, allowMentionMe, allowMentionAll, allowGroupCall).all { it == null }) return
        try {
            dbRoomStore.createRoomIfNotExist(conversation)
            dbRoomStore.updateConversationSettings(
                roomId = conversationId,
                muteStatus = muteStatus,
                blockStatus = blockStatus,
                allowMentionMe = allowMentionMe,
                allowMentionAll = allowMentionAll,
                allowGroupCall = allowGroupCall
            )
            L.i {
                "[ChatSettings] cached settings updated conversationId=$conversationId mute=$muteStatus block=$blockStatus " +
                    "allowMe=$allowMentionMe allowAll=$allowMentionAll allowCall=$allowGroupCall"
            }
        } catch (e: Exception) {
            L.e { "[ChatSettings] updateCachedSettings failed conversationId=$conversationId: ${e.stackTraceToString()}" }
        }
    }

    



    fun setSaveToPhotos(saveToPhotos: Int?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                
                dbRoomStore.updateSaveToPhotos(conversation.id, saveToPhotos)

                
                withContext(Dispatchers.Main) {
                    _conversationSet.value?.let { current ->
                        _conversationSet.value = current.copy(saveToPhotos = saveToPhotos)
                    }
                }

                
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