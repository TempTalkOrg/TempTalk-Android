package com.difft.android.chat.ui

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatPaginationControllerFactory
import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.appScope
import org.difft.app.database.convertToContactorModel
import org.difft.app.database.convertToMessageModel
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getReadInfoList
import com.difft.android.base.utils.globalServices
import org.difft.app.database.members
import org.difft.app.database.wcdb
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.LChatToCallController
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.IChatPaginationController
import com.difft.android.chat.R
import com.difft.android.chat.compose.SelectMessageState
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageTwo
import com.difft.android.chat.speech2text.SpeechToTextManager
import com.difft.android.chat.translate.TranslateManager
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ReadPosition
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import difft.android.messageserialization.model.TranslateTargetLanguage
import difft.android.messageserialization.model.isAttachmentMessage
import difft.android.messageserialization.unreadmessage.UnreadMessageInfo
import com.difft.android.network.BaseResponse
import com.google.mlkit.nl.translate.TranslateLanguage
import com.kongzue.dialogx.dialogs.PopTip
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.ReadInfoModel
import util.TimeFormatter
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.create
import javax.inject.Inject


@HiltViewModel(assistedFactory = ChatMessageViewModelFactory::class)
class ChatMessageViewModel @AssistedInject constructor(
    @Assisted
    val forWhat: For,
    @Assisted
    private val jumpMessageTimeStamp: Long?,
    private val dbMessageStore: DBMessageStore,
    private val dbRoomStore: DBRoomStore,
    private val chatPaginationControllerFactory: ChatPaginationControllerFactory,
    private val callManager: LChatToCallController,
    private val translateManager: TranslateManager,
    private val speechToTextManager: SpeechToTextManager,
    private val pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory
) : ViewModel(),
    IChatPaginationController by chatPaginationControllerFactory.create(forWhat) {

    init {
        L.i { "[message]=========open ${if (forWhat is For.Group) "group" else "one to one"} chat page===========${forWhat.id}." }
    }

    val viewModelCreateTime = System.currentTimeMillis()

    private val disposableManager = CompositeDisposable()

    val chatUIData = MutableStateFlow(
        ChatUIData(
            ContactorModel().apply { id = forWhat.id }.takeIf { forWhat is For.Account },
            GroupModel().apply { gid = forWhat.id }.takeIf { forWhat is For.Group }
        )
    )

    private val _chatMessageListUIState = MutableStateFlow(ChatMessageListUIState(emptyList(), -2))

    val chatMessageListUIState: StateFlow<ChatMessageListUIState> =
        _chatMessageListUIState.asStateFlow()

    private val _readInfoList = MutableStateFlow<List<ReadInfoModel>>(emptyList())

    val forwardMultiMessage = MutableStateFlow<ForwardContextData?>(null)
    val saveMultiMessageToNote = MutableStateFlow<ForwardContextData?>(null)

    val selectMessagesState = MutableStateFlow(SelectMessageState(false, emptySet(), 0))

    // 添加输入框高度变化事件 Flow
    private val _inputHeightChanged = MutableSharedFlow<Unit>()
    val inputHeightChanged: SharedFlow<Unit> = _inputHeightChanged.asSharedFlow()
    fun emitInputHeightChanged() {
        viewModelScope.launch {
            _inputHeightChanged.emit(Unit)
        }
    }

    @Inject
    lateinit var userManager: UserManager

    @Inject
    fun initialize() {
        bindCoroutineScope(viewModelScope)
        combine(
            chatMessagesStateFlow,
            selectMessagesState,
            _readInfoList
        ) { chatMessageListBehavior, selectMessageModelData, readInfoList ->
            assembleMessagesUIData(chatMessageListBehavior, selectMessageModelData, readInfoList)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .onEach {
                _chatMessageListUIState.value = it
            }.launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            initLoadMessage(jumpMessageTimeStamp)
            // Initial load of read info
            _readInfoList.value = wcdb.getReadInfoList(forWhat.id)
        }

        // 监听已读状态更新事件
        viewModelScope.launch(Dispatchers.IO) {
            RoomChangeTracker.readInfoUpdates
                .filter { it == forWhat.id }
                .collect {
                    _readInfoList.value = wcdb.getReadInfoList(forWhat.id)
                }
        }
    }

    fun addOneMessage(message: TextMessage) {
        _chatMessageListUIState.value.chatMessages.lastOrNull()?.systemShowTimestamp?.let {
            if (message.systemShowTimestamp < it) {
                message.systemShowTimestamp = it + 1
            }
        }
        val messageModel = wcdb.convertToMessageModel(message)
        this.addOneMessage(messageModel)
    }

    fun setChatUIData(data: ChatUIData) {
        chatUIData.value = data
    }

    var agreeFriendRequest: Subject<BaseResponse<String>> = BehaviorSubject.create()

    var messageQuoted: Subject<ChatMessage> = BehaviorSubject.create()
    var messageForward: Subject<Pair<ChatMessage, Boolean>> = BehaviorSubject.create()
    var messageRecall: Subject<ChatMessage> = BehaviorSubject.create()
    var messageReEdit: Subject<TextChatMessage> = BehaviorSubject.create()
    var messageResend: Subject<ChatMessage> = BehaviorSubject.create()
    var messageEmojiReaction: Subject<EmojiReactionEvent> = BehaviorSubject.create()

    var voiceVisibilityChange: Subject<Boolean> = BehaviorSubject.create()

    fun setVoiceVisibility(visible: Boolean) {
        voiceVisibilityChange.onNext(visible)
    }

    var avatarLongClicked: Subject<ContactorModel> = PublishSubject.create()

    fun longClickAvatar(contactorModel: ContactorModel) {
        avatarLongClicked.onNext(contactorModel)
    }

    var listClick: Subject<Unit> = BehaviorSubject.create()
    fun clickList() {
        listClick.onNext(Unit)
    }

    var voiceMessageSend: Subject<String> = BehaviorSubject.create()

    fun sendVoiceMessage(path: String) {
        voiceMessageSend.onNext(path)
    }

    var chatActionsShow: Subject<Unit> = BehaviorSubject.create()
    fun showChatActions() {
        chatActionsShow.onNext(Unit)
    }

    fun startCall(activity: Activity, chatRoomName: String?) {
        if (LCallActivity.isInCalling()) {
            if (LCallActivity.getConversationId() == forWhat.id) {
                L.i { "[call] Bringing back current call" }
                LCallManager.bringInCallScreenBack(activity)
            } else {
                PopTip.show(R.string.call_is_calling_tip)
            }
        } else {
            //判断当前是否有livekit会议，有则join会议
            val callData = LCallManager.getCallDataByConversationId(forWhat.id)
            if (callData != null) {
                L.i { "[call] Joining existing call with roomId:${callData.roomId}" }
                LCallManager.joinCall(activity.applicationContext, callData.roomId)
                return
            }
            //否则发起livekit call通话
            L.i { "[call] Starting new call" }
            callManager.startCall(activity, forWhat, chatRoomName)
        }
    }

    var showOrHideFullInput: Subject<Pair<Boolean, String>> = PublishSubject.create()

    fun showOrHideFullInput(show: Boolean, inputContent: String) {
        showOrHideFullInput.onNext(show to inputContent)
    }

    var confidentialRecipient: Subject<ChatMessage> = BehaviorSubject.create()

    var showReactionShade: Subject<Boolean> = BehaviorSubject.create()

    var translateEvent: Subject<Pair<String, TranslateData>> = BehaviorSubject.create()

    var speechToTextEvent: Subject<Pair<String, SpeechToTextData>> = BehaviorSubject.create()

    fun showReactionShade(show: Boolean) {
        showReactionShade.onNext(show)
    }

    fun setConfidentialRecipient(message: ChatMessage) {
        confidentialRecipient.onNext(message)
    }

    fun agreeFriendRequest(messageId: String, applyId: Int, token: String) {
        disposableManager.add(
            ContactorUtil.fetchAgreeFriendRequest(com.difft.android.base.utils.application, applyId, token)
                .subscribeOn(Schedulers.io())
                .subscribe({
                    it.data = messageId
                    agreeFriendRequest.onNext(it)
                }) {
                    it.printStackTrace()
                }
        )
    }

    fun quoteMessage(data: ChatMessage) {
        messageQuoted.onNext(data)
    }

    fun forwardMessage(data: ChatMessage, saveToNote: Boolean) {
        messageForward.onNext(data to saveToNote)
    }

    fun recallMessage(data: ChatMessage) {
        messageRecall.onNext(data)
    }

    fun reEditMessage(data: TextChatMessage) {
        messageReEdit.onNext(data)
    }

    fun reSendMessage(data: ChatMessage) {
        messageResend.onNext(data)
    }

    fun deleteMessage(messageId: String) {
        dbMessageStore.deleteMessage(listOf(messageId))
    }

    fun emojiReaction(emojiEvent: EmojiReactionEvent) {
        messageEmojiReaction.onNext(emojiEvent)
    }

    override fun onCleared() {
        disposableManager.dispose()
        super.onCleared()
    }

    private fun updateTranslateStatus(id: String, translateData: TranslateData) {
        translateEvent.onNext(id to translateData)
    }

    private fun updateSpeechToTextStatus(id: String, speechToTextData: SpeechToTextData) {
        speechToTextEvent.onNext(id to speechToTextData)
    }

    fun speechToText(context: Context, data: TextChatMessage) {
        appScope.launch {
            if (data.speechToTextData?.convertStatus == SpeechToTextStatus.Converting) {
                L.w { "[speechToTextManager] current voice file is converting." }
                return@launch
            }

            data.attachment?.let { attachment ->
                attachment.id
                // 检查 key和digest 是否存在
                if (attachment.key == null || attachment.digest == null) {
                    L.w { "[speechToTextManager] Attachment key is null or empty for message: ${data.attachment?.id}" }
                    return@launch
                }

                val speechToTextData = data.speechToTextData ?: SpeechToTextData(SpeechToTextStatus.Invisible, null)
                speechToTextData.convertStatus = SpeechToTextStatus.Converting
                updateSpeechToTextStatus(data.id, speechToTextData)
                withContext(Dispatchers.Main) {
                    speechToTextManager.speechToText(
                        context,
                        attachment,
                        onSuccess = {
                            if (it.isNotEmpty()) {
                                speechToTextData.convertStatus = SpeechToTextStatus.Show
                                speechToTextData.speechToTextContent = it
                                updateSpeechToTextStatus(data.id, speechToTextData)
                                updateSpeechToTextDataOfDB(data, speechToTextData)
                            } else {
                                speechToTextData.convertStatus = SpeechToTextStatus.Invisible
                                updateSpeechToTextStatus(data.id, speechToTextData)
                                PopTip.show(R.string.chat_speech_to_text_isblank)
                            }
                        },
                        onFailure = {
                            L.e { "[speechToTextManager] SpeechToText failed:" + it.message }
                            speechToTextData.convertStatus = SpeechToTextStatus.Invisible
                            updateSpeechToTextStatus(data.id, speechToTextData)
                            PopTip.show(R.string.chat_speech_to_text_fail)
                        }
                    )
                }

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
        dbMessageStore.updateMessageSpeechToTextData(forWhat.id, data.id, speechToTextData).subscribe()
    }

    fun translate(data: TextChatMessage, targetLanguage: TranslateTargetLanguage) {
        val translateData = data.translateData ?: TranslateData(TranslateStatus.Invisible, null, null)
//        if (targetLanguage == TranslateTargetLanguage.EN && !TextUtils.isEmpty(translateData.translatedContentEN)) {
//            translateData.translateStatus = TranslateStatus.ShowEN
//            updateTranslateStatus(data.id, translateData)
//
//            updateTranslateDataOfDB(data, translateData)
//
//            return
//        } else if (targetLanguage == TranslateTargetLanguage.ZH && !TextUtils.isEmpty(translateData.translatedContentCN)) {
//            translateData.translateStatus = TranslateStatus.ShowCN
//            updateTranslateStatus(data.id, translateData)
//
//            updateTranslateDataOfDB(data, translateData)
//
//            return
//        }

        translateData.translateStatus = TranslateStatus.Translating
        updateTranslateStatus(data.id, translateData)

        val content = if (data.forwardContext?.forwards?.size == 1) {
            data.forwardContext?.forwards?.firstOrNull()?.let { forward ->
                forward.card?.content.takeUnless { it.isNullOrEmpty() }
                    ?: forward.text.takeUnless { it.isNullOrEmpty() }
            }
        } else data.card?.content.takeUnless { it.isNullOrEmpty() }
            ?: data.message.takeUnless { it.isNullOrEmpty() }
            ?: ""

        val targetLang = if (targetLanguage == TranslateTargetLanguage.ZH) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH

        translateManager.translateText(
            text = content.toString(),
            targetLang = targetLang,
            onSuccess = {
                L.i { "[translateManager] Translate success" }
                if (targetLanguage == TranslateTargetLanguage.EN) {
                    translateData.translateStatus = TranslateStatus.ShowEN
                    translateData.translatedContentEN = it
                    updateTranslateStatus(data.id, translateData)

                    updateTranslateDataOfDB(data, translateData)

                } else if (targetLanguage == TranslateTargetLanguage.ZH) {
                    translateData.translateStatus = TranslateStatus.ShowCN
                    translateData.translatedContentCN = it
                    updateTranslateStatus(data.id, translateData)

                    updateTranslateDataOfDB(data, translateData)
                }
            },
            onFailure = {
                L.e { "[translateManager] Translate failed:" + it.stackTraceToString() }
                translateData.translateStatus = TranslateStatus.Invisible
                updateTranslateStatus(data.id, translateData)
                PopTip.show(R.string.chat_translate_fail)
            }
        )
    }

    fun translateOff(data: TextChatMessage) {
        val translateData = data.translateData ?: return
        translateData.translateStatus = TranslateStatus.Invisible
        updateTranslateStatus(data.id, translateData)

        updateTranslateDataOfDB(data, translateData)
    }

    private fun updateTranslateDataOfDB(data: TextChatMessage, translateData: TranslateData) {
        dbMessageStore.updateMessageTranslateData(forWhat.id, data.id, translateData).subscribe()
    }

    private fun assembleMessagesUIData(
        chatMessageListBehavior: ChatMessageListBehavior,
        selectMessageState: SelectMessageState,
        readInfoList: List<ReadInfoModel>
    ): ChatMessageListUIState {
        val contactIds = chatMessageListBehavior.messageList.map { it.fromWho }.distinct()
        val members = wcdb.getContactorsFromAllTable(contactIds)
        val chatMessages = chatMessageListBehavior.messageList.mapNotNull { msg ->
            generateMessageTwo(forWhat, msg, members, readInfoList)?.apply {
                editMode = selectMessageState.editModel
                selectedStatus = id in selectMessageState.selectedMessageIds
            }
        }
        val list = chatMessages.sortedBy { message -> message.systemShowTimestamp }

        // 过滤掉错误通知消息
        val listWithoutErrorNotify = list.filterNot {
            it is NotifyChatMessage && it.notifyMessage?.showContent.isNullOrEmpty()
        }

        // 处理消息显示逻辑
        val newList = listWithoutErrorNotify.mapIndexed { index, message ->
            val previousMessage = if (index > 0) listWithoutErrorNotify[index - 1] else null
            val isSameDayWithPreviousMessage = TimeFormatter.isSameDay(message.timeStamp, previousMessage?.timeStamp ?: 0L)

            val nextMessage = if (index < listWithoutErrorNotify.size - 1) listWithoutErrorNotify[index + 1] else null
            val isSameDayWithNextMessage = TimeFormatter.isSameDay(message.timeStamp, nextMessage?.timeStamp ?: 0L)

            message.showName = !isSameDayWithPreviousMessage || previousMessage is NotifyChatMessage || message.nickname != previousMessage?.nickname
            message.showDayTime = !isSameDayWithPreviousMessage
            message.showTime = !isSameDayWithNextMessage || message.nickname != nextMessage?.nickname
            message
        }

        L.i { "[${forWhat.id}] Finally to be submit to recyclerview adapter with chat message size: ${newList.size}" }
        return ChatMessageListUIState(
            newList,
            chatMessageListBehavior.scrollToPosition,
            chatMessageListBehavior.stateTriggeredByUser,
        )
    }

    suspend fun sendReadRecipient(currentReadPosition: Long) = withContext(Dispatchers.IO) {
        try {
            val lastReadPosition = dbRoomStore.getMessageReadPosition(forWhat).blockingGet()
            if (currentReadPosition > lastReadPosition) {
                val messages = wcdb.message.getAllObjects(
                    DBMessageModel.roomId.eq(forWhat.id)
                        .and(DBMessageModel.fromWho.notEq(globalServices.myId))
                        .and(DBMessageModel.mode.eq(0))
                        .and(DBMessageModel.systemShowTimestamp.between(lastReadPosition, currentReadPosition))
                )

                messages.groupBy { it.fromWho }.forEach { (contactId, messages) ->
                    L.i { "[${forWhat.id}] Sending read recipient to $contactId, currentReadPosition:$currentReadPosition, size:${messages.size}" }
                    val maxMessage = messages.maxBy { it.systemShowTimestamp }
                    val readPosition = ReadPosition(
                        forWhat.id.takeIf { forWhat is For.Group },
                        maxMessage.timeStamp,
                        maxMessage.systemShowTimestamp,
                        maxMessage.notifySequenceId,
                        maxMessage.sequenceId
                    )
                    ApplicationDependencies.getJobManager().add(
                        pushReadReceiptSendJobFactory.create(contactId, forWhat, messages.map { it.timeStamp }, readPosition, 0)
                    )
                }
            } else {
                L.i { "[${forWhat.id}] Read recipient already sent at position: $lastReadPosition, no need to resend." }
            }
        } catch (e: Exception) {
            L.e { "[${forWhat.id}] Error sending read recipient: ${e.stackTraceToString()}" }
        }
    }

    suspend fun updateMessageReadPosition(readPosition: Long) = withContext(Dispatchers.IO) {
        try {
            dbRoomStore.updateMessageReadPosition(forWhat, readPosition)
            dbMessageStore.updateMessageReadTime(forWhat.id, readPosition).await()
        } catch (e: Exception) {
            L.e { "updateMessageReadPosition error: ${e.stackTraceToString()}" }
        }
    }

//    suspend fun updateMessageReceiverIds() = withContext(Dispatchers.IO) {
//        if (forWhat !is For.Group) return@withContext
//        val receiverIdList = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.gid.eq(forWhat.id)).map { it.id } - mySelfId
//        wcdb.message.updateValue(
//            globalServices.gson.toJson(receiverIdList),
//            DBMessageModel.receiverIds,
//            DBMessageModel.roomId.eq(forWhat.id)
//                .and(DBMessageModel.fromWho.eq(mySelfId))
//                .and(DBMessageModel.receiverIds.isNull())
//        )
//    }

    fun getUnreadMessageInfo(): UnreadMessageInfo? {
        try {
            return dbRoomStore.getUnreadMessageInfo(forWhat).blockingGet()
        } catch (e: Exception) {
            L.e { "[${forWhat.id}] Error getting unread message info: ${e.stackTraceToString()}" }
            return null
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
        var state = selectMessagesState.value
        val newSelectMessageState = if (selected) {
            state.copy(selectedMessageIds = state.selectedMessageIds + messageId)
        } else {
            state.copy(selectedMessageIds = state.selectedMessageIds - messageId)
        }
        selectMessagesState.value = newSelectMessageState
    }

    fun selectModel(enable: Boolean) {
        val totalMessageCount = dbMessageStore.selectableMessageCount(forWhat)
        selectMessagesState.value = selectMessagesState.value.copy(editModel = enable, totalMessageCount = totalMessageCount)
    }

    fun onForwardClick() = viewModelScope.launch(Dispatchers.IO) {
        val loadedMessages =
            wcdb.message.getAllObjects(DBMessageModel.id.`in`(*selectMessagesState.value.selectedMessageIds.toTypedArray()))
                .map { it.convertToTextMessage() }

        val forwardContexts = loadedMessages.mapNotNull {
            val message = it
            val content: String?
            var forwardContext: ForwardContext? = null
            if (!message.sharedContact.isNullOrEmpty()) {
                content = ""
                val sharedContactId = message.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
                val sharedContactName = message.sharedContact?.getOrNull(0)?.name?.displayName
                forwardContext = ForwardContext(null, false, sharedContactId, sharedContactName)
            } else if (message.forwardContext != null) {
                content = ResUtils.getString(R.string.chat_history)

                forwardContext = message.forwardContext?.apply {
                    this.forwards?.forEach { forward ->
                        if (!forward.attachments.isNullOrEmpty()) {
                            forward.attachments =
                                forward.attachments?.subList(0, 1)
                        }
                    }
                }
            } else {
                content = if (message.isAttachmentMessage()) {
                    ResUtils.getString(R.string.chat_message_attachment)
                } else {
                    it.toString()
                }
                forwardContext = ForwardContext(mutableListOf<Forward>().apply {
                    this.add(
                        Forward(
                            it.timeStamp,
                            0,
                            it.forWhat is For.Group,
                            it.fromWho.id,
                            message.text,
                            message.attachments,
                            null,
                            message.card,
                            message.mentions,
                            it.systemShowTimestamp
                        )
                    )
                }, it.forWhat is For.Group)
            }
            forwardContext
        }
        forwardMultiMessage.value = ForwardContextData("", forwardContexts)
        resetSelectMessageState()
    }

    fun onCombineClick() = viewModelScope.launch(Dispatchers.IO) {
        val loadedMessages = wcdb.message.getAllObjects(DBMessageModel.id.`in`(*selectMessagesState.value.selectedMessageIds.toTypedArray())).map { it.convertToTextMessage() }
        val forwardContext = ForwardContext(loadedMessages.map {
            val message = it
            Forward(
                it.timeStamp,
                0,
                it.forWhat is For.Group,
                it.fromWho.id,
                if (!message.sharedContact.isNullOrEmpty()) ResUtils.getString(R.string.chat_message_contact_card_content) else message.text,
                message.attachments,
                message.forwardContext?.forwards,
                message.card,
                message.mentions,
                it.systemShowTimestamp
            )
        }, forWhat is For.Group)
        forwardMultiMessage.value =
            ForwardContextData("", listOfNotNull(forwardContext))
        resetSelectMessageState()
    }

    private fun resetSelectMessageState() {
        selectMessagesState.value = selectMessagesState.value.copy(editModel = false, selectedMessageIds = emptySet())
    }

    fun onSaveSelectedMessages() = viewModelScope.launch(Dispatchers.IO) {
        val loadedMessages = wcdb.message.getAllObjects(DBMessageModel.id.`in`(*selectMessagesState.value.selectedMessageIds.toTypedArray())).map { it.convertToTextMessage() }
        if (loadedMessages.size == 1 && loadedMessages.firstOrNull()?.sharedContact != null) {
            val sharedContactId = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
            val sharedContactName = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.name?.displayName
            saveMultiMessageToNote.value =
                ForwardContextData("", listOf(ForwardContext(emptyList(), false, sharedContactId, sharedContactName)))
        } else {
            val forwardContext = ForwardContext(loadedMessages.map {
                val message = it
                Forward(
                    it.timeStamp,
                    0,
                    it.forWhat is For.Group,
                    it.fromWho.id,
                    if (!message.sharedContact.isNullOrEmpty()) ResUtils.getString(R.string.chat_message_contact_card_content) else message.text,
                    message.attachments,
                    message.forwardContext?.forwards,
                    message.card,
                    message.mentions,
                    it.systemShowTimestamp
                )
            }, forWhat is For.Group)
            saveMultiMessageToNote.value =
                ForwardContextData("", listOfNotNull(forwardContext))
        }
        resetSelectMessageState()
    }

    fun getMeContactor(): ContactorModel {
        return chatUIData.value.group?.members?.find { it.id == globalServices.myId }?.convertToContactorModel() ?: ContactorModel().apply { id = globalServices.myId }
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
    val forwardContexts: List<ForwardContext>
)
