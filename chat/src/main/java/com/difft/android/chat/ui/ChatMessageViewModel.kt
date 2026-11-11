package com.difft.android.chat.ui

import android.app.Activity
import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatPaginationControllerFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.message.ConversationUnreadMarker
import com.difft.android.chat.message.ReadReceiptDispatcher
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.IChatPaginationController
import com.difft.android.chat.R
import com.difft.android.chat.ScrollAction
import com.difft.android.chat.messagesToConvert
import com.difft.android.chat.compose.SelectMessageState
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.deepCopyWithNewAttachmentIdentities
import com.difft.android.chat.attachment.forwardSourceContext
import com.difft.android.chat.attachment.toForwardCopy
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.ConfidentialPlaceholderChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageTwo
import com.difft.android.chat.message.getRelevantAttachment
import com.difft.android.chat.message.isNotifyStyleMessage
import com.difft.android.chat.speech2text.SpeechToTextManager
import com.difft.android.chat.translate.AutoTranslateCoordinator
import com.difft.android.chat.translate.AutoTranslateCoordinatorFactory
import com.difft.android.chat.translate.VisibleReason
import com.difft.android.chat.translate.applyTranslateOverlay
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.BaseResponse
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import com.difft.android.chat.message.NoticeAggregator
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateTargetLanguage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.unreadmessage.UnreadMessageInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.getMessageById
import org.difft.app.database.putMessageIfNotExists
import org.difft.app.database.delete
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.getReadInfoList
import org.difft.app.database.hydration.MessageHydration
import org.difft.app.database.hydration.MessageHydrator
import org.difft.app.database.isKnownContact
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.updateGroupMembersReadPosition
import org.difft.app.database.wcdb
import util.TimeFormatter
import javax.inject.Inject





private data class AssembleDbInputs(
    val members: List<ContactorModel>,
    val groupMemberCount: Int,
    val hydration: MessageHydration,
)


@Suppress("BlockingWcdbInSuspend")
@HiltViewModel(assistedFactory = ChatMessageViewModelFactory::class)
class ChatMessageViewModel @AssistedInject constructor(
    @Assisted
    val forWhat: For,
    @Assisted
    private val jumpMessageTimeStamp: Long?,
    private val dbMessageStore: DBMessageStore,
    private val dbRoomStore: DBRoomStore,
    private val chatPaginationControllerFactory: ChatPaginationControllerFactory,
    private val callManager: dagger.Lazy<LChatToCallController>,
    private val autoTranslateCoordinatorFactory: AutoTranslateCoordinatorFactory,
    private val speechToTextManager: dagger.Lazy<SpeechToTextManager>,
    private val readReceiptDispatcher: ReadReceiptDispatcher,
    private val activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager,
    private val messageHydrator: MessageHydrator,
    private val conversationUnreadMarker: ConversationUnreadMarker,
) : ViewModel(),
    IChatPaginationController by chatPaginationControllerFactory.create(forWhat) {

    init {
        L.i { "[message]=========open ${if (forWhat is For.Group) "group" else "one to one"} chat page===========${forWhat.id}." }
    }

    
    private var shown = false

    




    fun onShown() {
        if (shown) return
        shown = true
        conversationUnreadMarker.onConversationOpened(forWhat)
        viewModelScope.launch { conversationUnreadMarker.clearOnOpen(forWhat) }
    }

    




    fun onReopened() {
        if (!shown) return
        viewModelScope.launch { conversationUnreadMarker.clearOnOpen(forWhat) }
    }

    


    val isE2eeHintEligible: Boolean = when (forWhat) {
        is For.Group -> true
        is For.Account -> forWhat.id != globalServices.myId && !forWhat.id.isOfficialAccount()
    }

    




    val neutralInputHintRes: Int
        get() = if (isE2eeHintEligible) R.string.chat_message_input_hint_e2ee else R.string.chat_message_input_hint

    private val _dbNonFriend = MutableStateFlow(false)
    private val _optimisticNonFriendOverride = MutableStateFlow<Boolean?>(null)

    




    val isNonFriendOneToOne: StateFlow<Boolean> =
        combine(_dbNonFriend, _optimisticNonFriendOverride) { dbValue, override -> override ?: dbValue }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    



    private suspend fun refreshDbNonFriend() {
        try {
            _dbNonFriend.value = !wcdb.isKnownContact(forWhat.id)
        } catch (e: Exception) {
            L.e { "[Chat] isNonFriendOneToOne query failed roomId=${forWhat.id}: ${e.stackTraceToString()}" }
        }
    }

    private fun initE2eeHintObservers() {
        if (forWhat is For.Account && forWhat.id != globalServices.myId && !forWhat.id.isOfficialAccount()) {
            viewModelScope.launch(Dispatchers.IO) { refreshDbNonFriend() }
            ContactorUtil.contactsUpdate
                .filter { it.contains(forWhat.id) }
                .onEach {
                    
                    
                    
                    
                    withContext(Dispatchers.IO) { refreshDbNonFriend() }
                    _optimisticNonFriendOverride.value = null
                }
                .launchIn(viewModelScope)
            ContactorUtil.friendStatusUpdate
                .filter { it.first == forWhat.id }
                .onEach { (_, isFriend) -> _optimisticNonFriendOverride.value = !isFriend }
                .launchIn(viewModelScope)
        }
    }

    val viewModelCreateTime = System.currentTimeMillis()

    


    val contactorCache = com.difft.android.chat.MessageContactsCacheUtil()

    val chatUIData = MutableStateFlow(
        ChatUIData(
            ContactorModel().apply { id = forWhat.id }.takeIf { forWhat is For.Account },
            GroupModel().apply { gid = forWhat.id }.takeIf { forWhat is For.Group }
        )
    )

    private val _chatMessageListUIState = MutableStateFlow<ChatMessageListUIState?>(null)

    val chatMessageListUIState: StateFlow<ChatMessageListUIState?> =
        _chatMessageListUIState.asStateFlow()

    private val _readInfoList = MutableStateFlow<List<ReadInfoModel>>(emptyList())

    
    
    private val _forwardMultiMessage = Channel<ForwardContextData>(Channel.BUFFERED)
    val forwardMultiMessage: Flow<ForwardContextData> = _forwardMultiMessage.receiveAsFlow()
    private val _saveMultiMessageToNote = Channel<ForwardContextData>(Channel.BUFFERED)
    val saveMultiMessageToNote: Flow<ForwardContextData> = _saveMultiMessageToNote.receiveAsFlow()

    





    private suspend fun sendMultiMessageEffect(channel: Channel<ForwardContextData>, data: ForwardContextData, name: String) {
        withContext(Dispatchers.Main.immediate) {
            if (channel.trySend(data).isFailure) {
                L.w { "[ChatMessageViewModel] $name send failed" }
            }
        }
    }

    
    
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal suspend fun sendForwardMultiMessage(data: ForwardContextData) =
        sendMultiMessageEffect(_forwardMultiMessage, data, "forwardMultiMessage")

    val selectMessagesState = MutableStateFlow(SelectMessageState(false, emptySet(), 0))

    
    
    private val _selectionRejected = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val selectionRejected: SharedFlow<String> = _selectionRejected.asSharedFlow()

    
    private var lastMessageListTimestamp: Long = 0

    







    @Volatile
    private var dividerReadPosition: Long? = null

    
    @Volatile
    private var dividerCleared = false

    






    fun clearNewMessageDivider() {
        dividerReadPosition = null
        dividerCleared = true
    }

    private val _inputHeightChanged = MutableSharedFlow<Unit>()
    val inputHeightChanged: SharedFlow<Unit> = _inputHeightChanged.asSharedFlow()
    fun emitInputHeightChanged() {
        viewModelScope.launch {
            _inputHeightChanged.emit(Unit)
        }
    }

    
    
    private val _contactorCacheRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactorCacheRefreshed: SharedFlow<Unit> = _contactorCacheRefreshed.asSharedFlow()

    init {
        observeContactorUpdates()
        initE2eeHintObservers()
    }

    



    private fun observeContactorUpdates() {
        viewModelScope.launch {
            ContactorUtil.contactsUpdate
                .collect { updatedContactIds ->
                    withContext(Dispatchers.IO) {
                        val cachedIds = contactorCache.getCachedIds()

                        val idsToRefresh = updatedContactIds.filter { cachedIds.contains(it) }.toSet()

                        if (idsToRefresh.isNotEmpty()) {
                            contactorCache.remove(idsToRefresh)
                            contactorCache.loadContactors(idsToRefresh, (forWhat as? For.Group)?.id)
                            _contactorCacheRefreshed.emit(Unit)
                            L.d { "[MessageContactsCache] refreshed=${idsToRefresh.size}" }
                        }
                    }
                }
        }
    }

    





    fun refreshContactorInCache(contactor: ContactorModel) {
        if (contactorCache.getCachedIds().contains(contactor.id)) {
            contactorCache.put(contactor)
            viewModelScope.launch {
                _contactorCacheRefreshed.emit(Unit)
            }
        }
    }

    @Inject
    lateinit var userManager: UserManager

    
    private val autoTranslate: AutoTranslateCoordinator =
        autoTranslateCoordinatorFactory.create(forWhat, viewModelScope)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Inject
    fun initialize() {
        bindCoroutineScope(viewModelScope)
        autoTranslate.start()

        
        
        
        val behaviorWithTranslateBudget = chatMessagesStateFlow
            .filterNotNull()
            .mapLatest { behavior ->
                autoTranslate.awaitNewIncoming(behavior)
                behavior
            }

        combine(
            behaviorWithTranslateBudget,
            _readInfoList,
            
            
            isNonFriendOneToOne,
            autoTranslate.overlay,
        ) { chatMessageListBehavior, readInfoList, _, translateOverlay ->
            assembleMessagesUIData(chatMessageListBehavior, readInfoList, translateOverlay)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .onEach {
                _chatMessageListUIState.value = it
            }.launchIn(viewModelScope)

        
        

        viewModelScope.launch(Dispatchers.IO) {
            initLoadMessage(jumpMessageTimeStamp)
            
            _readInfoList.value = wcdb.getReadInfoList(forWhat.id)
            
            checkAndUpdateLargeGroupReadPositions()
        }

        viewModelScope.launch(Dispatchers.IO) {
            readInfoSignals(RoomChangeTracker.readInfoUpdates, forWhat.id).collect {
                _readInfoList.value = wcdb.getReadInfoList(forWhat.id)
            }
        }
    }

    



    private suspend fun checkAndUpdateLargeGroupReadPositions() {
        if (forWhat !is For.Group) return

        try {
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            val memberCount = wcdb.getGroupMemberCount(forWhat.id)

            if (memberCount <= threshold) return

            val maxTimestamp = wcdb.message.getValue(
                DBMessageModel.systemShowTimestamp.max(),
                DBMessageModel.roomId.eq(forWhat.id)
                    .and(DBMessageModel.fromWho.eq(globalServices.myId))
            )?.long ?: 0L

            if (maxTimestamp > 0) {
                L.i { "[Chat][${forWhat.id}] Large group with $memberCount members (threshold: $threshold), updating all members' read positions to $maxTimestamp" }
                wcdb.updateGroupMembersReadPosition(forWhat.id, maxTimestamp)
                RoomChangeTracker.trackRoomReadInfoUpdate(forWhat.id)
            }
        } catch (e: Exception) {
            L.e(e) { "[${forWhat.id}] Error checking and updating large group read positions" }
        }
    }


    




    fun addOneMessage(message: TextMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            _chatMessageListUIState.value?.chatMessages?.lastOrNull()?.systemShowTimestamp?.let {
                if (message.systemShowTimestamp < it) {
                    message.systemShowTimestamp = it + 1
                }
            }
            wcdb.putMessageIfNotExists(message)
            val messageModel = wcdb.getMessageById(message.id) ?: return@launch
            this@ChatMessageViewModel.addOneMessage(messageModel)
        }
    }

    fun setChatUIData(data: ChatUIData) {
        chatUIData.value = data
    }

    val messageQuoted: MutableSharedFlow<ChatMessage> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messageForward: MutableSharedFlow<Pair<ChatMessage, Boolean>> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messageRecall: MutableSharedFlow<ChatMessage> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messageResend: MutableSharedFlow<ChatMessage> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messageEmojiReaction: MutableSharedFlow<EmojiReactionEvent> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val avatarLongClicked: MutableSharedFlow<ContactorModel> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun longClickAvatar(contactorModel: ContactorModel) {
        avatarLongClicked.tryEmit(contactorModel)
    }

    val listClick: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun clickList() {
        listClick.tryEmit(Unit)
    }

    
    private val _voiceMessageSendChannel = Channel<String>(capacity = Channel.BUFFERED)
    val voiceMessageSend: Flow<String> = _voiceMessageSendChannel.receiveAsFlow()

    fun sendVoiceMessage(path: String) {
        val result = _voiceMessageSendChannel.trySend(path)
        if (result.isFailure) {
            L.w { "[ChatMessageViewModel] voiceMessageSend channel send failed" }
        }
    }

    fun startCall(activity: Activity, chatRoomName: String?) {
        if (onGoingCallStateManager.isInCalling()) {
            if (onGoingCallStateManager.getConversationId() == forWhat.id) {
                L.i { "[call] Bringing back current call" }
                LCallManager.bringCallScreenToFront(activity)
            } else {
                ToastUtil.show(R.string.call_is_calling_tip)
            }
        } else {
            val callData = callDataManager.getCallDataByConversationId(forWhat.id)
            if (callData != null) {
                L.i { "[call] Joining existing call with roomId:${callData.roomId}" }
                viewModelScope.launch {
                    val status = LCallManager.joinCall(activity.applicationContext, callData)
                    if (!status) {
                        L.e { "[Call] startCall join call failed." }
                        ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                    }
                }
                return
            }
            L.i { "[call] Starting new call" }
            callManager.get().startCall(activity, forWhat, chatRoomName) { status, message ->
                if (!status) {
                    L.e { "[Call] start call failed." }
                    message?.let { ToastUtil.show(it) }
                }
            }
        }
    }

    val showOrHideFullInput: MutableSharedFlow<Pair<Boolean, String>> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun showOrHideFullInput(show: Boolean, inputContent: String) {
        showOrHideFullInput.tryEmit(show to inputContent)
    }

    
    private val _confidentialViewReceipt = Channel<ChatMessage>(Channel.BUFFERED)
    val confidentialViewReceipt: Flow<ChatMessage> = _confidentialViewReceipt.receiveAsFlow()

    val speechToTextEvent: MutableSharedFlow<Pair<String, SpeechToTextData>> = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun sendConfidentialViewReceipt(message: ChatMessage) {
        L.i { "[Confidential] Send view receipt, messageId: ${message.id}, timestamp: ${message.timeStamp}" }
        if (_confidentialViewReceipt.trySend(message).isFailure) {
            L.w { "[ChatMessageViewModel] confidentialViewReceipt send failed" }
        }
    }

    fun quoteMessage(data: ChatMessage) {
        messageQuoted.tryEmit(data)
    }

    fun forwardMessage(data: ChatMessage, saveToNote: Boolean) {
        messageForward.tryEmit(data to saveToNote)
    }

    fun recallMessage(data: ChatMessage) {
        messageRecall.tryEmit(data)
    }

    fun reSendMessage(data: ChatMessage) {
        L.i { "[ChatResend] resend requested id=${data.id}" }
        messageResend.tryEmit(data)
    }

    fun deleteMessage(messageId: String) {
        dbMessageStore.deleteMessage(listOf(messageId))
    }

    
    private val pendingConfidentialPlaceholders = mutableSetOf<Long>()

    




    fun markConfidentialPlaceholderAsSeen(timestamp: Long) {
        if (!pendingConfidentialPlaceholders.add(timestamp)) return
        viewModelScope.launch {
            delay(AUTO_DELETE_DELAY)
            deleteConfidentialPlaceholder(timestamp)
            pendingConfidentialPlaceholders.remove(timestamp)
        }
    }

    



    suspend fun processPendingConfidentialPlaceholders() {
        if (pendingConfidentialPlaceholders.isEmpty()) return

        val timestamps = pendingConfidentialPlaceholders.toList()
        pendingConfidentialPlaceholders.clear()

        timestamps.forEach { deleteConfidentialPlaceholder(it) }
    }

    private suspend fun deleteConfidentialPlaceholder(timestamp: Long) {
        withContext(Dispatchers.IO) {
            val messageModel = wcdb.message.getFirstObject(DBMessageModel.timeStamp.eq(timestamp))
            if (messageModel != null) {
                messageModel.delete()
                L.i { "[Confidential] Deleted placeholder message -> timestamp:$timestamp" }
            }
        }
    }

    companion object {
        private const val AUTO_DELETE_DELAY = 3000L
        private const val MAX_SELECT_LIMIT = 50
    }

    fun emojiReaction(emojiEvent: EmojiReactionEvent) {
        messageEmojiReaction.tryEmit(emojiEvent)
    }

    private fun updateSpeechToTextStatus(id: String, speechToTextData: SpeechToTextData) {
        speechToTextEvent.tryEmit(id to speechToTextData)
    }

    fun speechToText(context: Context, data: TextChatMessage) {
        viewModelScope.launch {
            if (data.speechToTextData?.convertStatus == SpeechToTextStatus.Converting) {
                L.w { "[speechToTextManager] current voice file is converting." }
                return@launch
            }

            
            
            
            data.getRelevantAttachment()?.let { attachment ->
                if (attachment.key?.isEmpty() != false || attachment.digest?.isEmpty() != false) {
                    L.w { "[speechToTextManager] Attachment key is null or empty for message: ${data.id}" }
                    return@launch
                }
                
                
                
                val attachmentPath = withContext(Dispatchers.IO) {
                    AttachmentPathResolver.materializedFileFor(attachment, data.id)
                }

                val speechToTextData = data.speechToTextData ?: SpeechToTextData(SpeechToTextStatus.Invisible, null)
                speechToTextData.convertStatus = SpeechToTextStatus.Converting
                updateSpeechToTextStatus(data.id, speechToTextData)
                speechToTextManager.get().speechToText(
                    viewModelScope,
                    context,
                    attachment,
                    attachmentPath,
                    onSuccess = {
                        if (it.isNotEmpty()) {
                            speechToTextData.convertStatus = SpeechToTextStatus.Show
                            speechToTextData.speechToTextContent = it
                            updateSpeechToTextStatus(data.id, speechToTextData)
                            updateSpeechToTextDataOfDB(data, speechToTextData)
                        } else {
                            speechToTextData.convertStatus = SpeechToTextStatus.Invisible
                            updateSpeechToTextStatus(data.id, speechToTextData)
                            ToastUtil.show(R.string.chat_speech_to_text_isblank)
                        }
                    },
                    onFailure = {
                        L.e { "[speechToTextManager] SpeechToText failed:" + it.message }
                        speechToTextData.convertStatus = SpeechToTextStatus.Invisible
                        updateSpeechToTextStatus(data.id, speechToTextData)
                        ToastUtil.show(R.string.chat_speech_to_text_fail)
                    }
                )
            }
        }
    }

    fun speechToTextOff(data: TextChatMessage) {
        val speechToTextData = data.speechToTextData ?: return
        speechToTextData.convertStatus = SpeechToTextStatus.Invisible
        updateSpeechToTextStatus(data.id, speechToTextData)
        updateSpeechToTextDataOfDB(data, speechToTextData)
    }

    private fun updateSpeechToTextDataOfDB(data: TextChatMessage, speechToTextData: SpeechToTextData) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbMessageStore.updateMessageSpeechToTextData(forWhat.id, data.id, speechToTextData)
            } catch (e: Exception) {
                L.e { "[ChatMessageViewModel] updateSpeechToTextDataOfDB error: ${e.stackTraceToString()}" }
            }
        }
    }

    
    fun translate(data: TextChatMessage, targetLanguage: TranslateTargetLanguage) {
        autoTranslate.translateManually(data, targetLanguage)
    }

    
    fun translateOff(data: TextChatMessage) {
        autoTranslate.translateOff(data)
    }

    
    fun onTranslateRetry(messageId: String) {
        autoTranslate.retry(messageId)
    }

    
    fun onVisibleMessages(messageIds: List<String>, reason: VisibleReason) {
        autoTranslate.onVisibleRange(messageIds, reason)
    }

    override fun onCleared() {
        if (shown) conversationUnreadMarker.onConversationClosed(forWhat)
        autoTranslate.close()
        super.onCleared()
    }

    private suspend fun assembleMessagesUIData(
        chatMessageListBehavior: ChatMessageListBehavior,
        readInfoList: List<ReadInfoModel>,
        translateOverlay: Map<String, TranslateData>
    ): ChatMessageListUIState {
        
        val toConvert = chatMessageListBehavior.messagesToConvert()
        val senderIds = chatMessageListBehavior.messageList.mapNotNull { it.fromWho }.distinct()

        
        val db = withContext(Dispatchers.IO) {
            AssembleDbInputs(
                members = wcdb.getContactorsFromAllTable(senderIds),
                groupMemberCount = if (forWhat is For.Group) wcdb.getGroupMemberCount(forWhat.id) else 0,
                hydration = messageHydrator.hydrate(toConvert),
            )
        }
        val members = db.members
        val groupMemberCount = db.groupMemberCount
        val isLargeGroup = if (forWhat is For.Group) {
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            groupMemberCount > threshold
        } else {
            false
        }

        
        val anchorChatMessageBefore = chatMessageListBehavior.anchorMessageBefore?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup, groupMemberCount, db.hydration[it.id])
        }
        val anchorChatMessageAfter = chatMessageListBehavior.anchorMessageAfter?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup, groupMemberCount, db.hydration[it.id])
        }

        val chatMessages = chatMessageListBehavior.messageList.mapNotNull { msg ->
            generateMessageTwo(forWhat, msg, members, readInfoList, isLargeGroup, groupMemberCount, db.hydration[msg.id])
        }

        
        
        
        applyTranslateOverlay(chatMessages, translateOverlay)
        autoTranslate.onWindowAssembled(chatMessages)

        
        val allMessagesToCollect = listOfNotNull(anchorChatMessageBefore, anchorChatMessageAfter) + chatMessages
        val allContactIds = com.difft.android.chat.MessageContactsCacheUtil.collectContactIds(allMessagesToCollect)

        
        contactorCache.loadContactors(allContactIds, (forWhat as? For.Group)?.id)
        val list = chatMessages.sortedBy { message -> message.systemShowTimestamp }

        val listWithoutErrorNotify = list.filterNot {
            it is NotifyChatMessage && it.notifyMessage?.showContent.isNullOrEmpty()
        }

        
        
        
        
        val showE2eeHeader = chatMessageListBehavior.hasReachedHistoryStart && isE2eeHintEligible
        val listWithHeader = if (showE2eeHeader) {
            listOf(EncryptionHeaderChatMessage(isNonFriendVariant = isNonFriendOneToOne.value)) + listWithoutErrorNotify
        } else {
            listWithoutErrorNotify
        }

        
        
        if (!dividerCleared && dividerReadPosition == null) {
            dividerReadPosition = chatMessageListBehavior.readPosition
        }
        val readPosition = dividerReadPosition

        var firstUnreadFound = false

        
        
        
        var lastNonNotify: ChatMessage? = anchorChatMessageBefore?.takeIf { !it.isNotifyStyleMessage() }

        val newList = listWithHeader.mapIndexed { index, message ->
            val previousMessage = if (index > 0) {
                listWithHeader[index - 1]
            } else {
                anchorChatMessageBefore
            }
            val isSameDayWithPreviousMessage = TimeFormatter.isSameDay(message.timeStamp, previousMessage?.timeStamp ?: 0L)

            val nextMessage = if (index < listWithHeader.size - 1) {
                listWithHeader[index + 1]
            } else {
                anchorChatMessageAfter
            }
            val isSameDayWithNextMessage = TimeFormatter.isSameDay(message.timeStamp, nextMessage?.timeStamp ?: 0L)

            message.showName = !isSameDayWithPreviousMessage || previousMessage?.isNotifyStyleMessage() == true || message.authorId != previousMessage?.authorId

            
            
            val isNotifyStyle = message.isNotifyStyleMessage()
            if (isNotifyStyle) {
                message.showDayTime = false
            } else {
                message.showDayTime = !TimeFormatter.isSameDay(message.timeStamp, lastNonNotify?.timeStamp ?: 0L)
            }
            
            
            
            if (!isNotifyStyle) lastNonNotify = message

            message.showTime = !isSameDayWithNextMessage || nextMessage?.isNotifyStyleMessage() == true || message.authorId != nextMessage?.authorId

            
            
            if (!firstUnreadFound && readPosition != null && message.systemShowTimestamp > readPosition && !message.isMine) {
                message.showNewMsgDivider = true
                firstUnreadFound = true
            } else {
                message.showNewMsgDivider = false
            }

            message
        }

        
        val rawScrollAction = if (chatMessageListBehavior.updateTimestamp != lastMessageListTimestamp) {
            lastMessageListTimestamp = chatMessageListBehavior.updateTimestamp
            chatMessageListBehavior.scrollAction
        } else {
            null
        }

        
        
        val scrollAction = if (showE2eeHeader && rawScrollAction is ScrollAction.ToPosition) {
            ScrollAction.ToPosition(rawScrollAction.position + 1)
        } else {
            rawScrollAction
        }

        L.i { "[Chat][${forWhat.id}] Finally to be submit to recyclerview adapter with chat message size: ${newList.size}, scrollAction: $scrollAction" }
        return ChatMessageListUIState(
            chatMessages = newList,
            scrollAction = scrollAction,
            
            windowSize = chatMessageListBehavior.messageList.size,
            hasReachedHistoryStart = chatMessageListBehavior.hasReachedHistoryStart,
            hasReachedLatest = chatMessageListBehavior.hasReachedLatest
        )
    }

    suspend fun sendReadRecipient(currentReadPosition: Long, readAt: Long) =
        readReceiptDispatcher.send(forWhat, currentReadPosition, readAt)

    suspend fun updateMessageReadPosition(readPosition: Long, readAt: Long) = withContext(Dispatchers.IO) {
        try {
            
            dbRoomStore.updateMessageReadPosition(forWhat, readPosition)
            dbMessageStore.updateMessageReadTime(forWhat.id, readPosition, readAt)
        } catch (e: Exception) {
            L.e { "updateMessageReadPosition error: ${e.stackTraceToString()}" }
        }
    }


    suspend fun getUnreadMessageInfo(): UnreadMessageInfo? {
        return try {
            dbRoomStore.getUnreadMessageInfo(forWhat)
        } catch (e: Exception) {
            L.e { "[Chat][${forWhat.id}] Error getting unread message info: ${e.stackTraceToString()}" }
            null
        }
    }

    fun updatePlayStatus(data: TextChatMessage, status: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            wcdb.message.updateValue(
                status,
                DBMessageModel.playStatus,
                DBMessageModel.id.eq(data.id)
            )
            RoomChangeTracker.trackRoom(forWhat.id, RoomChangeType.MESSAGE)
        }
    }

    fun selectedMessage(messageId: String, selected: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val state = selectMessagesState.value
            val alreadySelected = messageId in state.selectedMessageIds
            if (selected && !alreadySelected && state.selectedMessageIds.size >= MAX_SELECT_LIMIT) {
                ToastUtil.show(R.string.chat_message_action_select_max_limit)
                _selectionRejected.tryEmit(messageId)
                return@launch
            }
            val newSelectedIds = if (selected) {
                state.selectedMessageIds + messageId
            } else {
                state.selectedMessageIds - messageId
            }
            
            val recallableIds = calculateRecallableMessageIds(newSelectedIds)
            selectMessagesState.value = state.copy(
                selectedMessageIds = newSelectedIds,
                recallableMessageIds = recallableIds
            )
        }
    }

    fun selectModel(enable: Boolean) {
        val totalMessageCount = dbMessageStore.selectableMessageCount(forWhat)
        val selectedIds = if (enable) selectMessagesState.value.selectedMessageIds else emptySet()
        val recallableIds = if (enable) selectMessagesState.value.recallableMessageIds else emptySet()
        selectMessagesState.value = selectMessagesState.value.copy(
            editModel = enable,
            selectedMessageIds = selectedIds,
            totalMessageCount = totalMessageCount,
            recallableMessageIds = recallableIds
        )
    }

    



    private suspend fun calculateRecallableMessageIds(selectedIds: Set<String>): Set<String> {
        if (selectedIds.isEmpty()) return emptySet()

        val myId = globalServices.myId
        val recallTimeoutInterval = (globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.recall?.timeoutInterval ?: (24 * 60 * 60)) * 1000L
        val currentTime = ServerTimeProvider.nowMillis()

        val messages = wcdb.message.getAllObjects(
            DBMessageModel.id.`in`(*selectedIds.toTypedArray())
        )

        return messages.filter { message ->
            message.fromWho == myId && (currentTime - message.systemShowTimestamp <= recallTimeoutInterval)
        }.map { it.id }.toSet()
    }

    
    private val _batchRecallMessages = MutableSharedFlow<Set<String>>()
    val batchRecallMessages: SharedFlow<Set<String>> = _batchRecallMessages.asSharedFlow()

    



    fun onBatchRecallClick() {
        val recallableIds = selectMessagesState.value.recallableMessageIds
        if (recallableIds.isNotEmpty()) {
            viewModelScope.launch {
                _batchRecallMessages.emit(recallableIds)
            }
        }
    }

    







    fun onForwardClick() = viewModelScope.launch(Dispatchers.IO) {
        val loadedMessages =
            wcdb.message.getAllObjects(DBMessageModel.id.`in`(*selectMessagesState.value.selectedMessageIds.toTypedArray()))
                .map { it.convertToTextMessage() }
        if (loadedMessages.isEmpty()) {
            resetSelectMessageState()
            return@launch
        }

        val myId = globalServices.myId
        val mode = NoticeAggregator.computeCombinedForwardModeFromTextMessages(loadedMessages, isSubContext = false)
        
        val sortedAuthorIds = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(loadedMessages, selfIdLast = myId)
        
        
        val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContentFromTextMessages(loadedMessages, myId)

        val (forwardContexts, scene) = if (loadedMessages.size == 1) {
            
            val message = loadedMessages.first()
            
            
            val nestedForward = message.forwardContext
            val singleContext: ForwardContext? = if (!message.sharedContact.isNullOrEmpty()) {
                val sharedContactId = message.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
                val sharedContactName = message.sharedContact?.getOrNull(0)?.name?.displayName
                ForwardContext(null, false, sharedContactId, sharedContactName)
            } else if (nestedForward != null) {
                
                
                
                nestedForward
                    .deepCopyWithNewAttachmentIdentities(message.forwardSourceContext())
                    .apply {
                        forwards?.forEach { forward ->
                            forward.attachments?.takeIf { it.isNotEmpty() }?.let {
                                forward.attachments = listOf(it.first())
                            }
                        }
                    }
            } else {
                ForwardContext(mutableListOf<Forward>().apply {
                    this.add(
                        Forward(
                            message.timeStamp,
                            0,
                            message.forWhat is For.Group,
                            message.fromWho.id,
                            message.text,
                            message.attachments?.map { it.toForwardCopy(message.forwardSourceContext()) },
                            null,
                            message.mentions,
                            message.systemShowTimestamp
                        )
                    )
                }, message.forWhat is For.Group)
            }
            listOfNotNull(singleContext) to ForwardNoticeData.Scene.SINGLE
        } else {
            
            val combinedContext = ForwardContext(loadedMessages.map { message ->
                Forward(
                    message.timeStamp,
                    0,
                    message.forWhat is For.Group,
                    message.fromWho.id,
                    if (!message.sharedContact.isNullOrEmpty()) ResUtils.getString(R.string.chat_message_contact_card_content) else message.text,
                    message.attachments?.map { it.toForwardCopy(message.forwardSourceContext()) },
                    message.forwardContext?.forwards?.map { it.deepCopyWithNewAttachmentIdentities(message.forwardSourceContext()) },
                    message.mentions,
                    message.systemShowTimestamp
                )
            }, forWhat is For.Group)
            listOfNotNull(combinedContext) to ForwardNoticeData.Scene.COMBINED
        }

        val forwardData = ForwardContextData(
            "",
            forwardContexts,
            scene,
            forWhat,
            sourceAuthorIds = sortedAuthorIds,
            messageCount = loadedMessages.size,
            combinedForwardMode = mode,
            carriesForeignContent = carriesForeignContent,
        )
        sendForwardMultiMessage(forwardData)
        resetSelectMessageState()
    }

    private fun resetSelectMessageState() {
        selectMessagesState.value = selectMessagesState.value.copy(
            editModel = false,
            selectedMessageIds = emptySet(),
            recallableMessageIds = emptySet()
        )
    }

    





    fun onCopyClick() = viewModelScope.launch(Dispatchers.IO) {
        val selectedIds = selectMessagesState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return@launch
        val loadedMessages = wcdb.message
            .getAllObjects(DBMessageModel.id.`in`(*selectedIds.toTypedArray()))
            .map { it.convertToTextMessage() }
            .sortedBy { it.systemShowTimestamp }
        if (loadedMessages.isEmpty()) return@launch

        
        val authorIds = loadedMessages.map { it.fromWho.id }.toSet()
        contactorCache.loadContactors(authorIds)

        val formatted = com.difft.android.chat.message.MessageCopyTextFormatter.format(
            messages = loadedMessages,
            nameResolver = { uid ->
                contactorCache.getContactor(uid)?.getDisplayNameWithoutRemarkForUI()
                    ?: uid.formatBase58Id()
            },
            context = com.difft.android.base.utils.application,
            language = java.util.Locale.getDefault().language,
        )
        if (formatted.isBlank()) return@launch

        
        
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            com.difft.android.chat.util.Util.copyToClipboard(
                com.difft.android.base.utils.application,
                formatted,
            )
        }

        
        
        
        val myId = globalServices.myId
        if (NoticeAggregator.copyCarriesForeignContentFromTextMessages(loadedMessages, myId)) {
            
            val mode = NoticeAggregator.computeCombinedForwardModeFromTextMessages(loadedMessages, isSubContext = false)
            val sortedAuthorIds = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(loadedMessages, selfIdLast = myId)
            activityNoticeDispatcher.dispatchCopyNotice(
                sourceConversation = forWhat,
                sourceAuthorIds = sortedAuthorIds,
                myId = myId,
                messageCount = loadedMessages.size,
                combinedForwardMode = mode,
            )
        }

        resetSelectMessageState()
    }

    




    fun sendCopyNotice(
        sourceMessages: List<TextChatMessage>,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        if (sourceMessages.isEmpty()) return
        
        
        
        val myId = globalServices.myId
        if (!NoticeAggregator.copyCarriesForeignContent(sourceMessages, myId)) return
        activityNoticeDispatcher.dispatchCopyNotice(
            sourceConversation = forWhat,
            sourceAuthorIds = sourceMessages.map { it.authorId },
            myId = myId,
            messageCount = sourceMessages.size,
            combinedForwardMode = combinedForwardMode,
        )
    }

    fun onSaveSelectedMessages() = viewModelScope.launch(Dispatchers.IO) {
        val loadedMessages = wcdb.message.getAllObjects(DBMessageModel.id.`in`(*selectMessagesState.value.selectedMessageIds.toTypedArray())).map { it.convertToTextMessage() }
        
        val saveMode = NoticeAggregator.computeCombinedForwardModeFromTextMessages(loadedMessages, isSubContext = false)
        val myId = globalServices.myId
        
        
        val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContentFromTextMessages(loadedMessages, myId)
        val saveData: ForwardContextData
        if (loadedMessages.size == 1 && loadedMessages.firstOrNull()?.sharedContact != null) {
            val sharedContactId = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
            val sharedContactName = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.name?.displayName
            
            saveData =
                ForwardContextData(
                    "",
                    listOf(ForwardContext(emptyList(), false, sharedContactId, sharedContactName)),
                    ForwardNoticeData.Scene.SAVE_TO_NOTES,
                    forWhat,
                    sourceAuthorIds = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(loadedMessages, selfIdLast = myId),
                    messageCount = loadedMessages.size,
                    combinedForwardMode = saveMode,
                    carriesForeignContent = carriesForeignContent,
                )
        } else {
            val forwardContext = ForwardContext(loadedMessages.map {
                val message = it
                Forward(
                    it.timeStamp,
                    0,
                    it.forWhat is For.Group,
                    it.fromWho.id,
                    if (!message.sharedContact.isNullOrEmpty()) ResUtils.getString(R.string.chat_message_contact_card_content) else message.text,
                    message.attachments?.map { attach -> attach.toForwardCopy(message.forwardSourceContext()) },
                    message.forwardContext?.forwards?.map { fwd -> fwd.deepCopyWithNewAttachmentIdentities(message.forwardSourceContext()) },
                    message.mentions,
                    it.systemShowTimestamp
                )
            }, forWhat is For.Group)
            
            saveData =
                ForwardContextData(
                    "",
                    listOfNotNull(forwardContext),
                    ForwardNoticeData.Scene.SAVE_TO_NOTES,
                    forWhat,
                    sourceAuthorIds = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(loadedMessages, selfIdLast = myId),
                    messageCount = loadedMessages.size,
                    combinedForwardMode = saveMode,
                    carriesForeignContent = carriesForeignContent,
                )
        }
        sendMultiMessageEffect(_saveMultiMessageToNote, saveData, "saveMultiMessageToNote")
        resetSelectMessageState()
    }

}

data class EmojiReactionEvent(
    val message: ChatMessage,
    val emoji: String,
    val remove: Boolean,
    val emojiOriginTimeStamp: Long,
    val actionFrom: EmojiReactionFrom
)

enum class EmojiReactionFrom {
    EMOJI_DIALOG,
    CHAT_LIST
}

data class ForwardContextData(
    val content: String,
    val forwardContexts: List<ForwardContext>,
    val scene: ForwardNoticeData.Scene,
    val sourceConversation: For,   
    
    
    
    
    val sourceAuthorIds: List<String>,
    
    
    
    val messageCount: Int,
    
    
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    
    
    
    
    
    val carriesForeignContent: Boolean = true,
)
