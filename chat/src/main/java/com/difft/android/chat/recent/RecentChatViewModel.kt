package com.difft.android.chat.recent

import android.app.Activity
import android.content.Context
import android.text.TextUtils
import androidx.lifecycle.viewModelScope
import com.difft.android.base.call.CallDataSourceType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import androidx.lifecycle.ViewModel
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.chat.contacts.data.ContactorUtil.getEntryPoint
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.messageserialization.db.store.DraftRepository
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.signal.DeviceRepository
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.requests.ConversationSetRequestBody
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.updateRoomUnreadState
import org.difft.app.database.wcdb
import com.difft.android.chat.util.AppIconBadgeManager
import com.difft.android.chat.util.MessageNotificationUtil
import util.TimeFormatter
import javax.inject.Inject
import dagger.Lazy
import kotlin.time.Duration.Companion.minutes

@HiltViewModel
class RecentChatViewModel @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    @param:ChativeHttpClientModule.Call
    private val httpClient: Lazy<ChativeHttpClient>,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: Lazy<ChativeHttpClient>,
    private val draftRepository: DraftRepository,
    private val appIconBadgeManager: Lazy<AppIconBadgeManager>,
    private val messageNotificationUtil: Lazy<MessageNotificationUtil>,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    private val contactorCacheManager: ContactorCacheManager,
    private val groupUtil: Lazy<GroupUtil>,
    private val deviceRepository: DeviceRepository,
) : ViewModel() {

    private val language by lazy {
        LanguageUtils.getLanguage(application)
    }

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
            updateTime.map { }  // 每分钟触发查询，作为兜底机制
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
                    L.i { "[ChatList] latestRoomModelsFlow queried ${newRooms.size} rooms" }
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

    val allRecentRoomsStateFlow = MutableStateFlow(emptyList<RoomViewData>())

    val instantCallRoomViewData: MutableMap<String, RoomViewData> = mutableMapOf()

    @Inject
    fun initLoadAndKeepObserving() {
        L.i { "[ChatList] initLoadAndKeepObserving" }
        // ✅ 分开处理：room变化时查询room，draft变化时查询draft
        combine(
            latestRoomModelsFlow,
            draftRepository.allDraftsFlow.onStart { emit(emptyMap()) }
        ) { roomModels, allDrafts ->
            Pair(roomModels, allDrafts)
        }.sampleAfterFirst(500).onEach { (roomModels, allDrafts) ->
            L.i { "[ChatList] Processing conversations: ${roomModels.size}, drafts: ${allDrafts.size}" }

            val finalRoomList = buildList {
                // 添加常规房间数据
                addAll(roomModels.map {
                    val lastActiveTimeText = if (it.lastActiveTime == 0L) "" else TimeFormatter.formatConversationTime(
                        language.language,
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
                    )
                })

                // 添加即时通话房间数据
                if (instantCallRoomViewData.isNotEmpty()) {
                    addAll(instantCallRoomViewData.values)
                }
            }.sortedByDescending { it.sortTime }

            allRecentRoomsStateFlow.value = finalRoomList
            L.i { "[ChatList] allRecentRoomsStateFlow updated: ${allRecentRoomsStateFlow.value.size}" }
        }.flowOn(Dispatchers.IO).launchIn(viewModelScope)
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
                val response = callService.getCallingList((globalServices.userManager.getUserData()?.microToken ?: ""))
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
            } catch (error: Exception) {
                L.e { "[Call] retrieveCallingList error: ${error.message}" }
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
                withContext(Dispatchers.IO) {
                    dbRoomStore.updatePinnedTime(
                        forWhat, if (isPinnedItem) {
                            System.currentTimeMillis()
                        } else {
                            null
                        }
                    )
                }
                L.i { "Successfully pin item $isPinnedItem $channelId" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.i { "Error updating pin item $isPinnedItem $channelId $e" }
            }
        }
    }


    fun muteChannel(
        context: Context,
        channelId: String,
        muteStatus: Int
    ) {
        storeConversionSettingsToServer(
            context = context,
            setting = ConversationSetRequestBody(
                conversation = channelId,
                muteStatus = muteStatus
            )
        )
    }

    private fun storeConversionSettingsToServer(
        context: Context,
        setting: ConversationSetRequestBody
    ) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    context
                        .getEntryPoint()
                        .getHttpClient()
                        .httpService
                        .fetchConversationSet(
                            (globalServices.userManager.getUserData()?.baseAuth ?: ""),
                            setting
                        )
                }
                if (result.status == 0) {
//                    saveMuteStatus(setting.conversation, it.data?.muteStatus ?: 0)
                } else {
                    result.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }

    suspend fun activateDevice(): BaseResponse<Any> {
        return chatHttpClient.get().getService(HttpService::class.java)
            .activateDevice((globalServices.userManager.getUserData()?.baseAuth ?: ""))
    }

    /**
     * Check device auth via Retrofit suspend fun.
     * Replaces blocking PushServiceSocket call.
     * Throws AuthorizationFailedException on 401/403 (handled by caller for logout).
     */
    suspend fun checkDeviceAuth() {
        deviceRepository.checkDeviceAuth()
    }

    fun markAllAsRead(activity: Activity) {
        viewModelScope.launch(Dispatchers.Main) {
            //allRecentRoomsStateFlow.value = allRecentRoomsStateFlow.value.map { it.copy(unreadMessageNum = 0) }

            ComposeDialogManager.showWait(activity, "")

            withContext(Dispatchers.IO) {
                wcdb.room.allObjects.filter { it.unreadMessageNum > 0 }.forEach {
                    it.updateRoomUnreadState(it.lastActiveTime)
                    RoomChangeTracker.trackRoom(it.roomId, RoomChangeType.REFRESH)
                }
            }

            ComposeDialogManager.dismissWait()

            delay(1000)
            // Zero-reset on the same field the FCM increment path uses, so
            // "mark all as read" reliably zeroes the badge count.
            globalServices.userManager.update { unreadMsgNum = 0 }
            appIconBadgeManager.get().updateAppIconBadgeNum(0)
            messageNotificationUtil.get().cancelAllNotifications()
        }
    }
}