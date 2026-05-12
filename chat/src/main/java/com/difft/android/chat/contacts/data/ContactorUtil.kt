package com.difft.android.chat.contacts.data

import android.content.Context
import android.text.TextUtils
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsremark.ContactRemarkUtil
import com.difft.android.chat.setting.archive.MessageArchiveManager
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
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.cache.ContactRemarkInfo
import org.difft.app.database.convertToContactorModel
import org.difft.app.database.covertToGroupMemberContactorModel
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.base.utils.Base64
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

    private val mContactsUpdateSubject = MutableSharedFlow<List<String>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun emitContactsUpdate(ids: List<String>) {
        mContactsUpdateSubject.tryEmit(ids)
        ids.forEach {
            RoomChangeTracker.trackRoom(it, RoomChangeType.CONTACT)
        }
    }

    val contactsUpdate: SharedFlow<List<String>> = mContactsUpdateSubject

    private val mGetContactsStatusSubject = MutableSharedFlow<Boolean>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun emitGetContactsStatusUpdate(success: Boolean) { mGetContactsStatusSubject.tryEmit(success) }

    val getContactsStatusUpdate: SharedFlow<Boolean> = mGetContactsStatusSubject

    private val mFriendStatusSubject = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun emitFriendStatusUpdate(id: String, isFriend: Boolean) { mFriendStatusSubject.tryEmit(id to isFriend) }

    val friendStatusUpdate: SharedFlow<Pair<String, Boolean>> = mFriendStatusSubject

    // 协程实现
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchAndSaveFlow = MutableSharedFlow<Boolean>(replay = 1) // replay=1 缓存最后一个事件，Boolean表示是否强制刷新
    private var isCoroutineInitialized = false


    fun Context.getEntryPoint() = EntryPointAccessors.fromApplication<EntryPoint>(this)

    fun from(contactResponse: ContactResponse): ContactorModel? {
        try {
            val id = contactResponse.number ?: return null

            // Use the unified decode helper so bulk-fetch and single-update paths share
            // the same failure semantics. On null/empty input -> null; on plain string
            // (no '|') -> the string itself; on V1| with a successful decode -> plaintext;
            // on V1| with a decode failure (exception or null result) -> null, never the
            // raw "V1|<base64>" string (which would otherwise be persisted into DB and
            // surfaced as the contact's display name through the cache).
            val remark = when (val result = decodeRemarkV1OrPlain(contactResponse.remark, id)) {
                is RemarkDecodeResult.Success -> result.plain
                RemarkDecodeResult.Failure -> null
            }
            val remarkAvatarPlain = when (val result = decodeRemarkV1OrPlain(contactResponse.remarkAvatar, id)) {
                is RemarkDecodeResult.Success -> result.plain
                RemarkDecodeResult.Failure -> null
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
                this.remarkAvatar = remarkAvatarPlain
                this.joinedAt = contactResponse.joinedAt
                this.sourceDescribe = contactResponse.sourceDescribe
                this.findyouDescribe = contactResponse.findyouDescribe
                this.customUid = contactResponse.customUid
            }
        } catch (e: Exception) {
            L.e(e) { "[ContactorUtil] convert contactResponse data fail:" }
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


    private fun BaseResponse<ContactsDataResponse>.covertToContactors(): List<ContactorModel> =
        this.data
            ?.contacts
            ?.mapNotNull { from(it) }
            ?: emptyList()

    /**
     * get data from db first,then through the network
     */
    suspend fun getContactWithID(context: Context, id: String): Optional<ContactorModel> = withContext(Dispatchers.IO) {
        val local = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(id))
            ?: wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(id))
                ?.convertToContactorModel()
        if (local != null) {
            return@withContext Optional.of(local)
        }
        val contacts = fetchContactors(listOf(id), context)
        if (contacts.isNotEmpty()) {
            Optional.ofNullable(contacts.first())
        } else {
            Optional.empty()
        }
    }


    suspend fun fetchContactors(ids: List<String>, context: Context): List<ContactorModel> =
        fetchContactors(context, ids, SecureSharedPrefsUtil.getBasicAuth())

    private suspend fun fetchContactors(context: Context, ids: List<String>, basicAuth: String): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            val contacts = context
                .getEntryPoint()
                .getHttpClient()
                .httpService
                .fetchContactors(baseAuth = basicAuth, body = ContactsRequestBody(ids.filter { !TextUtils.isEmpty(it) && it != "server" }))
                .covertToContactors()

            if (contacts.isNotEmpty()) {
                val noAvatarFromServer = contacts.filter { it.avatar == null }.map { it.id }
                if (noAvatarFromServer.isNotEmpty()) {
                    L.i { "[ContactorUtil] fetchContactors server response without avatar: $noAvatarFromServer" }
                }

                val list = wcdb.contactor.getAllObjects(DBContactorModel.id.`in`(*contacts.map { it.id }.toTypedArray()))
                val existInContacts = contacts.filter { contact -> list.any { it.id == contact.id } }

                val avatarChanges = existInContacts.mapNotNull { contact ->
                    val local = list.find { it.id == contact.id }
                    val localHas = local?.avatar != null
                    val serverHas = contact.avatar != null
                    if (localHas != serverHas) "${contact.id}:$localHas->$serverHas" else null
                }
                if (avatarChanges.isNotEmpty()) {
                    L.i { "[ContactorUtil] fetchContactors avatar changes: $avatarChanges" }
                }

                wcdb.contactor.deleteObjects(DBContactorModel.id.`in`(existInContacts.map { it.id }))
                wcdb.contactor.insertObjects(existInContacts.toList())

                val notExistInContacts = contacts.filter { contact -> list.none { it.id == contact.id } }
                if (notExistInContacts.isNotEmpty()) {
                    L.i { "[ContactorUtil] fetchContactors notExistInContacts: ${notExistInContacts.map { it.id }}" }
                }

                val contactorOfGroupMember = notExistInContacts.map { contactor -> contactor.covertToGroupMemberContactorModel() }
                wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq("").and(DBGroupMemberContactorModel.id.`in`(contactorOfGroupMember.map { it.id })))
                wcdb.groupMemberContactor.insertObjects(contactorOfGroupMember)

                ContactRemarkCache.putAll(contacts.associate { it.id to ContactRemarkInfo(remark = it.remark, remarkAvatar = it.remarkAvatar) })
                contacts
            } else {
                emptyList()
            }
        } catch (throwable: Exception) {
            L.e(throwable) { "[ContactorUtil] fetch contactors data fail:" }
            emptyList()
        }
    }

    suspend fun fetchAddFriendRequest(context: Context, token: String, contactID: String, sourceType: String? = null, source: String? = null, action: String? = null): BaseResponse<AddContactorResponse> =
        context
            .getEntryPoint()
            .getHttpClient()
            .httpService
            .fetchAddContactor(token, if (!TextUtils.isEmpty(sourceType)) AddContactorRequestBody(contactID, AddContactorSource(sourceType, source), action) else AddContactorRequestBody(contactID, null, action))

    suspend fun fetchRemoveFriend(context: Context, token: String, contactID: String): BaseResponse<Any> = context
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
            L.w(e) { "[ContactorUtil] getFirstChar error:" }
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
            if (globalServices.userManager.getUserData()?.syncedContactsV4 == false) {
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
        coroutineScope.launch { ContactRemarkCache.preload() }
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
                        val contactsResponse = httpService.fetchAllContactors(baseAuth = SecureSharedPrefsUtil.getBasicAuth())

                        val contacts = contactsResponse.data?.contacts?.toMutableList() ?: mutableListOf()
                        val directoryVersion = contactsResponse.data?.directoryVersion ?: 0

                        // 检查是否需要跳过处理
                        val currentVersion = globalServices.userManager.getUserData()?.directoryVersionForContactors ?: 0
                        val isSyncedContacts = globalServices.userManager.getUserData()?.syncedContactsV4 ?: false

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
                                )

                                response.data?.contacts?.firstOrNull()?.let {
                                    contacts.add(it)
                                } ?: run {
                                    contacts.add(ContactResponse(number = officialBotId, name = officialBotName))
                                }
                            } catch (e: Exception) {
                                L.e(e) { "[ContactorUtil] fetch official bot info error:" }
                                contacts.add(ContactResponse(number = officialBotId, name = officialBotName))
                            }
                        }


                        val noAvatarIds = contacts.filter { it.avatar == null }.map { it.number ?: "null" }
                        L.i { "[ContactorUtil] fetchAndSaveContactors total:${contacts.size}, withoutAvatar:${noAvatarIds.size}, ids:$noAvatarIds" }
                        
                        wcdb.contactor.deleteObjects()

                        if (contacts.size > 1000) {
                            L.i { "[ContactorUtil] Large contact list (${contacts.size}), using streaming" }
                            processContactsStreaming(contacts)
                        } else {
                            val allContactEntities = contacts.mapNotNull { from(it) }
                            wcdb.contactor.insertObjects(allContactEntities)
                            ContactRemarkCache.putAll(allContactEntities.associate { it.id to ContactRemarkInfo(remark = it.remark, remarkAvatar = it.remarkAvatar) })
                            L.i { "[ContactorUtil] SaveContactors success:${allContactEntities.size}" }
                            emitContactsUpdate(allContactEntities.map { it.id })
                        }

                        // 数据保存成功后，才更新版本号
                        updateLocalContactDirectoryVersionIfChanged(directoryVersion)
                        L.i { "[ContactorUtil] SaveContactors complete directoryVersion=$directoryVersion" }

                        // 更新状态
                        globalServices.userManager.update {
                            this.syncedContactsV4 = true
                        }

                        L.i { "[ContactorUtil] fetchAndSaveContactors complete" + contacts.size }
                        emitGetContactsStatusUpdate(true)

                    } catch (e: Exception) {
                        L.e(e) { "[ContactorUtil] fetchAndSaveContactors fail:" }
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
            L.w(e) { "[ContactorUtil] processContactorFlow error:" }
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
    fun sendFriendRequestMessage(scope: CoroutineScope, friendRequestText: String, forWhat: For) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val myID = globalServices.myId
                val time = EntryPointAccessors.fromApplication<EntryPoint>(application)
                    .getMessageArchiveManager()
                    .getMessageArchiveTime(forWhat)
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
                    friendRequestText
                )
                val pushTextSendJobFactory = EntryPointAccessors.fromApplication<EntryPoint>(application).getPushTextSendJobFactory()
                ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
            }.onFailure { e ->
                L.w(e) { "[ContactorUtil] sendFriendRequestMessage error:" }
            }
        }
    }

    fun updateRemark(contactId: String, remarkString: String?) {
        when (val result = decodeRemarkV1OrPlain(remarkString, contactId)) {
            is RemarkDecodeResult.Success -> updateContactRemark(result.plain, contactId)
            RemarkDecodeResult.Failure -> Unit // decode failed - preserve local value, do not clear
        }
    }

    private fun updateContactRemark(remark: String?, contactId: String) {
        // Diff short-circuit: skip when cache holds a definitive value matching the new value.
        // We deliberately do NOT short-circuit when normalized is null and cache.get returns null,
        // because a null cache result is ambiguous between "no remark" and "preload not yet
        // completed for this uid" -- in the latter case the DB may still hold an old value that
        // must be cleared.
        val normalized = remark?.takeIf { it.isNotEmpty() }
        if (normalized != null && ContactRemarkCache.getRemark(contactId) == normalized) return

        wcdb.contactor.updateValue(
            normalized,
            DBContactorModel.remark,
            DBContactorModel.id.eq(contactId)
        )
        wcdb.groupMemberContactor.updateValue(
            normalized,
            DBGroupMemberContactorModel.remark,
            DBGroupMemberContactorModel.id.eq(contactId)
        )

        ContactRemarkCache.putRemark(contactId, normalized)
        emitContactsUpdate(listOf(contactId))
    }

    fun updateRemarkAvatar(contactId: String, encryptedRemarkAvatar: String?) {
        when (val result = decodeRemarkV1OrPlain(encryptedRemarkAvatar, contactId)) {
            is RemarkDecodeResult.Success -> updateContactRemarkAvatar(result.plain, contactId)
            RemarkDecodeResult.Failure -> {
                L.w { "[ContactRemark] updateRemarkAvatar decode failure uid=$contactId — keeping local value" }
            }
        }
    }

    private fun updateContactRemarkAvatar(plainAvatarJson: String?, contactId: String) {
        // Diff short-circuit: skip when cache holds a definitive value matching the new value.
        val normalized = plainAvatarJson?.takeIf { it.isNotEmpty() }
        if (normalized != null && ContactRemarkCache.getRemarkAvatar(contactId) == normalized) return

        wcdb.contactor.updateValue(
            normalized,
            DBContactorModel.remarkAvatar,
            DBContactorModel.id.eq(contactId)
        )
        wcdb.groupMemberContactor.updateValue(
            normalized,
            DBGroupMemberContactorModel.remarkAvatar,
            DBGroupMemberContactorModel.id.eq(contactId)
        )

        ContactRemarkCache.putRemarkAvatar(contactId, normalized)
        emitContactsUpdate(listOf(contactId))
    }

    private suspend fun processContactsStreaming(contacts: List<ContactResponse>) = withContext(Dispatchers.IO) {
        wcdb.contactor.deleteObjects()

        val batchSize = 500
        val allContactIds = mutableListOf<String>()
        val remarkUpdates = HashMap<String, ContactRemarkInfo?>()
        var totalWithoutAvatar = 0
        val noAvatarIds = mutableListOf<String>()

        contacts.chunked(batchSize).forEach { batch ->
            val contactEntities = batch.mapNotNull { from(it) }
            contactEntities.filter { it.avatar == null }.forEach {
                totalWithoutAvatar++
                noAvatarIds.add(it.id)
            }
            wcdb.contactor.insertObjects(contactEntities)
            allContactIds.addAll(contactEntities.map { it.id })
            contactEntities.forEach { remarkUpdates[it.id] = ContactRemarkInfo(remark = it.remark, remarkAvatar = it.remarkAvatar) }

            yield()
        }

        ContactRemarkCache.putAll(remarkUpdates)
        L.i { "[ContactorUtil] Streaming complete: total:${allContactIds.size}, withoutAvatar:$totalWithoutAvatar, ids:$noAvatarIds" }
        emitContactsUpdate(allContactIds)
    }

    /**
     * Decodes a V1|-encrypted or plain remark string.
     *
     * Three distinct outcomes:
     *  - input null / empty -> [RemarkDecodeResult.Success] with plain=null (explicit clear)
     *  - input non-empty and decoded successfully -> [RemarkDecodeResult.Success] with non-empty plain
     *  - input non-empty but Base64 / decryption throws -> [RemarkDecodeResult.Failure]
     *    (preserve local value; must NOT be treated as clear)
     *
     * Callers ([updateRemark]) must distinguish Success(null) from Failure:
     * the former writes empty to DB + cache; the latter skips the entry to avoid
     * accidentally clearing an existing remark.
     */
    private fun decodeRemarkV1OrPlain(remarkString: String?, contactId: String): RemarkDecodeResult {
        if (remarkString.isNullOrEmpty()) return RemarkDecodeResult.Success(null)
        val parts = remarkString.split("|")
        if (parts.size <= 1) return RemarkDecodeResult.Success(remarkString)
        return try {
            val paddedString = (contactId + contactId + contactId).padEnd(32, '+')
            val key = paddedString.toByteArray().copyOf(32)
            val decoded = ContactRemarkUtil.decodeRemark(Base64.decode(parts[1]), key)
            // A null result from decodeRemark indicates a silent decryption failure
            // (e.g. corrupted ciphertext that does not throw). Treat it as Failure so
            // the caller preserves the local value rather than surfacing the raw V1|
            // string to the user as a contact name.
            if (decoded == null) {
                L.w { "[ContactorUtil] decodeRemarkV1OrPlain returned null uid=$contactId" }
                RemarkDecodeResult.Failure
            } else {
                RemarkDecodeResult.Success(decoded)
            }
        } catch (e: Exception) {
            L.e(e) { "[ContactorUtil] decodeRemarkV1OrPlain fail uid=$contactId: ${e.stackTraceToString()}" }
            RemarkDecodeResult.Failure
        }
    }

    private sealed interface RemarkDecodeResult {
        /** Decode succeeded; plain may be null (explicit clear) or a non-empty string. */
        data class Success(val plain: String?) : RemarkDecodeResult
        /** Decode failed (Base64 / decryption error). Caller must preserve local value and skip. */
        data object Failure : RemarkDecodeResult
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
        L.e(e) { "[ContactorUtil] parse avatar data fail: $this ===" }
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

fun String.isOfficialBotId(): Boolean {
    return this == ResUtils.getString(R.string.official_bot_id)
}