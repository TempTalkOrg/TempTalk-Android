package com.difft.android.chat.recent

import android.app.Activity
import android.text.TextUtils
import androidx.lifecycle.viewModelScope
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import androidx.lifecycle.ViewModel
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.DraftRepository
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusDeletedRowStore
import com.difft.android.chat.focus.FocusPinCoordinator
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.group.GroupUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import com.difft.android.chat.message.ConversationUnreadMarker
import difft.android.messageserialization.model.isManualUnreadActive
import org.difft.app.database.wcdb
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.MessageNotificationUtil
import util.TimeFormatter
import java.time.LocalDate
import javax.inject.Inject
import dagger.Lazy
import com.difft.android.base.utils.sampleAfterFirst
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/** The latest inputs of one chat-list assembly besides the calls (see [ChatListInput]). */
data class ChatListSource(
    val rooms: List<RoomViewData> = emptyList(),
    val focus: EffectiveFocus = EffectiveFocus.INACTIVE,
    val synthesizedFocusRoom: RoomViewData? = null,
    val deletedFocusGids: Set<String> = emptySet(),
    /** False only for the initial placeholder, before the first query result. */
    val isLoaded: Boolean = false,
    /** Local date (epoch day) of this snapshot: a date change re-assembles, so the folded row's "Tomorrow HH:MM" turns into "HH:MM". */
    val epochDay: Long = 0L,
)

private data class RoomInputs<D>(
    val roomModels: List<RoomModel>,
    val drafts: D,
    val focus: EffectiveFocus,
    val deletedGids: Set<String>,
)

@HiltViewModel
class RecentChatViewModel @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    @param:ChativeHttpClientModule.Call
    private val httpClient: Lazy<ChativeHttpClient>,
    private val draftRepository: DraftRepository,
    private val appIconBadgeManager: Lazy<AppIconBadgeManager>,
    private val messageNotificationUtil: Lazy<MessageNotificationUtil>,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    private val contactorCacheManager: ContactorCacheManager,
    private val groupUtil: Lazy<GroupUtil>,
    private val unreadMarker: Lazy<ConversationUnreadMarker>,
    private val focusRepository: GroupFocusRepository,
    private val focusPinCoordinator: FocusPinCoordinator,
    private val deletedRowStore: FocusDeletedRowStore,
    private val focusChatRooms: FocusChatRooms,
) : ViewModel() {

    // Read on every use: this ViewModel outlives the Activity recreation a system language change
    // causes, so a cached value would keep formatting in the old language (#1395).
    private val language get() = LanguageUtils.getLanguage(application)

    private val formatTracker = TimeTextFormatTracker { TimeFormatter.formatSignature(language.language) }

    // Re-runs the room query (and so the formatting) after a language, 12/24-hour or time-zone change.
    private val formatRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val callService by lazy {
        httpClient.get().getService(LCallHttpService::class.java)
    }

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    // ✅ 只查询room数据，draft独立管理
    private val latestRoomModelsFlow: Flow<List<RoomModel>> by lazy {
        merge(
            WCDBUpdateService.roomTableUpdated,
            updateTime.map { },  // 每分钟触发查询，作为兜底机制
            formatRefresh,
        )
            .onStart { emit(Unit) }  // 初始化时立即触发一次
            .map {
                withContext(Dispatchers.IO) {
                    val newRooms = wcdb.room.getAllObjects(
                        DBRoomModel.roomId.notEq("server")
                            .and(DBRoomModel.roomName.notNull())
                            .and(DBRoomModel.roomName.notEq(""))
                            .and(DBRoomModel.lastActiveTime.notEq(0L))
                    )
                    L.d { "[ChatList] latestRoomModelsFlow queried ${newRooms.size} rooms" }
                    newRooms
                }
            }
            .sampleAfterFirst(500)
            .flowOn(Dispatchers.IO)
    }

    private val updateTime by lazy {
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(1.minutes)
            }
        }
    }

    /** Every visible room, unfiltered (tab / launcher badge sums read [RoomViewData.isMuted]). */
    val allRecentRoomsStateFlow = MutableStateFlow(emptyList<RoomViewData>())

    /** What the chat list assembles from: [allRecentRoomsStateFlow]'s rooms plus the focus inputs, published together. */
    val chatListSource = MutableStateFlow(ChatListSource())

    private var lastLoggedRoomCount = -1

    val instantCallRoomViewData: MutableMap<String, RoomViewData> = mutableMapOf()

    @Inject
    fun initLoadAndKeepObserving() {
        L.i { "[ChatList] initLoadAndKeepObserving" }
        // ✅ 分开处理：room变化时查询room，draft变化时查询draft
        combine(
            latestRoomModelsFlow,
            draftRepository.allDraftsFlow.onStart { emit(emptyMap()) },
            focusRepository.effective,
            deletedRowStore.deletedGids,
        ) { roomModels, allDrafts, focus, deletedGids ->
            RoomInputs(roomModels, allDrafts, focus, deletedGids)
        }.sampleAfterFirst(500).onEach { (roomModels, allDrafts, focus, deletedGids) ->
            val lang = language.language
            val signature = TimeFormatter.formatSignature(lang)

            val finalRoomList = buildList {
                // 添加常规房间数据
                addAll(roomModels.map {
                    val lastActiveTimeText = if (it.lastActiveTime == 0L) "" else TimeFormatter.formatConversationTime(
                        lang,
                        it.lastActiveTime
                    )
                    val callData = callDataManager.getCallDataByConversationId(it.roomId)
                    val activeTime = callData?.createdAt ?: it.lastActiveTime
                    val isOneOnOne = it.roomType == 0
                    val remarkAvatarJson = if (isOneOnOne) {
                        ContactRemarkCache.getRemarkAvatar(it.roomId)?.takeIf { json -> json.isNotEmpty() }
                    } else {
                        null
                    }
                    RoomViewData(
                        roomId = it.roomId,
                        type = if (isOneOnOne) RoomViewData.Type.OneOnOne else RoomViewData.Type.Group,
                        roomName = it.roomName,
                        roomAvatarJson = it.roomAvatarJson,
                        remarkAvatarJson = remarkAvatarJson,
                        lastDisplayContent = it.lastDisplayContent,
                        lastActiveTime = activeTime,
                        lastActiveTimeText = lastActiveTimeText,
                        unreadMessageNum = it.unreadMessageNum,
                        isManualUnread = isManualUnreadActive(it.markUnreadFlag, it.markUnreadAt, it.lastActiveTime, it.readPosition),
                        muteStatus = it.muteStatus,
                        pinnedTime = it.pinnedTime,
                        mentionType = it.mentionType,
                        criticalAlertType = it.criticalAlertType,
                        sendStatus = it.sendStatus,
                        sendingStatus = it.sendingStatus,
                        messageExpiry = it.messageExpiry,
                        callData = callData,
                        draftPreview = allDrafts[it.roomId]?.draft?.content,
                        sortTime = maxOf(activeTime, allDrafts[it.roomId]?.updatedAt ?: 0L),
                        groupMembersNumber = it.groupMembersNumber,
                        effectiveMuted = ChatListAssembler.effectiveMuted(isGroup = !isOneOnOne, roomId = it.roomId, focus = focus),
                    )
                })

                // 添加即时通话房间数据
                if (instantCallRoomViewData.isNotEmpty()) {
                    addAll(instantCallRoomViewData.values)
                }
            }.sortedByDescending { it.sortTime }

            val focusGid = focus.focusGid?.takeIf { focus.isFocusedGroup(it) }
            val focusIndex = focusGid?.let { gid -> finalRoomList.indexOfFirst { it.roomId == gid } } ?: -1
            val rooms = if (focusGid != null && focusIndex >= 0) {
                finalRoomList.toMutableList().also { it[focusIndex] = it[focusIndex].copy(focusPreviewLines = focusChatRooms.previewLines(focusGid)) }
            } else {
                finalRoomList
            }
            val synthesized = if (focusGid != null && focusIndex < 0 && focusGid !in deletedGids) focusChatRooms.synthesize(focusGid) else null
            chatListSource.value = ChatListSource(
                rooms, focus, synthesized, deletedGids, isLoaded = true, epochDay = LocalDate.now().toEpochDay(),
            )
            allRecentRoomsStateFlow.value = rooms
            formatTracker.markFormatted(signature)
            // The flow re-emits every minute (updateTime ticker) and on every room table
            // change; only a size change is worth a line.
            if (finalRoomList.size != lastLoggedRoomCount) {
                lastLoggedRoomCount = finalRoomList.size
                L.i { "[ChatList] rooms=${finalRoomList.size} drafts=${allDrafts.size}" }
            }
        }.flowOn(Dispatchers.IO).launchIn(viewModelScope)
    }

    /**
     * Re-formats the list when the language, 12/24-hour setting or time zone changed since it was
     * last formatted (#1395). Re-reads the 12/24-hour setting first, off the main thread, so the
     * check does not race the app-foreground refresh.
     */
    fun refreshIfFormatChanged() {
        viewModelScope.launch(Dispatchers.IO) {
            TimeFormatter.refreshHourFormat()
            if (formatTracker.needsRefresh()) formatRefresh.tryEmit(Unit)
        }
    }

    fun createNote() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbRoomStore.createRoomIfNotExist(For.Account(globalServices.myId))
                RoomChangeTracker.trackRoom(globalServices.myId, RoomChangeType.REFRESH)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                L.e { "createNote error: ${error.stackTraceToString()}" }
            }
        }
    }


    fun retrieveCallingList() {
        L.d { "[Call] RecentChatViewModel retrieveCallingList" }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = callService.getCallingList()
                if(response.status == 0) {
                    L.d { "[Call] RecentChatViewModel retrieve calling list from server: ${response.status}, ${response.data?.calls}" }
                    val joinAbleCalls = response.data?.calls
                    if (joinAbleCalls?.isNotEmpty() == true) {
                        viewModelScope.launch(Dispatchers.IO) {
                            joinAbleCalls.forEach { call ->
                                call.source = CallDataSourceType.SERVER
                                when (call.type) {
                                    CallType.ONE_ON_ONE.type -> {
                                        call.conversation?.let { conversation ->
                                            call.callName = contactorCacheManager.getDisplayName(conversation) ?: conversation
                                        }
                                    }

                                    CallType.GROUP.type -> {
                                        call.conversation?.let { conversation ->
                                            val group = groupUtil.get().getSingleGroupInfo(conversation)
                                            if (group != null) {
                                                call.callName = group.name
                                            } else {
                                                call.type = CallType.INSTANT.type
                                                call.conversation = null
                                                call.callName = getString(com.difft.android.call.R.string.call_instant_call_title_default)
                                            }
                                        }
                                    }

                                    CallType.INSTANT.type -> {
                                        val callerId = call.caller.uid
                                        val displayName = contactorCacheManager.getDisplayName(callerId)
                                        call.callName = if (!TextUtils.isEmpty(displayName)) {
                                            "${displayName}${getString(com.difft.android.call.R.string.call_instant_call_title)}"
                                        } else {
                                            getString(com.difft.android.call.R.string.call_instant_call_title_default)
                                        }
                                    }

                                    else -> {
                                    }
                                }
                                callDataManager.addCallData(call)
                            }
                        }
                    } else {
                        callDataManager.clearAllCallData()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                L.w { "[Call] retrieveCallingList failed ${error.javaClass.simpleName}: ${error.message}" }
            }
        }
    }

    fun pinChattingRoom(isPinnedItem: Boolean, data: RoomViewData) {
        val channelId = data.roomId
        val isGroupId = !channelId.startsWith("+")
        val forWhat = if (isGroupId) {
            For.Group(channelId)
        } else {
            For.Account(channelId)
        }

        viewModelScope.launch {
            try {
                if (isGroupId) {
                    // Records who owns the pin (user vs. focus) and makes a stand-in row real first.
                    focusPinCoordinator.setGroupPin(channelId, isPinnedItem)
                } else {
                    withContext(Dispatchers.IO) { dbRoomStore.updatePinnedTime(forWhat, if (isPinnedItem) System.currentTimeMillis() else null) }
                }
                L.i { "Successfully pin item $isPinnedItem $channelId" }
                ToastUtil.show(if (isPinnedItem) com.difft.android.chat.R.string.chat_toast_pinned else com.difft.android.chat.R.string.chat_toast_unpinned)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.i { "Error updating pin item $isPinnedItem $channelId $e" }
            }
        }
    }

    /**
     * The user deleted a conversation from the list. A focus group's deletion is recorded first (local
     * only), so the row is not synthesized again the moment the room disappears, and the whole step
     * runs serialized with the pin coordinator so a reconcile cannot recreate the row in between.
     */
    fun deleteChat(data: RoomViewData) {
        // App scope: the deletion is confirmed already, so closing the screen (e.g. the folded list
        // finishing when focus ends) must not cancel it.
        appScope.launch {
            try {
                val remove: suspend () -> Unit = {
                    withContext(Dispatchers.IO) { ApplicationDependencies.getMessageStore().removeRoomAndMessages(data.roomId) }
                }
                if (data.type == RoomViewData.Type.Group) {
                    focusPinCoordinator.serialized {
                        deletedRowStore.markDeleted(data.roomId)
                        remove()
                    }
                } else {
                    remove()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatList] delete chat failed error=${e.javaClass.simpleName}" }
            }
        }
    }

    fun markAsUnread(data: RoomViewData) {
        unreadMarker.get().markAsUnread(data.toFor())
    }

    fun markAsRead(data: RoomViewData) {
        viewModelScope.launch {
            unreadMarker.get().markAsRead(data.toFor())
        }
    }

    private fun RoomViewData.toFor(): For =
        if (type == RoomViewData.Type.Group) For.Group(roomId) else For.Account(roomId)

    fun markAllAsRead(activity: Activity) {
        viewModelScope.launch(Dispatchers.Main) {
            ComposeDialogManager.showWait(activity, "")

            try {
                withContext(Dispatchers.IO) {
                    // Same per-room path as "Mark as read": read receipts + read-position sync + READ flag
                    // sync, so other devices clear too (the old local-only reset never synced).
                    wcdb.room.allObjects
                        .filter { it.unreadMessageNum > 0 || isManualUnreadActive(it.markUnreadFlag, it.markUnreadAt, it.lastActiveTime, it.readPosition) }
                        .forEach {
                            val room = if (it.roomType == 0) For.Account(it.roomId) else For.Group(it.roomId)
                            unreadMarker.get().markAsRead(room)
                            RoomChangeTracker.trackRoom(it.roomId, RoomChangeType.REFRESH)
                        }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[MarkUnread] markAllAsRead failed: ${e.stackTraceToString()}" }
            } finally {
                ComposeDialogManager.dismissWait()
            }

            delay(1000)
            // Zero-reset on the same field the FCM increment path uses, so
            // "mark all as read" reliably zeroes the badge count.
            globalServices.userManager.update { unreadMsgNum = 0 }
            appIconBadgeManager.get().updateAppIconBadgeNum(0)
            messageNotificationUtil.get().cancelAllNotifications()
        }
    }
}