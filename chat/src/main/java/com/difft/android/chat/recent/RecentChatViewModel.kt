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
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.SharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.base.viewmodel.DisposableManageViewModel
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
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.requests.ConversationSetRequestBody
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
import io.reactivex.rxjava3.core.Single
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
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDBUpdateService
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.RoomModel
import org.difft.app.database.updateRoomUnreadState
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.util.AppIconBadgeManager
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import util.TimeFormatter
import javax.inject.Inject
import dagger.Lazy
import kotlin.time.Duration.Companion.minutes

@HiltViewModel
class RecentChatViewModel @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    @param:ChativeHttpClientModule.Call
    private val httpClient: ChativeHttpClient,
    @param:ChativeHttpClientModule.Chat
    private val chatHttpClient: ChativeHttpClient,
    private val draftRepository: DraftRepository,
    private val appIconBadgeManager: AppIconBadgeManager,
    private val messageNotificationUtil: MessageNotificationUtil,
    private val callDataManagerLazy: Lazy<CallDataManager>,
    private val contactorCacheManager: ContactorCacheManager
) : DisposableManageViewModel() {

    private val language by lazy {
        LanguageUtils.getLanguage(application)
    }

    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
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
                    RoomViewData(
                        it.roomId,
                        if (it.roomType == 0) RoomViewData.Type.OneOnOne else RoomViewData.Type.Group,
                        it.roomName,
                        it.roomAvatarJson,
                        it.lastDisplayContent,
                        lastActiveTime = callDataManager.getCallDataByConversationId(it.roomId)?.createdAt ?: it.lastActiveTime,
                        lastActiveTimeText,
                        it.unreadMessageNum,
                        it.muteStatus,
                        it.pinnedTime,
                        it.mentionType,
                        it.criticalAlertType,
                        it.messageExpiry,
                        callData = callDataManager.getCallDataByConversationId(it.roomId),
                        draftPreview = allDrafts[it.roomId]?.content,
                        groupMembersNumber = it.groupMembersNumber,
                    )
                })

                // 添加即时通话房间数据
                if (instantCallRoomViewData.isNotEmpty()) {
                    addAll(instantCallRoomViewData.values)
                }
            }.sortedByDescending { it.lastActiveTime }

            allRecentRoomsStateFlow.value = finalRoomList
            L.i { "[ChatList] allRecentRoomsStateFlow updated: ${allRecentRoomsStateFlow.value.size}" }
        }.flowOn(Dispatchers.IO).launchIn(viewModelScope)
    }

    fun createNote() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                dbRoomStore.createRoomIfNotExist(For.Account(globalServices.myId))
                RoomChangeTracker.trackRoom(globalServices.myId, RoomChangeType.REFRESH)
            } catch (error: Exception) {
                L.e { "createNote error: ${error.stackTraceToString()}" }
            }
        }
    }


    fun retrieveCallingList() {
        L.d { "[Call] RecentChatViewModel retrieveCallingList" }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = callService.getCallingList(SecureSharedPrefsUtil.getToken()).await()
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
                                            val groupOptional = GroupUtil.getSingleGroupInfo(application, conversation)
                                                .firstOrError()
                                                .await()
                                            if (groupOptional.isPresent) {
                                                call.callName = groupOptional.get().name
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

        dbRoomStore.updatePinnedTime(
            forWhat, if (isPinnedItem) {
                System.currentTimeMillis()
            } else {
                null
            }
        ).subscribe(
            {
                L.i { "Successfully pin item $isPinnedItem $channelId" }
            },
            { error ->
                L.i { "Error updating pin item $isPinnedItem $channelId $error" }
            }
        ).also {
            manageDisposable(it)
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
        manageDisposable(
            context
                .getEntryPoint()
                .getHttpClient()
                .httpService
                .fetchConversationSet(
                    SecureSharedPrefsUtil.getBasicAuth(),
                    setting
                )
                .compose(RxUtil.getSingleSchedulerComposer())
                .subscribe({
                    if (it.status == 0) {
//                        saveMuteStatus(setting.conversation, it.data?.muteStatus ?: 0)
                    } else {
                        it.reason?.let { message -> ToastUtil.show(message) }
                    }
                }) {
                    it.message?.let { message -> ToastUtil.show(message) }
                }

        )
    }

    //激活设备
    fun activateDevice(): Single<BaseResponse<Any>> {
        return chatHttpClient.getService(HttpService::class.java)
            .activateDevice(SecureSharedPrefsUtil.getBasicAuth())
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
            SharedPrefsUtil.putInt(SharedPrefsUtil.SP_UNREAD_MSG_NUM, 0)
            appIconBadgeManager.updateAppIconBadgeNum(0)
            messageNotificationUtil.cancelAllNotifications()
        }
    }
}