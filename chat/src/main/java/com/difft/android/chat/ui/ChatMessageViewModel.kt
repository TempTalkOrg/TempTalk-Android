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
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.call.LChatToCallController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.ChatMessageListBehavior
import com.difft.android.chat.IChatPaginationController
import com.difft.android.chat.R
import com.difft.android.chat.compose.SelectMessageState
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.ConfidentialPlaceholderChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageTwo
import com.difft.android.chat.message.isNotifyStyleMessage
import com.difft.android.chat.speech2text.SpeechToTextManager
import com.difft.android.chat.translate.TranslateManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.BaseResponse
import com.google.mlkit.nl.translate.TranslateLanguage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import com.difft.android.chat.message.NoticeAggregator
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.ReadPosition
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.forEachMessagePaged
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.getMessageById
import org.difft.app.database.putMessageIfNotExists
import org.difft.app.database.delete
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.getReadInfoList
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.models.ReadInfoModel
import org.difft.app.database.updateGroupMembersReadPosition
import org.difft.app.database.wcdb
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.create
import util.TimeFormatter
import javax.inject.Inject

/**
 * Per-sender read-receipt accumulator used while paging in-range messages in
 * [ChatMessageViewModel.sendReadRecipient]. Holds a reference to that sender's current max
 * message (a handful of refs) plus its read-message `timeStamp`s — never the full message
 * objects en masse. (#909 #5)
 */
internal data class SenderReadReceiptAcc(
    var maxMessage: MessageModel,
    val timeStamps: MutableList<Long>
)

/**
 * One per-sender read-receipt Job to emit: the recipient, the (possibly chunked) timestamp
 * list it carries, and the max message whose tuple becomes the Job's [ReadPosition]. The
 * dispatch decision (which Jobs to emit) is computed by
 * [ChatMessageViewModel.planReadReceiptJobs] so the large-group skip + chunking branch can be
 * unit-tested without WCDB / the Job factory. (#909 #5, T18)
 */
internal data class ReadReceiptJobPlan(
    val recipientId: String,
    val timeStamps: List<Long>,
    val maxMessage: MessageModel
)

// All wcdb calls in this class run on Dispatchers.IO.
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
    private val translateManager: dagger.Lazy<TranslateManager>,
    private val speechToTextManager: dagger.Lazy<SpeechToTextManager>,
    private val pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory,
    private val activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callDataManager: CallDataManager
) : ViewModel(),
    IChatPaginationController by chatPaginationControllerFactory.create(forWhat) {

    init {
        L.i { "[message]=========open ${if (forWhat is For.Group) "group" else "one to one"} chat page===========${forWhat.id}." }
    }

    val viewModelCreateTime = System.currentTimeMillis()

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

    private val _chatMessageListUIState = MutableStateFlow<ChatMessageListUIState?>(null)

    val chatMessageListUIState: StateFlow<ChatMessageListUIState?> =
        _chatMessageListUIState.asStateFlow()

    private val _readInfoList = MutableStateFlow<List<ReadInfoModel>>(emptyList())

    val forwardMultiMessage = MutableStateFlow<ForwardContextData?>(null)
    val saveMultiMessageToNote = MutableStateFlow<ForwardContextData?>(null)

    val selectMessagesState = MutableStateFlow(SelectMessageState(false, emptySet(), 0))

    // Emits the messageId of a checkbox tap that was rejected (e.g. exceeds the cap),
    // so the list can revert that row's checkbox visual to match selectMessagesState.
    private val _selectionRejected = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val selectionRejected: SharedFlow<String> = _selectionRejected.asSharedFlow()

    // 用于判断消息数据是否真正变化，避免 _readInfoList 变化时重复触发滚动
    private var lastMessageListTimestamp: Long = 0

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
                .collect { updatedContactIds ->
                    withContext(Dispatchers.IO) {
                        // 获取缓存中的所有联系人ID
                        val cachedIds = contactorCache.getCachedIds()

                        // 找出需要更新的联系人（在缓存中的）
                        val idsToRefresh = updatedContactIds.filter { cachedIds.contains(it) }.toSet()

                        if (idsToRefresh.isNotEmpty()) {
                            // 清除缓存中的旧数据并重新加载
                            contactorCache.remove(idsToRefresh)
                            contactorCache.loadContactors(idsToRefresh, (forWhat as? For.Group)?.id)
                            _contactorCacheRefreshed.emit(Unit)
                            L.i { "MessageContactsCacheUtil: Refreshed ${idsToRefresh.size} cached contactors" }
                        }
                    }
                }
        }
    }

    /**
     * Directly refresh a specific contact in the message contact cache.
     *
     * Used after the chat page fetches latest contact info from server,
     * bypassing the global event bus (contactsUpdate) to avoid circular refresh.
     */
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

    @Inject
    fun initialize() {
        bindCoroutineScope(viewModelScope)
        combine(
            chatMessagesStateFlow.filterNotNull(), // 过滤初始 null 状态
            _readInfoList
        ) { chatMessageListBehavior, readInfoList ->
            assembleMessagesUIData(chatMessageListBehavior, readInfoList)
        }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
            .onEach {
                _chatMessageListUIState.value = it
            }.launchIn(viewModelScope)

        // PRD §5 recall cleanup: rely on the wcdb query in onCopyClick to drop
        // deleted/recalled ids — not on chatMessagesStateFlow, which only carries
        // a paginated window (max 60 messages) and would mis-evict ids scrolled
        // out of view.

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


    /**
     * Optimistic add: persist the sent message off-main then append it to the list so it renders
     * instantly, bypassing JobManager scheduling + the throttled observer re-query. Goes through
     * putMessageIfNotExists so onAdded() finds the row and skips — no duplicate child rows (#997).
     */
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

    val voiceVisibilityChange = MutableStateFlow(false)

    fun setVoiceVisibility(visible: Boolean) {
        voiceVisibilityChange.value = visible
    }

    val avatarLongClicked: MutableSharedFlow<ContactorModel> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun longClickAvatar(contactorModel: ContactorModel) {
        avatarLongClicked.tryEmit(contactorModel)
    }

    val listClick: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun clickList() {
        listClick.tryEmit(Unit)
    }

    // Channel buffers the emit during Activity recreation so it isn't lost when the new collector subscribes.
    private val _voiceMessageSendChannel = Channel<String>(capacity = Channel.BUFFERED)
    val voiceMessageSend: Flow<String> = _voiceMessageSendChannel.receiveAsFlow()

    fun sendVoiceMessage(path: String) {
        val result = _voiceMessageSendChannel.trySend(path)
        if (result.isFailure) {
            L.w { "[ChatMessageViewModel] voiceMessageSend channel send failed" }
        }
    }

    val chatActionsShow: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun showChatActions() {
        chatActionsShow.tryEmit(Unit)
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
            //判断当前是否有livekit会议，有则join会议
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
            //否则发起livekit call通话
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

    val confidentialViewReceipt: MutableSharedFlow<ChatMessage> = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val translateEvent: MutableSharedFlow<Pair<String, TranslateData>> = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val speechToTextEvent: MutableSharedFlow<Pair<String, SpeechToTextData>> = MutableSharedFlow(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun sendConfidentialViewReceipt(message: ChatMessage) {
        L.i { "[Confidential] Send view receipt, messageId: ${message.id}, timestamp: ${message.timeStamp}" }
        confidentialViewReceipt.tryEmit(message)
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
        messageResend.tryEmit(data)
    }

    fun deleteMessage(messageId: String) {
        dbMessageStore.deleteMessage(listOf(messageId))
    }

    // ========== Confidential placeholder: auto-delete 3s after displayed, fallback on page close ==========
    private val pendingConfidentialPlaceholders = mutableSetOf<Long>()

    /**
     * Record a seen confidential placeholder and auto-delete it after 3 seconds.
     * Each placeholder launches its own coroutine. On successful deletion, it is
     * removed from the pending set so the fallback in onDestroyView skips it.
     */
    fun markConfidentialPlaceholderAsSeen(timestamp: Long) {
        if (!pendingConfidentialPlaceholders.add(timestamp)) return
        viewModelScope.launch {
            delay(AUTO_DELETE_DELAY)
            deleteConfidentialPlaceholder(timestamp)
            pendingConfidentialPlaceholders.remove(timestamp)
        }
    }

    /**
     * Fallback: batch delete remaining placeholders on page close.
     * Only processes items that were not yet auto-deleted (e.g., page closed within 3s).
     */
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

        /**
         * Max read-message timestamps carried by a single read-receipt Job. A sender whose
         * in-range timestamp list exceeds this is split across multiple Jobs sharing the same
         * [ReadPosition]. Prevents the #909 OOM from relocating into Job `Data` serialization
         * (a single 1000-long LongArray ≈ 8KB payload — safe). (#909 TP4)
         */
        const val MAX_TIMESTAMPS_PER_RECEIPT = 1000

        /**
         * Folds [msg] into the per-sender accumulator [perSender], updating that sender's
         * running max message and appending its `timeStamp`. Pure (no DB / no I/O) so it can be
         * unit-tested by feeding a message sequence directly. (#909 #5)
         */
        internal fun accumulateReadReceipt(
            perSender: HashMap<String, SenderReadReceiptAcc>,
            msg: MessageModel
        ) {
            val sender = msg.fromWho ?: ""
            val acc = perSender[sender]
            if (acc == null) {
                perSender[sender] = SenderReadReceiptAcc(msg, mutableListOf(msg.timeStamp))
            } else {
                acc.timeStamps += msg.timeStamp
                if (msg.systemShowTimestamp > acc.maxMessage.systemShowTimestamp) acc.maxMessage = msg
            }
        }

        /**
         * Computes the per-sender read-receipt Jobs to emit for an accumulated [perSender] map.
         * - Large group ([isLargeGroup] true): returns empty — no per-sender receipts are sent
         *   (only the sync job, emitted by the caller, runs). (#909 #5 large-group branch)
         * - Otherwise: one plan per sender, with that sender's timestamp list split into chunks
         *   of at most [chunkCap] so a single Job never serializes an unbounded LongArray. All
         *   chunks for a sender share the same max message ⇒ same [ReadPosition]. (#909 TP4)
         *
         * Pure (no DB / no Job factory) so the dispatch + chunking branch is unit-testable. (T18)
         */
        internal fun planReadReceiptJobs(
            perSender: Map<String, SenderReadReceiptAcc>,
            isLargeGroup: Boolean,
            chunkCap: Int = MAX_TIMESTAMPS_PER_RECEIPT
        ): List<ReadReceiptJobPlan> {
            if (isLargeGroup) return emptyList()
            return perSender.flatMap { (recipientId, acc) ->
                acc.timeStamps.chunked(chunkCap).map { chunk ->
                    ReadReceiptJobPlan(recipientId, chunk, acc.maxMessage)
                }
            }
        }
    }

    fun emojiReaction(emojiEvent: EmojiReactionEvent) {
        messageEmojiReaction.tryEmit(emojiEvent)
    }

    private fun updateTranslateStatus(id: String, translateData: TranslateData) {
        translateEvent.tryEmit(id to translateData)
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
                speechToTextManager.get().speechToText(
                    viewModelScope,
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
            data.forwardContext?.forwards?.firstOrNull()?.text.takeUnless { it.isNullOrEmpty() }
        } else data.message.takeUnless { it.isNullOrEmpty() }
            ?: ""

        val targetLang = if (targetLanguage == TranslateTargetLanguage.ZH) TranslateLanguage.CHINESE else TranslateLanguage.ENGLISH

        translateManager.get().translateText(
            scope = viewModelScope,
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbMessageStore.updateMessageTranslateData(forWhat.id, data.id, translateData)
            } catch (e: Exception) {
                L.e { "[ChatMessageViewModel] updateTranslateDataOfDB error: ${e.stackTraceToString()}" }
            }
        }
    }

    private suspend fun assembleMessagesUIData(
        chatMessageListBehavior: ChatMessageListBehavior,
        readInfoList: List<ReadInfoModel>
    ): ChatMessageListUIState {
        // 1. 先批量查询消息发送者的联系人信息（generateMessageTwo需要用于设置nickname）
        val senderIds = chatMessageListBehavior.messageList.mapNotNull { it.fromWho }.distinct()
        val members = withContext(Dispatchers.IO) {
            wcdb.getContactorsFromAllTable(senderIds)
        }

        // 2. 在循环外判断是否是大群，避免重复查询
        val groupMemberCount = if (forWhat is For.Group) wcdb.getGroupMemberCount(forWhat.id) else 0
        val isLargeGroup = if (forWhat is For.Group) {
            val threshold = globalServices.globalConfigsManager.getNewGlobalConfigs()?.data?.group?.chatWithoutReceiptThreshold ?: Double.MAX_VALUE
            groupMemberCount > threshold
        } else {
            false
        }

        // 3. 生成 ChatMessage（generateMessageTwo 内部会查询子数据一次）
        // 转换锚点消息用于计算显示逻辑
        val anchorChatMessageBefore = chatMessageListBehavior.anchorMessageBefore?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup, groupMemberCount)
        }
        val anchorChatMessageAfter = chatMessageListBehavior.anchorMessageAfter?.let {
            generateMessageTwo(forWhat, it, members, readInfoList, isLargeGroup, groupMemberCount)
        }

        val chatMessages = chatMessageListBehavior.messageList.mapNotNull { msg ->
            generateMessageTwo(forWhat, msg, members, readInfoList, isLargeGroup, groupMemberCount)
        }

        // 4. 从已生成的 ChatMessage 中收集所有联系人ID（不触发新查询）
        val allMessagesToCollect = listOfNotNull(anchorChatMessageBefore, anchorChatMessageAfter) + chatMessages
        val allContactIds = com.difft.android.chat.MessageContactsCacheUtil.collectContactIds(allMessagesToCollect)

        // 5. 批量加载联系人到当前页面的缓存（只查询缓存中不存在的）
        contactorCache.loadContactors(allContactIds, (forWhat as? For.Group)?.id)
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

            message.showName = !isSameDayWithPreviousMessage || previousMessage?.isNotifyStyleMessage() == true || message.authorId != previousMessage?.authorId

            // Notify-style messages (NotifyChatMessage, screenshot) never show the day header.
            // The day header transfers to the first normal message that follows.
            if (message.isNotifyStyleMessage()) {
                message.showDayTime = false
            } else {
                val previousNonNotify = if (index > 0) {
                    listWithoutErrorNotify.subList(0, index).lastOrNull { !it.isNotifyStyleMessage() }
                } else {
                    null
                } ?: anchorChatMessageBefore?.takeIf { !it.isNotifyStyleMessage() }
                message.showDayTime = !TimeFormatter.isSameDay(message.timeStamp, previousNonNotify?.timeStamp ?: 0L)
            }

            message.showTime = !isSameDayWithNextMessage || nextMessage?.isNotifyStyleMessage() == true || message.authorId != nextMessage?.authorId

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

        // 只有消息数据真正变化时才传递 scrollAction，避免 _readInfoList 变化时重复触发滚动
        val scrollAction = if (chatMessageListBehavior.updateTimestamp != lastMessageListTimestamp) {
            lastMessageListTimestamp = chatMessageListBehavior.updateTimestamp
            chatMessageListBehavior.scrollAction
        } else {
            null
        }

        L.i { "[${forWhat.id}] Finally to be submit to recyclerview adapter with chat message size: ${newList.size}, scrollAction: $scrollAction" }
        return ChatMessageListUIState(
            chatMessages = newList,
            scrollAction = scrollAction
        )
    }

    suspend fun sendReadRecipient(currentReadPosition: Long, readAt: Long) = withContext(Dispatchers.IO) {
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

            val lastReadPosition = dbRoomStore.getMessageReadPosition(forWhat)
            if (currentReadPosition > lastReadPosition) {
                // #1020 Phase 2: readAt (server-axis read moment) is sampled once by the caller and
                // passed to both this and updateMessageReadPosition so wire and local readTime match.
                // #909 #5: page the in-range messages instead of loading them all. Peak memory
                // is one page + per-sender max-message refs + the timestamp longs, never the
                // whole match set of full MessageModel objects.
                val rangeCond = DBMessageModel.roomId.eq(forWhat.id)
                    .and(DBMessageModel.fromWho.notEq(globalServices.myId))
                    .and(DBMessageModel.systemShowTimestamp.between(lastReadPosition, currentReadPosition))

                val perSender = HashMap<String, SenderReadReceiptAcc>()
                var globalMax: MessageModel? = null
                forEachMessagePaged(rangeCond) { msg ->
                    accumulateReadReceipt(perSender, msg)
                    if (globalMax == null || msg.systemShowTimestamp > globalMax!!.systemShowTimestamp) globalMax = msg
                }

                if (perSender.isEmpty()) {
                    L.i { "[${forWhat.id}] No messages to send read receipt for" }
                    return@withContext
                }

                // 1. 小群/单聊：为每个发送者创建Job发送已读回执 大群：跳过这一步
                // #909 #5/TP4: dispatch + chunk decision is computed by the pure
                // planReadReceiptJobs (large group ⇒ empty; otherwise one plan per chunk).
                val receiptJobPlans = planReadReceiptJobs(perSender, isLargeGroup)
                if (isLargeGroup) {
                    L.i { "[${forWhat.id}] Large group: skipping read receipt jobs, will only send sync message" }
                }
                receiptJobPlans.forEach { plan ->
                    L.i { "[${forWhat.id}] Creating read receipt job for ${plan.recipientId}, messages=${plan.timeStamps.size}" }
                    val maxMessage = plan.maxMessage
                    val readPosition = ReadPosition(
                        forWhat.id.takeIf { forWhat is For.Group },
                        readAt,
                        maxMessage.systemShowTimestamp,
                        maxMessage.notifySequenceId,
                        maxMessage.sequenceId
                    )
                    ApplicationDependencies.getJobManager().add(
                        pushReadReceiptSendJobFactory.create(
                            recipientId = plan.recipientId,
                            forWhat = forWhat,
                            messageTimeStamps = plan.timeStamps,
                            readPosition = readPosition,
                            messageMode = 0,
                            sendReceiptToSender = true,   // 小群发送已读回执
                            sendSyncToSelf = false        // 统一在最后发送同步消息
                        )
                    )
                }

                // 2. 统一发送一次同步消息（无论是否大群）
                // 从所有消息中找到时间戳最大的消息，用于同步已读位置
                val syncMaxMessage = globalMax!!  // non-null: perSender non-empty ⇒ globalMax set
                val syncRecipientId = syncMaxMessage.fromWho
                if (syncRecipientId == null) {
                    // Anomalous (system/corrupt message as the max anchor): a job persisted with an
                    // empty recipientId would survive restart and dispatch to a non-existent recipient.
                    L.w { "[${forWhat.id}] sync-only read-position job skipped: max message ${syncMaxMessage.timeStamp} has null fromWho" }
                } else {
                    val syncReadPosition = ReadPosition(
                        forWhat.id.takeIf { forWhat is For.Group },
                        readAt,
                        syncMaxMessage.systemShowTimestamp,
                        syncMaxMessage.notifySequenceId,
                        syncMaxMessage.sequenceId
                    )
                    L.i { "[${forWhat.id}] Creating sync-only job to sync read position to self's other devices, maxTimestamp=${syncMaxMessage.systemShowTimestamp}" }
                    ApplicationDependencies.getJobManager().add(
                        pushReadReceiptSendJobFactory.create(
                            recipientId = syncRecipientId,
                            forWhat = forWhat,
                            messageTimeStamps = listOf(syncMaxMessage.timeStamp),
                            readPosition = syncReadPosition,
                            messageMode = 0,
                            sendReceiptToSender = false,  // 不发已读回执
                            sendSyncToSelf = true          // 只发同步消息
                        )
                    )
                }
            } else {
                L.i { "[${forWhat.id}] Read recipient already sent at position: $lastReadPosition, no need to resend." }
            }
        } catch (e: Exception) {
            L.e { "[${forWhat.id}] Error sending read recipient: ${e.stackTraceToString()}" }
        }
    }

    suspend fun updateMessageReadPosition(readPosition: Long, readAt: Long) = withContext(Dispatchers.IO) {
        try {
            // #1020 Phase 2: readTime = actual read moment (readAt from caller); readPosition selects rows.
            dbRoomStore.updateMessageReadPosition(forWhat, readPosition)
            dbMessageStore.updateMessageReadTime(forWhat.id, readPosition, readAt)
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

    suspend fun getUnreadMessageInfo(): UnreadMessageInfo? {
        return try {
            dbRoomStore.getUnreadMessageInfo(forWhat)
        } catch (e: Exception) {
            L.e { "[${forWhat.id}] Error getting unread message info: ${e.stackTraceToString()}" }
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
            // Recalculate recallable messages based on new selection
            val recallableIds = calculateRecallableMessageIds(newSelectedIds)
            selectMessagesState.value = state.copy(
                selectedMessageIds = newSelectedIds,
                recallableMessageIds = recallableIds
            )
        }
    }

    fun selectModel(enable: Boolean) {
        val totalMessageCount = dbMessageStore.selectableMessageCount(forWhat)
        // 关闭选择模式时清空已选消息和可撤回消息
        val selectedIds = if (enable) selectMessagesState.value.selectedMessageIds else emptySet()
        val recallableIds = if (enable) selectMessagesState.value.recallableMessageIds else emptySet()
        selectMessagesState.value = selectMessagesState.value.copy(
            editModel = enable,
            selectedMessageIds = selectedIds,
            totalMessageCount = totalMessageCount,
            recallableMessageIds = recallableIds
        )
    }

    /**
     * Calculate which selected messages can be recalled
     * Recall conditions: message.isMine && isWithinRecallTimeout
     */
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

    // Event for batch recall using SharedFlow
    private val _batchRecallMessages = MutableSharedFlow<Set<String>>()
    val batchRecallMessages: SharedFlow<Set<String>> = _batchRecallMessages.asSharedFlow()

    /**
     * Called when user clicks the batch recall button
     * Emits the set of recallable message IDs for the Fragment to handle
     */
    fun onBatchRecallClick() {
        val recallableIds = selectMessagesState.value.recallableMessageIds
        if (recallableIds.isNotEmpty()) {
            viewModelScope.launch {
                _batchRecallMessages.emit(recallableIds)
            }
        }
    }

    /**
     * Multi-select → Forward. The "Combine & Forward" entry was merged into "Forward":
     * a single selected message is forwarded on its own (Scene.SINGLE, preserving
     * chat-history nesting / shared-contact / attachment), while a multi-selection is
     * always bundled into ONE combined-forward container (Scene.COMBINED). The old
     * per-message one-by-one path (Scene.ONE_BY_ONE) is no longer produced here; the
     * enum value + proto mapping stay for wire compatibility with peers/older clients.
     *
     * PRD §5.3.4: priority-sort authors (CF sender → count desc → ts desc → authorId asc);
     * messageCount = selected message count (a CF bubble counts as 1).
     */
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
        // PRD v2.0 §改动3: pin the operator (myId) last in the source list.
        val sortedAuthorIds = NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages(loadedMessages, selfIdLast = myId)
        // PRD v2.0 §改动1/§改动2 条件①④: forward leaves a trace only if it carries someone else's
        // real message (CF judged by inner authors). The forward ACTION still proceeds either way.
        val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContentFromTextMessages(loadedMessages, myId)

        val (forwardContexts, scene) = if (loadedMessages.size == 1) {
            // Single selection → forward the one message as its own message.
            val message = loadedMessages.first()
            // Local val enables smart-cast in the != null branch (forwardContext is a
            // mutable property, so it cannot be smart-cast directly).
            val nestedForward = message.forwardContext
            val singleContext: ForwardContext? = if (!message.sharedContact.isNullOrEmpty()) {
                val sharedContactId = message.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
                val sharedContactName = message.sharedContact?.getOrNull(0)?.name?.displayName
                ForwardContext(null, false, sharedContactId, sharedContactName)
            } else if (nestedForward != null) {
                nestedForward.apply {
                    this.forwards?.forEach { forward ->
                        if (!forward.attachments.isNullOrEmpty()) {
                            forward.attachments = forward.attachments?.subList(0, 1)
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
                            message.attachments,
                            null,
                            message.mentions,
                            message.systemShowTimestamp
                        )
                    )
                }, message.forWhat is For.Group)
            }
            listOfNotNull(singleContext) to ForwardNoticeData.Scene.SINGLE
        } else {
            // Multi-select → bundle selected messages into ONE combined-forward container.
            val combinedContext = ForwardContext(loadedMessages.map { message ->
                Forward(
                    message.timeStamp,
                    0,
                    message.forWhat is For.Group,
                    message.fromWho.id,
                    if (!message.sharedContact.isNullOrEmpty()) ResUtils.getString(R.string.chat_message_contact_card_content) else message.text,
                    message.attachments,
                    message.forwardContext?.forwards,
                    message.mentions,
                    message.systemShowTimestamp
                )
            }, forWhat is For.Group)
            listOfNotNull(combinedContext) to ForwardNoticeData.Scene.COMBINED
        }

        forwardMultiMessage.value = ForwardContextData(
            "",
            forwardContexts,
            scene,
            forWhat,
            sourceAuthorIds = sortedAuthorIds,
            messageCount = loadedMessages.size,
            combinedForwardMode = mode,
            carriesForeignContent = carriesForeignContent,
        )
        resetSelectMessageState()
    }

    private fun resetSelectMessageState() {
        selectMessagesState.value = selectMessagesState.value.copy(
            editModel = false,
            selectedMessageIds = emptySet(),
            recallableMessageIds = emptySet()
        )
    }

    /**
     * Multi-select → Copy: format the selected messages and write to the clipboard
     * inside the ViewModel coroutine (uses [application] context), THEN enqueue the
     * copy notice. PRD §4.1 requires the trace notice be emitted only after a
     * successful copy — writing the clipboard inline (rather than via a SharedFlow
     * + Fragment collector) eliminates the race where the Fragment could be detached
     * before the collector fires, leaving the clipboard empty while the notice goes
     * out to peers.
     *
     * Order: messages are sorted by display position in the conversation (PRD §3.6),
     * not by the user's selection order.
     *
     * Author names: passed through the message contacts cache with the remark-free
     * accessor — PRD §3.3 mandates the author's own name in clipboard text (the
     * trace system message uses remark-name; see Phase 4).
     */
    fun onCopyClick() = viewModelScope.launch(Dispatchers.IO) {
        val selectedIds = selectMessagesState.value.selectedMessageIds
        if (selectedIds.isEmpty()) return@launch
        val loadedMessages = wcdb.message
            .getAllObjects(DBMessageModel.id.`in`(*selectedIds.toTypedArray()))
            .map { it.convertToTextMessage() }
            .sortedBy { it.systemShowTimestamp }
        if (loadedMessages.isEmpty()) return@launch

        // Pre-warm name resolver for all authors + shared-contact uids in one batch
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

        // Clipboard write + toast on main thread (Toast.show requires main looper).
        // Inline write guarantees PRD §4.1: notice is enqueued ONLY after the
        // clipboard write succeeds in this coroutine — no lifecycle-cancellable
        // collector indirection between the format and the notice.
        kotlinx.coroutines.withContext(Dispatchers.Main) {
            com.difft.android.chat.util.Util.copyToClipboard(
                com.difft.android.base.utils.application,
                formatted,
            )
        }

        // PRD §4: emit copy notice into the source conversation. Android's multi-select
        // is single-source, so all selected messages share forWhat as the source.
        // PRD v2.0 §改动1 条件①④⑤: only leave a trace when the clipboard actually carries
        // another person's real content. A selection that is all-self, or only placeholder
        // bubbles (Chat History / single forward / contact card), leaks nothing real.
        val myId = globalServices.myId
        if (NoticeAggregator.copyCarriesForeignContentFromTextMessages(loadedMessages, myId)) {
            // PRD §5.3: derive mode + sorted authors (isSubContext=false — multi-select copy
            // is always main-conv); §改动3 pins the operator (myId) last in the source list.
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

    /**
     * Entry point for emitting a copy notice from the single-message paths
     * (single long-press copy / partial text-selection copy / derived-content copy).
     * Caller invokes this only AFTER the clipboard write actually succeeded (PRD §4.1).
     *
     * PRD §5.3: this single-message path is invoked from main-conv surfaces; mode defaults to
     * UNKNOWN. Caller may override via [combinedForwardMode] when the source message originated
     * from a CF detail view (Phase 5 wiring through transient `sourceMode`).
     */
    fun sendCopyNotice(
        sourceMessages: List<TextChatMessage>,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        if (sourceMessages.isEmpty()) return
        // PRD v2.0 §改动1/§改动2 条件①⑤: trace only when the clipboard carries another person's
        // real content. A self message, or a placeholder bubble (Chat History / single forward /
        // contact card copies as a bracketed placeholder), leaks nothing real.
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
        // PRD §5.3: derive mode from main-conv selection (isSubContext=false).
        val saveMode = NoticeAggregator.computeCombinedForwardModeFromTextMessages(loadedMessages, isSubContext = false)
        val myId = globalServices.myId
        // PRD v2.0 §改动1/§改动2 条件①④: save-to-notes leaves a trace only if it moves someone
        // else's real message out of the conversation. The save ACTION proceeds either way.
        val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContentFromTextMessages(loadedMessages, myId)
        if (loadedMessages.size == 1 && loadedMessages.firstOrNull()?.sharedContact != null) {
            val sharedContactId = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.phone?.getOrNull(0)?.value
            val sharedContactName = loadedMessages.firstOrNull()?.sharedContact?.getOrNull(0)?.name?.displayName
            // PRD §5.3.4: priority-sort authors; messageCount = selected count (CF as 1).
            saveMultiMessageToNote.value =
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
                    message.attachments,
                    message.forwardContext?.forwards,
                    message.mentions,
                    it.systemShowTimestamp
                )
            }, forWhat is For.Group)
            // PRD §5.3.4: priority-sort authors; messageCount = selected count (CF as 1).
            saveMultiMessageToNote.value =
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
    val sourceConversation: For,   // Source conversation (where the forwarded messages originally lived). forwardNotice will be posted here.
    // Authors of the user-selected messages. PRD §5.3.4: deduped and priority-sorted via
    // NoticeAggregator.computeSortedSourceAuthorIdsFromTextMessages — CF senders first,
    // then by contributed count desc, then latest timestamp desc, then authorId asc.
    // For notice count semantics, use [messageCount] (NOT sourceAuthorIds.size).
    val sourceAuthorIds: List<String>,
    // PRD §5.3: explicit selected-message count (one per user-selected outer message;
    // a combined-forward bubble counts as 1). Decoupled from sourceAuthorIds.size so
    // the author list can be deduped+sorted independently.
    val messageCount: Int,
    // PRD v1.0 §5.3 combined-forward mode derived from the selected messages by the ViewModel
    // (single source of truth; UI fragment just plumbs through to SelectChatsUtils).
    val combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    // PRD v2.0 §改动1/§改动2 条件①④: whether the selection includes another person's real message
    // (by original author — CF/single-forward judged by recursing to the leaf authors). Drives
    // whether a forward/save notice fires. Callers SHOULD always compute and pass this explicitly via
    // NoticeAggregator.forwardCarriesForeignContent*; the `true` default exists only for legacy/share
    // entry points that cannot compute it (conservative: trace rather than silently miss).
    val carriesForeignContent: Boolean = true,
)
