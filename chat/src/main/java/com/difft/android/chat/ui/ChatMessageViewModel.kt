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
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ToastUtil
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
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.BaseResponse
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
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
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.subjects.BehaviorSubject
import io.reactivex.rxjava3.subjects.PublishSubject
import io.reactivex.rxjava3.subjects.Subject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.convertToMessageModel
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.getReadInfoList
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.updateGroupMembersReadPosition
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.create
import util.TimeFormatter
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

    /**
     * 页面级联系人缓存
     *
     * 跟随ViewModel生命周期，页面销毁时自动释放
     */
    val contactorCache = com.difft.android.chat.MessageContactsCacheUtil()

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

    // 联系人缓存刷新事件（用于触发adapter.notifyDataSetChanged）
    // extraBufferCapacity=1: 缓冲一个事件，避免因为Fragment生命周期而丢失事件
    private val _contactorCacheRefreshed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactorCacheRefreshed: SharedFlow<Unit> = _contactorCacheRefreshed.asSharedFlow()

    init {
        // 监听联系人更新事件
        observeContactorUpdates()
    }

    /**
     * 监听联系人更新事件
     *
     * 当联系人信息更新时，检查是否在当前缓存中：
     * - 如果有交集，重新加载这些联系人并发送刷新事件
     * - 这样群聊中其他成员、转发消息作者、引用消息作者等的名字也能及时更新
     */
    private fun observeContactorUpdates() {
        viewModelScope.launch {
            ContactorUtil.contactsUpdate
                .asFlow()
                .flowOn(Dispatchers.IO)
                .collect { updatedContactIds ->
                    // 获取缓存中的所有联系人ID
                    val cachedIds = contactorCache.getCachedIds()

                    // 找出需要更新的联系人（在缓存中的）
                    val idsToRefresh = updatedContactIds.filter { cachedIds.contains(it) }.toSet()

                    if (idsToRefresh.isNotEmpty()) {
                        L.d { "MessageContactsCacheUtil: Refreshing ${idsToRefresh.size} cached contactors: $idsToRefresh" }

                        // 1. 先清除缓存中的旧数据
                        contactorCache.remove(idsToRefresh)
                        L.d { "MessageContactsCacheUtil: Removed old cache entries" }

                        // 2. 重新加载这些联系人
                        contactorCache.loadContactors(idsToRefresh)
                        L.d { "MessageContactsCacheUtil: Reloaded contactors from database" }

                        // 3. 发送刷新事件
                        _contactorCacheRefreshed.emit(Unit)
                        L.d { "MessageContactsCacheUtil: Refresh event emitted" }
                    }
                }
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
            // Check and update large group read positions
            checkAndUpdateLargeGroupReadPositions()
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

    /**
     * 检查并更新大群的已读位置
     * 对于大群（群人数 > chatWithoutReceiptThreshold），直接将所有成员的已读位置更新为自己发的最后一条消息的时间戳
     */
    private suspend fun checkAndUpdateLargeGroupReadPositions() {
        // 只对群聊进行处理
        if (forWhat !is For.Group) return

        try {
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            val memberCount = wcdb.getGroupMemberCount(forWhat.id)

            // 只对大群进行处理
            if (memberCount <= threshold) return

            // 获取自己发的最后一条消息的 systemShowTimestamp（使用数据库 MAX 查询）
            val maxTimestamp = wcdb.message.getValue(
                DBMessageModel.systemShowTimestamp.max(),
                DBMessageModel.roomId.eq(forWhat.id)
                    .and(DBMessageModel.fromWho.eq(globalServices.myId))
            )?.long ?: 0L

            if (maxTimestamp > 0) {
                L.i { "[${forWhat.id}] Large group with $memberCount members (threshold: $threshold), updating all members' read positions to $maxTimestamp" }
                wcdb.updateGroupMembersReadPosition(forWhat.id, maxTimestamp)
                RoomChangeTracker.trackRoomReadInfoUpdate(forWhat.id)
            }
        } catch (e: Exception) {
            L.e(e) { "[${forWhat.id}] Error checking and updating large group read positions" }
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

    var messageQuoted: Subject<ChatMessage> = BehaviorSubject.create()
    var messageForward: Subject<Pair<ChatMessage, Boolean>> = BehaviorSubject.create()
    var messageRecall: Subject<ChatMessage> = BehaviorSubject.create()
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
                ToastUtil.show(R.string.call_is_calling_tip)
            }
        } else {
            //判断当前是否有livekit会议，有则join会议
            val callData = LCallManager.getCallDataByConversationId(forWhat.id)
            if (callData != null) {
                L.i { "[call] Joining existing call with roomId:${callData.roomId}" }
                LCallManager.joinCall(activity.applicationContext, callData) { status ->
                    if (!status) {
                        L.e { "[Call] startCall join call failed." }
                        ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                    }
                }
                return
            }
            //否则发起livekit call通话
            L.i { "[call] Starting new call" }
            callManager.startCall(activity, forWhat, chatRoomName) { status, message ->
                if (!status) {
                    L.e { "[Call] start call failed." }
                    message?.let { ToastUtil.show(it) }
                }
            }
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

    fun quoteMessage(data: ChatMessage) {
        messageQuoted.onNext(data)
    }

    fun forwardMessage(data: ChatMessage, saveToNote: Boolean) {
        messageForward.onNext(data to saveToNote)
    }

    fun recallMessage(data: ChatMessage) {
        messageRecall.onNext(data)
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
                ToastUtil.show(R.string.chat_translate_fail)
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

    private suspend fun assembleMessagesUIData(
        chatMessageListBehavior: ChatMessageListBehavior,
        selectMessageState: SelectMessageState,
        readInfoList: List<ReadInfoModel>
    ): ChatMessageListUIState {
        // 1. 先批量查询消息发送者的联系人信息（generateMessageTwo需要用于设置nickname）
        val senderIds = chatMessageListBehavior.messageList.map { it.fromWho }.distinct()
        val members = withContext(Dispatchers.IO) {
            wcdb.getContactorsFromAllTable(senderIds)
        }

        // 2. 在循环外判断是否是大群，避免重复查询
        val isLargeGroup = if (forWhat is For.Group) {
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            val memberCount = wcdb.getGroupMemberCount(forWhat.id)
            memberCount > threshold
        } else {
            false
        }

        // 3. 生成 ChatMessage（generateMessageTwo 内部会查询子数据一次）
        // 转换锚点消息用于计算显示逻辑
        val anchorChatMessageBefore = chatMessageListBehavior.anchorMessageBefore?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup)
        }
        val anchorChatMessageAfter = chatMessageListBehavior.anchorMessageAfter?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup)
        }

        val chatMessages = chatMessageListBehavior.messageList.mapNotNull { msg ->
            generateMessageTwo(forWhat, msg, members, readInfoList, isLargeGroup)?.apply {
                editMode = selectMessageState.editModel
                selectedStatus = id in selectMessageState.selectedMessageIds
            }
        }

        // 4. 从已生成的 ChatMessage 中收集所有联系人ID（不触发新查询）
        val allMessagesToCollect = listOfNotNull(anchorChatMessageBefore, anchorChatMessageAfter) + chatMessages
        val allContactIds = com.difft.android.chat.MessageContactsCacheUtil.collectContactIds(allMessagesToCollect)

        // 5. 批量加载联系人到当前页面的缓存（只查询缓存中不存在的）
        contactorCache.loadContactors(allContactIds)
        val list = chatMessages.sortedBy { message -> message.systemShowTimestamp }

        // 过滤掉错误通知消息
        val listWithoutErrorNotify = list.filterNot {
            it is NotifyChatMessage && it.notifyMessage?.showContent.isNullOrEmpty()
        }

        // 使用传递过来的 readPosition，如果没有传递则为 null（不显示分割线）
        val readPosition = chatMessageListBehavior.readPosition

        // 标记是否已经找到第一个未读的非自己发送的消息
        var firstUnreadFound = false

        // 处理消息显示逻辑
        val newList = listWithoutErrorNotify.mapIndexed { index, message ->
            // 使用锚点消息来计算第一条和最后一条消息的显示逻辑
            val previousMessage = if (index > 0) {
                listWithoutErrorNotify[index - 1]
            } else {
                anchorChatMessageBefore
            }
            val isSameDayWithPreviousMessage = TimeFormatter.isSameDay(message.timeStamp, previousMessage?.timeStamp ?: 0L)

            val nextMessage = if (index < listWithoutErrorNotify.size - 1) {
                listWithoutErrorNotify[index + 1]
            } else {
                anchorChatMessageAfter
            }
            val isSameDayWithNextMessage = TimeFormatter.isSameDay(message.timeStamp, nextMessage?.timeStamp ?: 0L)

            message.showName = !isSameDayWithPreviousMessage || previousMessage is NotifyChatMessage || message.authorId != previousMessage?.authorId
            message.showDayTime = !isSameDayWithPreviousMessage
            message.showTime = !isSameDayWithNextMessage || message.authorId != nextMessage?.authorId

            // 设置新消息分割线：仅在初始化加载时（readPosition 不为 null）显示
            // 在 readPosition 之后第一个不是自己发送的消息上显示
            if (!firstUnreadFound && readPosition != null && message.systemShowTimestamp > readPosition && !message.isMine) {
                message.showNewMsgDivider = true
                firstUnreadFound = true
            } else {
                message.showNewMsgDivider = false
            }

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
            // 判断是否为大群（群人数大于阈值）
            val isLargeGroup = if (forWhat is For.Group) {
                val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
                val memberCount = wcdb.getGroupMemberCount(forWhat.id)
                val isLarge = memberCount > threshold
                if (isLarge) {
                    L.i { "[${forWhat.id}] Large group with $memberCount members (threshold: $threshold), will skip sending read receipts to senders but will send sync message" }
                }
                isLarge
            } else {
                false
            }

            val lastReadPosition = dbRoomStore.getMessageReadPosition(forWhat).blockingGet()
            if (currentReadPosition > lastReadPosition) {
                val messages = wcdb.message.getAllObjects(
                    DBMessageModel.roomId.eq(forWhat.id)
                        .and(DBMessageModel.fromWho.notEq(globalServices.myId))
                        .and(DBMessageModel.mode.eq(0))
                        .and(DBMessageModel.systemShowTimestamp.between(lastReadPosition, currentReadPosition))
                )

                if (messages.isEmpty()) {
                    L.i { "[${forWhat.id}] No messages to send read receipt for" }
                    return@withContext
                }

                val groupedMessages = messages.groupBy { it.fromWho }

                // 1. 小群/单聊：为每个发送者创建Job发送已读回执 大群：跳过这一步
                if (!isLargeGroup) {
                    groupedMessages.forEach { (contactId, senderMessages) ->
                        L.i { "[${forWhat.id}] Creating read receipt job for $contactId, messages=${senderMessages.size}" }
                        val maxMessage = senderMessages.maxBy { it.systemShowTimestamp }
                        val readPosition = ReadPosition(
                            forWhat.id.takeIf { forWhat is For.Group },
                            maxMessage.timeStamp,
                            maxMessage.systemShowTimestamp,
                            maxMessage.notifySequenceId,
                            maxMessage.sequenceId
                        )
                        ApplicationDependencies.getJobManager().add(
                            pushReadReceiptSendJobFactory.create(
                                recipientId = contactId,
                                forWhat = forWhat,
                                messageTimeStamps = senderMessages.map { it.timeStamp },
                                readPosition = readPosition,
                                messageMode = 0,
                                sendReceiptToSender = true,   // 小群发送已读回执
                                sendSyncToSelf = false        // 统一在最后发送同步消息
                            )
                        )
                    }
                } else {
                    L.i { "[${forWhat.id}] Large group: skipping read receipt jobs, will only send sync message" }
                }

                // 2. 统一发送一次同步消息（无论是否大群）
                // 从所有消息中找到时间戳最大的消息，用于同步已读位置
                val syncMaxMessage = messages.maxBy { it.systemShowTimestamp }
                val syncReadPosition = ReadPosition(
                    forWhat.id.takeIf { forWhat is For.Group },
                    syncMaxMessage.timeStamp,
                    syncMaxMessage.systemShowTimestamp,
                    syncMaxMessage.notifySequenceId,
                    syncMaxMessage.sequenceId
                )
                L.i { "[${forWhat.id}] Creating sync-only job to sync read position to self's other devices, maxTimestamp=${syncMaxMessage.systemShowTimestamp}" }
                ApplicationDependencies.getJobManager().add(
                    pushReadReceiptSendJobFactory.create(
                        recipientId = syncMaxMessage.fromWho,
                        forWhat = forWhat,
                        messageTimeStamps = listOf(syncMaxMessage.timeStamp),
                        readPosition = syncReadPosition,
                        messageMode = 0,
                        sendReceiptToSender = false,  // 不发已读回执
                        sendSyncToSelf = true          // 只发同步消息
                    )
                )
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
