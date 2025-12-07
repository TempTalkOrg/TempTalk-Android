package com.difft.android.chat.contacts.data

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.text.TextUtils
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.contactsremark.ContactRemarkUtil
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getOrNull
import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.UrlManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.AddContactorRequestBody
import com.difft.android.network.requests.AddContactorSource
import com.difft.android.network.requests.ContactsRequestBody
import com.difft.android.network.responses.AddContactorResponse
import com.difft.android.network.responses.AvatarResponse
import com.difft.android.network.responses.ContactResponse
import com.difft.android.network.responses.ContactsDataResponse
import com.difft.android.websocket.api.messages.Data
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import difft.android.messageserialization.model.TextMessage
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.yield
import org.difft.app.database.convertToContactorModel
import org.difft.app.database.covertToGroupMemberContactorModel
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.Base64
import java.util.Locale
import java.util.Optional

object ContactorUtil {
    @dagger.hilt.EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPoint {
        @ChativeHttpClientModule.Chat
        fun getHttpClient(): ChativeHttpClient

        fun getUrlManager(): UrlManager

        fun getMessageArchiveManager(): MessageArchiveManager

        fun getUserManager(): UserManager

        fun getPushTextSendJobFactory(): PushTextSendJobFactory
    }

    private val mContactsUpdateSubject = PublishSubject.create<List<String>>()

    fun emitContactsUpdate(ids: List<String>) {
        mContactsUpdateSubject.onNext(ids)
        ids.forEach {
            RoomChangeTracker.trackRoom(it, RoomChangeType.CONTACT)
        }
    }

    val contactsUpdate: Observable<List<String>> = mContactsUpdateSubject

    private val mGetContactsStatusSubject = PublishSubject.create<Boolean>()

    private fun emitGetContactsStatusUpdate(success: Boolean) = mGetContactsStatusSubject.onNext(success)

    val getContactsStatusUpdate: Observable<Boolean> = mGetContactsStatusSubject

    private val mFriendStatusSubject = PublishSubject.create<Pair<String, Boolean>>()

    fun emitFriendStatusUpdate(id: String, isFriend: Boolean) = mFriendStatusSubject.onNext(id to isFriend)

    val friendStatusUpdate: Observable<Pair<String, Boolean>> = mFriendStatusSubject

    // 协程实现
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchAndSaveFlow = MutableSharedFlow<Boolean>(replay = 1) // replay=1 缓存最后一个事件，Boolean表示是否强制刷新
    private var isCoroutineInitialized = false


    fun Context.getEntryPoint() = EntryPointAccessors.fromApplication<EntryPoint>(this)

    fun from(contactResponse: ContactResponse): ContactorModel? {
        try {
            val id = contactResponse.number ?: return null

            var remark: String? = null
            val remarkArray = contactResponse.remark?.split("|")
            remarkArray?.let {
                if (it.size > 1) {
                    val paddedString = (id + id + id).padEnd(32, '+')
                    val key = paddedString.toByteArray().copyOf(32)
                    remark = ContactRemarkUtil.decodeRemark(Base64.decode(it[1]), key)
                    if (remark == null) {
                        remark = contactResponse.remark
                    }
                }
            } ?: run {
                remark = contactResponse.remark
            }

            return ContactorModel().apply {
                this.id = id
                this.name = contactResponse.name
                this.email = contactResponse.email
                this.avatar = contactResponse.avatar
                this.meetingVersion = contactResponse.publicConfigs?.meetingVersion ?: 1
                this.publicName = contactResponse.publicConfigs?.publicName
                this.timeZone = contactResponse.timeZone
                this.remark = remark
                this.joinedAt = contactResponse.joinedAt
                this.sourceDescribe = contactResponse.sourceDescribe
                this.findyouDescribe = contactResponse.findyouDescribe
            }
        } catch (e: Exception) {
            L.e { "[ContactorUtil] convert contactResponse data fail:" + e.stackTraceToString() }
        }
        return null
    }

    fun getAvatarStorageUrl(attachmentId: String): String {
        val urlManager = EntryPointAccessors.fromApplication<EntryPoint>(application).getUrlManager()
        return urlManager.getAvatarStorageUrl(attachmentId)
    }

    private const val separator = " > "

    fun getLastPart(text: String?): String {
        return text?.split(separator)?.last() ?: ""
    }


    fun Single<BaseResponse<ContactsDataResponse>>.covertToContactors(): Single<List<ContactorModel>> =
        this.map { contactsResponseBody ->
            contactsResponseBody
                .data
                ?.contacts
                ?.mapNotNull { from(it) }
                ?: emptyList()
        }

    /**
     * get data from db first,then through the network
     */
    fun getContactWithID(context: Context, id: String): Single<Optional<ContactorModel>> =
        Single.fromCallable {
            Optional.ofNullable(
                wcdb.contactor.getFirstObject(DBContactorModel.id.eq(id))
                    ?: wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(id))
                        ?.convertToContactorModel()
            )
        }.concatMap {
            if (it.isPresent) {
                Single.just(it)
            } else {
                fetchContactors(listOf(id), context).map { contacts ->
                    if (contacts.isNotEmpty()) {
                        Optional.ofNullable(contacts.first())
                    } else {
                        Optional.empty()
                    }
                }
            }
        }


    fun fetchContactors(ids: List<String>, context: Context): Single<List<ContactorModel>> =
        fetchContactors(context, ids, SecureSharedPrefsUtil.getBasicAuth())

    private fun fetchContactors(context: Context, ids: List<String>, basicAuth: String): Single<List<ContactorModel>> =
        context
            .getEntryPoint()
            .getHttpClient()
            .httpService
            .fetchContactors(baseAuth = basicAuth, body = ContactsRequestBody(ids.filter { !TextUtils.isEmpty(it) && it != "server" }))
            .covertToContactors()
            .concatMap { contacts ->
                if (contacts.isNotEmpty()) {
                    //只对已经存在于联系人列表中的数据更新，不存在则存入非联系人表（避免好友关系逻辑判断出错）
                    val list = wcdb.contactor.getAllObjects(DBContactorModel.id.`in`(*contacts.map { it.id }.toTypedArray()))

                    val existInContacts = contacts.filter { contact -> list.any { it.id == contact.id } }

                    wcdb.contactor.deleteObjects(DBContactorModel.id.`in`(existInContacts.map { it.id }))
                    wcdb.contactor.insertObjects(existInContacts.toList())

                    val notExistInContacts = contacts.filter { contact -> list.none { it.id == contact.id } }

                    val contactorOfGroupMember = notExistInContacts.map { contactor -> contactor.covertToGroupMemberContactorModel() }
                    wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq("").and(DBGroupMemberContactorModel.id.`in`(contactorOfGroupMember.map { it.id }))) //here need delete the temp contact, not all the contact
                    wcdb.groupMemberContactor.insertObjects(contactorOfGroupMember)
                    Single.just(contacts)
                } else {
                    Single.just(emptyList())
                }
            }
            .onErrorResumeNext { throwable ->
                L.e { "[ContactorUtil] fetch contactors data fail:" + throwable.stackTraceToString() }
                Single.just(emptyList())
            }

    fun fetchAddFriendRequest(context: Context, token: String, contactID: String, sourceType: String? = null, source: String? = null, action: String? = null): Single<BaseResponse<AddContactorResponse>> =
        context
            .getEntryPoint()
            .getHttpClient()
            .httpService
            .fetchAddContactor(token, if (!TextUtils.isEmpty(sourceType)) AddContactorRequestBody(contactID, AddContactorSource(sourceType, source), action) else AddContactorRequestBody(contactID, null, action))

    fun fetchRemoveFriend(context: Context, token: String, contactID: String): Single<BaseResponse<Any>> = context
        .getEntryPoint()
        .getHttpClient()
        .httpService
        .fetchDeleteContact(contactID, token)

    fun getSortLetter(name: String): String {
        if (TextUtils.isEmpty(name)) return "#"
        return try {
            //汉字转换成拼音
            val pinyin = CharacterParser.getSelling(name)
            val sortString = pinyin.substring(0, 1).uppercase(Locale.getDefault())
            // 正则表达式，判断首字母是否是英文字母
            if (sortString.matches(Regex("[A-Z]"))) sortString.uppercase(Locale.getDefault()) else "#"
        } catch (e: Exception) {
            e.printStackTrace()
            "#"
        }
    }

    fun getFirstLetter(name: String?): String {
        var firstLetters = "#"
        if (!TextUtils.isEmpty(name?.trim())) {
            firstLetters = name?.firstOrNull()?.uppercase(Locale.getDefault()).toString()
        }
        return if (firstLetters.matches(Regex("[A-Z\u4e00-\u9fa5]"))) firstLetters.uppercase(Locale.getDefault()) else "#"
    }


    fun fetchAndSaveContactors(forceFetch: Boolean = true) {
        L.d { "[ContactorUtil] want to request data,forceFetch:$forceFetch" }
        if (forceFetch) {
            coroutineScope.launch {
                fetchAndSaveFlow.emit(true)
            }
        } else {
            if (globalServices.userManager.getUserData()?.syncedContactsV2 == false) {
                coroutineScope.launch {
                    fetchAndSaveFlow.emit(false)
                }
            }
        }
    }

    fun init() {
        if (isCoroutineInitialized) {
            L.d { "[ContactorUtil] Already initialized with coroutines, skipping" }
            return
        }

        L.d { "[ContactorUtil] Initializing with coroutines..." }
        setupFetchAndSaveContactorsWithCoroutines()
        isCoroutineInitialized = true
    }

    /**
     * fetch and save contacts to db using coroutines
     * Uses debounce(2000) for frequency control (2 seconds)
     */
    @OptIn(FlowPreview::class)
    private fun setupFetchAndSaveContactorsWithCoroutines() {
        coroutineScope.launch {
            fetchAndSaveFlow
                .debounce(2000) // 频率控制：2秒内只处理最后一次请求
                .collectLatest { forceRefresh ->
                    try {
                        val httpService = application.getEntryPoint().getHttpClient().httpService
                        val contactsResponse = httpService.fetchAllContactors(baseAuth = SecureSharedPrefsUtil.getBasicAuth()).await()

                        val contacts = contactsResponse.data?.contacts?.toMutableList() ?: mutableListOf()
                        val directoryVersion = contactsResponse.data?.directoryVersion ?: 0

                        // 检查是否需要跳过处理
                        val currentVersion = globalServices.userManager.getUserData()?.directoryVersionForContactors ?: 0
                        val isSyncedContacts = globalServices.userManager.getUserData()?.syncedContactsV2 ?: false

                        L.i { "[ContactorUtil] fetchAndSaveContactors total count:" + contacts.size + " - directoryVersion:$directoryVersion, currentVersion:$currentVersion, isSyncedContacts:$isSyncedContacts, forceRefresh:$forceRefresh" }

                        // 跳过处理的条件：
                        // 1. 新版本 <= 当前版本
                        // 2. 且 通讯录已经首次同步过
                        // 3. 且 不是手动强制刷新
                        if (directoryVersion <= currentVersion && isSyncedContacts && !forceRefresh) {
                            emitGetContactsStatusUpdate(true)
                            return@collectLatest
                        }

                        L.i { "[ContactorUtil] Starting to process version: $directoryVersion" }

                        val officialBotId = ResUtils.getString(R.string.official_bot_id)
                        val officialBotName = ResUtils.getString(R.string.official_bot_name)

                        if (contacts.none { it.number == officialBotId }) {
                            try {
                                val response = httpService.fetchContactors(
                                    baseAuth = SecureSharedPrefsUtil.getBasicAuth(),
                                    body = ContactsRequestBody(listOf(officialBotId))
                                ).await()

                                response.data?.contacts?.firstOrNull()?.let {
                                    contacts.add(it)
                                } ?: run {
                                    contacts.add(ContactResponse(number = officialBotId, name = officialBotName))
                                }
                            } catch (e: Exception) {
                                L.e { "[ContactorUtil] fetch official bot info error: ${e.stackTraceToString()}" }
                                contacts.add(ContactResponse(number = officialBotId, name = officialBotName))
                            }
                        }


                        // 保存到数据库
                        wcdb.contactor.deleteObjects()

                        // 根据数据量选择处理方式
                        if (contacts.size > 1000) {
                            // 大量数据使用流式处理
                            L.i { "[ContactorUtil] Large contact list detected (${contacts.size} > 1000), using streaming processing" }
                            processContactsStreaming(contacts)
                        } else {
                            // 少量数据直接处理
                            val allContactEntities = contacts.mapNotNull { from(it) }
                            wcdb.contactor.insertObjects(allContactEntities)
                            val allContactIds = allContactEntities.map { it.id }

                            L.i { "[ContactorUtil] SaveContactors success:" + allContactEntities.size }
                            emitContactsUpdate(allContactIds)
                        }

                        // 数据保存成功后，才更新版本号
                        updateLocalContactDirectoryVersionIfChanged(directoryVersion)
                        L.i { "[ContactorUtil] SaveContactors complete directoryVersion=$directoryVersion" }

                        // 更新状态
                        globalServices.userManager.update {
                            this.syncedContactsV2 = true
                        }

                        L.i { "[ContactorUtil] fetchAndSaveContactors complete" + contacts.size }
                        emitGetContactsStatusUpdate(true)

                    } catch (e: Exception) {
                        e.printStackTrace()
                        L.e { "[ContactorUtil] fetchAndSaveContactors fail: ${e.stackTraceToString()}" }
                        emitGetContactsStatusUpdate(false)
                    }
                }
        }
    }

    private fun updateLocalContactDirectoryVersionIfChanged(directoryVersion: Int) {
        val localDirectoryVersion = globalServices.userManager.getUserData()?.directoryVersionForContactors ?: 0
        if (localDirectoryVersion < directoryVersion) {
            globalServices.userManager.update { directoryVersionForContactors = directoryVersion }
        }
    }


//    /**
//     * 接受好友请求后，构建一条已接受的消息，进行展示
//     */
//    fun createFriendRequestAcceptMessage(messageId: String, accountId: String) {
//        val description = MessageDescription.MessageId(messageId)
//        val messages = ApplicationDependencies.getMessageLoader().loadMessages(For.Account(accountId), description, Date()).blockingGet()
//        if (messages.isNotEmpty()) {
//            val message = messages[0] as NotifyMessage
//            val timeStamp = System.currentTimeMillis()
//            val myID = LoginHelper.requestMyID(application).blockingGet()
//            message.id = timeStamp.toString() + myID.replace("+", "") + DEFAULT_DEVICE_ID
//            message.timeStamp = timeStamp
//            message.systemShowTimestamp = timeStamp
//            val signalNotifyMessage = Gson().fromJson(message.notifyContent, SignalNotifyMessage::class.java)
//            signalNotifyMessage.data?.actionType = SignalNotifyMessage.NOTIFY_ACTION_TYPE_ADD_FRIEND_REQUEST_DONE
//            signalNotifyMessage.showContent = ResUtils.getString(R.string.contact_added)
//            message.notifyContent = Gson().toJson(signalNotifyMessage)
//            ApplicationDependencies.getMessageStore().putMessage(For.Account(accountId), message).blockingAwait()
//        }
//    }

    /**
     * TT
     * 服务端增加限频，非好友每天只能发3条消息，
     * 超出后拒绝，客户端消息发送失败（感叹号），
     * 系统消息提示："You can only send up to 3 messages per day to a user who is not your friend. Please wait for the other party to accept your request."/"非好友每天最多发3条消息，请等待对方接受请求。"
     */
    fun createNonFriendLimitMessage(forWhat: For) {
        val timeStamp = System.currentTimeMillis()
        val myID = globalServices.myId
        val messageId = timeStamp.toString() + myID.replace("+", "") + DEFAULT_DEVICE_ID
        val signalNotifyMessage = TTNotifyMessage(Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_NON_FRIEND_LIMIT), timeStamp, TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL)
        signalNotifyMessage.showContent = ResUtils.getString(R.string.contact_non_friend_limit_tips)
        val message = NotifyMessage(
            messageId,
            For.Account(myID),
            forWhat,
            timeStamp,
            timeStamp,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            0,
            0,
            0,
            0,
            Gson().toJson(signalNotifyMessage)
        )
        ApplicationDependencies.getMessageStore().putMessage(message).subscribeOn(Schedulers.io()).subscribe()
    }

    /**
     * 构建一条对方离线或者账号禁用的消息，进行展示
     */
    @SuppressLint("CheckResult")
    fun createOfflineMessage(forWhat: For, actionType: Int) {
        val timeStamp = System.currentTimeMillis()
        val messageId = timeStamp.toString() + forWhat.id.replace("+", "") + DEFAULT_DEVICE_ID
        val signalNotifyMessage = TTNotifyMessage(Data(actionType, -1, null), timeStamp, TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL)
        signalNotifyMessage.showContent = when (actionType) {
            TTNotifyMessage.NOTIFY_ACTION_TYPE_OFFLINE -> ResUtils.getString(R.string.contact_offline_tips)
            TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_DISABLED -> ResUtils.getString(R.string.contact_account_exception_tips)
            TTNotifyMessage.NOTIFY_ACTION_TYPE_ACCOUNT_UNREGISTERED -> ResUtils.getString(R.string.contact_unregistered_tips)
            else -> ""
        }

        EntryPointAccessors.fromApplication<EntryPoint>(application).getMessageArchiveManager().getMessageArchiveTime(forWhat)
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({ time ->
                val myID = globalServices.myId

                val message = NotifyMessage(
                    messageId,
                    For.Account(myID),
                    forWhat,
                    timeStamp,
                    timeStamp,
                    System.currentTimeMillis(),
                    SendType.Sent.rawValue,
                    time.toInt(),
                    0,
                    0,
                    0,
                    Gson().toJson(signalNotifyMessage)
                )
                ApplicationDependencies.getMessageStore().putMessage(message).subscribeOn(Schedulers.io()).subscribe()
            }, { it.printStackTrace() })
    }

    /**
     * 构建一条**拍摄了一张截图的消息，进行展示
     */
    @SuppressLint("CheckResult")
    fun createScreenShotMessage(messageId: String, userId: String, groupID: String?, expiresInSeconds: Int, notifySequenceId: Long, sequenceId: Long, isMySelf: Boolean = false) {
        val forWhat = groupID?.let { For.Group(groupID) } ?: For.Account(userId)
        getContactWithID(application, userId)
            .compose(RxUtil.getSingleSchedulerComposer())
            .subscribe({
                if (it.isPresent) {
                    val timeStamp = System.currentTimeMillis()
                    val name = if (isMySelf) {
                        ResUtils.getString(R.string.you)
                    } else {
                        it.get().getDisplayNameForUI()
                    }
                    val signalNotifyMessage = TTNotifyMessage(Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_SCREEN_SHOT), timeStamp, TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL)
                    signalNotifyMessage.showContent = ResUtils.getString(R.string.chat_took_a_screen_shot, name)
                    val message = NotifyMessage(
                        messageId,
                        For.Account(userId),
                        forWhat,
                        timeStamp,
                        timeStamp,
                        System.currentTimeMillis(),
                        SendType.Sent.rawValue,
                        expiresInSeconds,
                        notifySequenceId,
                        sequenceId,
                        0,
                        Gson().toJson(signalNotifyMessage)
                    )
                    ApplicationDependencies.getMessageStore().putMessage(message).subscribeOn(Schedulers.io()).subscribe()
                }
            }, { it.printStackTrace() })
    }

    suspend fun createScreenShotMessageNew(
        forWhat: For,
        messageId: String,
        userId: String,
        notifySequenceId: Long,
        sequenceId: Long,
        timestamp: Long,
        systemShowTimestamp: Long,
        expiresInSeconds: Int,
    ): Message? {
        val name = if (userId == globalServices.myId) {
            ResUtils.getString(R.string.you)
        } else {
            runCatching { getContactWithID(application, userId).await().getOrNull()?.getDisplayNameForUI() }.getOrNull() ?: userId.formatBase58Id()
        }
        val signalNotifyMessage = TTNotifyMessage(
            Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_SCREEN_SHOT),
            timestamp,
            TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL
        )
        signalNotifyMessage.showContent = ResUtils.getString(R.string.chat_took_a_screen_shot, name)

        return NotifyMessage(
            messageId,
            For.Account(userId),
            forWhat,
            systemShowTimestamp,
            timestamp,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            expiresInSeconds,
            notifySequenceId,
            sequenceId,
            0,
            Gson().toJson(signalNotifyMessage)
        )
    }

    /**
     * 创建一条reset identity key的notify消息
     */
    suspend fun createResetIdentityKeyMessage(operator: String, forWhat: For, operateTime: Long, messageArchiveTime: Long): NotifyMessage {
        val contactorName = if (operator == globalServices.myId) {
            ResUtils.getString(R.string.you)
        } else {
            getContactWithID(application, operator).await().getOrNull()?.getDisplayNameForUI() ?: forWhat.id.formatBase58Id()
        }
        val messageId = operateTime.toString() + forWhat.id.replace("+", "") + DEFAULT_DEVICE_ID
        val signalNotifyMessage = TTNotifyMessage(Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_RESET_IDENTITY_KEY), operateTime, TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL)
        signalNotifyMessage.showContent = ResUtils.getString(R.string.me_renew_identity_key_notify_tips, contactorName)
        return NotifyMessage(
            messageId,
            forWhat,
            forWhat,
            operateTime,
            operateTime,
            System.currentTimeMillis(),
            SendType.Sent.rawValue,
            messageArchiveTime.toInt(),
            0,
            0,
            0,
            Gson().toJson(signalNotifyMessage)
        )
    }

    /**
     * 创建一条Earlier messages expired的系统消息
     */
    suspend fun createEarlierMessagesExpiredMessage(
        messageRoomId: String,
        messageRoomType: Int,
        messageSystemShowTimestamp: Long,
        messageReadTime: Long,
        messageExpiresInSeconds: Int
    ): MessageModel {
        val operateTime = System.currentTimeMillis()
        val messageId = operateTime.toString() + globalServices.myId.replace("+", "") + DEFAULT_DEVICE_ID
        val signalNotifyMessage = TTNotifyMessage(Data(TTNotifyMessage.NOTIFY_ACTION_TYPE_MESSAGES_EXPIRED), operateTime, TTNotifyMessage.NOTIFY_MESSAGE_TYPE_LOCAL)
        signalNotifyMessage.showContent = ResUtils.getString(R.string.chat_archive_messages_expired)

        return MessageModel().apply {
            id = messageId
            fromWho = globalServices.myId
            roomId = messageRoomId
            roomType = messageRoomType
            systemShowTimestamp = messageSystemShowTimestamp
            timeStamp = operateTime
            readTime = messageReadTime
            expiresInSeconds = messageExpiresInSeconds
            mode = 0
            messageText = globalServices.gson.toJson(signalNotifyMessage)
            type = 2 // Notify
        }
    }

    fun updateContactRequestStatus(contactID: String, isDelete: Boolean = false) {
        try {
            val gson = Gson()
            val type = object : TypeToken<MutableSet<String>>() {}.type
            val userManager = EntryPointAccessors.fromApplication<EntryPoint>(application).getUserManager()
            val set: MutableSet<String> = userManager.getUserData()?.contactRequestStatus?.let {
                gson.fromJson(it, type)
            } ?: mutableSetOf()
            if (isDelete) {
                set.remove(contactID)
            } else {
                set.add(contactID)
            }
            userManager.update {
                this.contactRequestStatus = gson.toJson(set, type)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun hasContactRequest(contactID: String): Boolean {
        val gson = Gson()
        val type = object : TypeToken<MutableSet<String>>() {}.type
        val userManager = EntryPointAccessors.fromApplication<EntryPoint>(application).getUserManager()
        val set: MutableSet<String> = userManager.getUserData()?.contactRequestStatus?.let {
            gson.fromJson(it, type)
        } ?: mutableSetOf()
        return set.contains(contactID)
    }

    //发送好友请求消息，目前兼容老版本，后面可能移除
    @SuppressLint("CheckResult")
    fun sendFriendRequestMessage(activity: Activity, forWhat: For) {
        try {
            val myID = globalServices.myId
            EntryPointAccessors.fromApplication<EntryPoint>(application).getMessageArchiveManager().getMessageArchiveTime(forWhat)
                .compose(RxUtil.getSingleSchedulerComposer())
                .subscribe({ time ->
                    val timeStamp = System.currentTimeMillis()
                    val messageId = "${timeStamp}${myID.replace("+", "")}${DEFAULT_DEVICE_ID}"

                    val textMessage = TextMessage(
                        messageId,
                        For.Account(myID),
                        forWhat,
                        timeStamp,
                        timeStamp,
                        timeStamp,
                        -1,
                        time.toInt(),
                        0,
                        0,
                        0,
                        activity.getString(R.string.contact_friend_request)
                    )
                    val pushTextSendJobFactory = EntryPointAccessors.fromApplication<EntryPoint>(application).getPushTextSendJobFactory()
                    ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
                }, { it.printStackTrace() })
        } catch (e: Exception) {
            e.printStackTrace()
            L.w { "[ContactorUtil] sendFriendRequestMessage error:" + e.stackTraceToString() }
        }
    }

    fun updateRemark(contactId: String, remarkString: String?) {
        if (remarkString.isNullOrEmpty()) {
            updateContactRemark(null, contactId)
            return
        }
        val remarkArray = remarkString.split("|")
        if (remarkArray.size > 1) {
            try {
                val paddedString = (contactId + contactId + contactId).padEnd(32, '+')
                val key = paddedString.toByteArray().copyOf(32)
                val decodedBytes = Base64.decode(remarkArray[1])
                val remark = ContactRemarkUtil.decodeRemark(decodedBytes, key)
                L.d { "[ContactorUtil] UpdateRemark remark: $remark" }
                updateContactRemark(remark, contactId)
            } catch (e: Exception) {
                L.e { "[ContactorUtil] UpdateRemark fail: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun updateContactRemark(remark: String?, contactId: String) {
        wcdb.contactor.updateValue(
            remark,
            DBContactorModel.remark,
            DBContactorModel.id.eq(contactId)
        )
        wcdb.groupMemberContactor.updateValue(
            remark,
            DBGroupMemberContactorModel.remark,
            DBGroupMemberContactorModel.id.eq(contactId)
        )

        emitContactsUpdate(listOf(contactId))
    }

    /**
     * 流式处理大量联系人数据，减少内存压力
     */
    private suspend fun processContactsStreaming(contacts: List<ContactResponse>) {
        wcdb.contactor.deleteObjects()

        val batchSize = 500 // 每批处理 500 个联系人
        val allContactIds = mutableListOf<String>()

        contacts.chunked(batchSize).forEachIndexed { index, batch ->
            L.d { "[ContactorUtil] Processing batch ${index + 1}/${contacts.size / batchSize + 1}" }

            // 转换当前批次
            val contactEntities = batch.mapNotNull { from(it) }

            // 插入数据库
            wcdb.contactor.insertObjects(contactEntities)

            // 收集 ID
            allContactIds.addAll(contactEntities.map { it.id })

            // 让出协程，避免长时间阻塞
            yield()
        }

        L.i { "[ContactorUtil] Streaming processing complete: ${allContactIds.size} contacts" }
        emitContactsUpdate(allContactIds)
    }
}

object FriendSourceType {
    const val FROM_GROUP = "fromGroup"
    const val SHARE_CONTACT = "shareContact"
    const val RANDOM_CODE = "randomCode"
}

fun String?.getContactAvatarData(): AvatarResponse? {
    return try {
        Gson().fromJson(this, AvatarResponse::class.java)
    } catch (e: Exception) {
        L.e { "[ContactorUtil] parse avatar data fail: $this === ${e.stackTraceToString()}" }
        null
    }
}

fun AvatarResponse.getContactAvatarUrl(): String? {
    this.attachmentId?.let {
        return ContactorUtil.getAvatarStorageUrl(it)
    }
    return null
}

fun String.getFirstLetter(): String {
    return ContactorUtil.getFirstLetter(this)
}

fun String.getSortLetter(): String {
    return ContactorUtil.getSortLetter(this)
}

const val LENGTH_OF_BOT_ID = 6

fun String.isBotId(): Boolean {
    return this.length <= LENGTH_OF_BOT_ID
}