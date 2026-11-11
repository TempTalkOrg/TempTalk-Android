package com.difft.android.chat.contacts.data

import android.content.Context
import android.text.TextUtils
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.application
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.sideBar.CharacterParser
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
import com.google.gson.reflect.TypeToken
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import difft.android.messageserialization.For
import difft.android.messageserialization.model.TextMessage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.cache.ContactRemarkInfo
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.convertToContactorModel
import org.difft.app.database.covertToGroupMemberContactorModel
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PublicAccountType
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.contacts.WeakContactReconciler
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import com.difft.android.messageserialization.db.store.DBMessageStore
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

        fun getWeakContactReconciler(): WeakContactReconciler

        fun getPendingRemovalContactRepository(): PendingRemovalContactRepository

        fun getDBMessageStore(): DBMessageStore
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

    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ContactorUtil") + dbKeyFailSoftExceptionHandler)
    private val fetchAndSaveFlow = MutableSharedFlow<Boolean>(replay = 1) 
    private var isCoroutineInitialized = false

    
    private val firstSyncFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    



    private val firstSyncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    



    private val immediateSyncRequests = Channel<Unit>(Channel.CONFLATED)

    
    private val syncInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    
    private val immediateSyncPending = MutableStateFlow(false)


    fun Context.getEntryPoint() = EntryPointAccessors.fromApplication<EntryPoint>(this)

    fun from(contactResponse: ContactResponse): ContactorModel? {
        try {
            val id = contactResponse.number ?: return null

            
            
            
            
            
            
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
                this.publicAccountType = contactResponse.publicConfigs?.publicAccountType ?: PublicAccountType.NORMAL
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


    


    suspend fun getContactWithID(context: Context, id: String): Optional<ContactorModel> = withContext(Dispatchers.IO) {
        val local = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(id))
            ?: wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(id))
                ?.convertToContactorModel()
        if (local != null) {
            return@withContext Optional.of(local)
        }
        val contacts = fetchContactors(listOf(id), context)
        if (contacts.isNotEmpty()) {
            return@withContext Optional.ofNullable(contacts.first())
        }
        
        
        context.getEntryPoint().getPendingRemovalContactRepository().getSnapshot(id)?.let {
            return@withContext Optional.of(it)
        }
        Optional.empty()
    }


    suspend fun fetchContactors(ids: List<String>, context: Context): List<ContactorModel> = withContext(Dispatchers.IO) {
        try {
            val rawContacts = context
                .getEntryPoint()
                .getHttpClient()
                .httpService
                .fetchContactors(body = ContactsRequestBody(ids.filter { !TextUtils.isEmpty(it) && it != "server" }))
                .data?.contacts ?: emptyList()
            val contacts = rawContacts.mapNotNull { from(it) }

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

                
                
                
                existInContacts.forEach { contact ->
                    val serverValue = rawContacts.find { it.number == contact.id }?.publicConfigs?.publicAccountType
                    val priorValue = list.find { it.id == contact.id }?.publicAccountType
                    contact.publicAccountType = PublicAccountType.resolve(serverValue, priorValue)
                }

                wcdb.contactor.deleteObjects(DBContactorModel.id.`in`(existInContacts.map { it.id }))
                wcdb.contactor.insertObjects(existInContacts.toList())
                existInContacts.forEach { OfficialAccountCache.put(it.id, it.publicAccountType == PublicAccountType.OFFICIAL) }

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
            L.e { "[ContactorUtil] fetch contactors data fail: ${throwable.stackTraceToString()}" }
            emptyList()
        }
    }

    suspend fun fetchAddFriendRequest(context: Context, contactID: String, sourceType: String? = null, source: String? = null, action: String? = null): BaseResponse<AddContactorResponse> =
        context
            .getEntryPoint()
            .getHttpClient()
            .httpService
            .fetchAddContactor(if (!TextUtils.isEmpty(sourceType)) AddContactorRequestBody(contactID, AddContactorSource(sourceType, source), action) else AddContactorRequestBody(contactID, null, action))

    suspend fun fetchRemoveFriend(context: Context, contactID: String): BaseResponse<Any> = context
        .getEntryPoint()
        .getHttpClient()
        .httpService
        .fetchDeleteContact(contactID)

    fun getSortLetter(name: String): String {
        if (TextUtils.isEmpty(name)) return "#"
        return try {
            val pinyin = CharacterParser.getSelling(name)
            val sortString = pinyin.substring(0, 1).uppercase(Locale.getDefault())
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
            if (globalServices.userManager.getUserData()?.syncedContactsV5 == false && !firstSyncInFlight.get()) {
                coroutineScope.launch {
                    fetchAndSaveFlow.emit(false)
                }
            }
        }
    }

    




    suspend fun awaitFirstSync(): Boolean {
        if (globalServices.userManager.getUserData()?.syncedContactsV5 == true) return true
        init()
        return getContactsStatusUpdate
            .onSubscription { if (firstSyncInFlight.compareAndSet(false, true)) firstSyncFlow.emit(true) }
            .first()
    }

    



    suspend fun refreshNow(): Boolean {
        init()
        return getContactsStatusUpdate
            .onSubscription {
                if (!syncInFlight.get() && immediateSyncPending.compareAndSet(false, true)) immediateSyncRequests.trySend(Unit)
            }
            .first()
    }

    @Synchronized
    fun init() {
        if (isCoroutineInitialized) {
            L.d { "[ContactorUtil] Already initialized with coroutines, skipping" }
            return
        }

        L.d { "[ContactorUtil] Initializing with coroutines..." }
        setupFetchAndSaveContactorsWithCoroutines()
        coroutineScope.launch { ContactRemarkCache.preload() }
        coroutineScope.launch { OfficialAccountCache.preload() }
        isCoroutineInitialized = true
    }

    



    @OptIn(FlowPreview::class)
    private fun setupFetchAndSaveContactorsWithCoroutines() {
        coroutineScope.launch {
            merge(
                
                
                fetchAndSaveFlow.debounce(2000).onEach { immediateSyncPending.first { !it } }.map { it to false },
                firstSyncFlow.map { it to false },
                immediateSyncRequests.receiveAsFlow().map { true to true },
            )
                .collectLatest { (forceRefresh, immediate) ->
                    syncInFlight.set(true)
                    try {
                        val httpService = application.getEntryPoint().getHttpClient().httpService
                        val contactsResponse = httpService.fetchAllContactors()

                        val contacts = contactsResponse.data?.contacts?.toMutableList() ?: mutableListOf()
                        val directoryVersion = contactsResponse.data?.directoryVersion ?: 0

                        
                        
                        val serverReturnedFriendList = contactsResponse.data?.contacts != null

                        val currentVersion = globalServices.userManager.getUserData()?.directoryVersionForContactors ?: 0
                        val isSyncedContacts = globalServices.userManager.getUserData()?.syncedContactsV5 ?: false

                        L.i { "[ContactorUtil] fetchAndSaveContactors total count:" + contacts.size + " - directoryVersion:$directoryVersion, currentVersion:$currentVersion, isSyncedContacts:$isSyncedContacts, forceRefresh:$forceRefresh" }

                        if (directoryVersion <= currentVersion && isSyncedContacts && !forceRefresh) {
                            emitGetContactsStatusUpdate(true)
                            return@collectLatest
                        }

                        L.i { "[ContactorUtil] Starting to process version: $directoryVersion" }

                        val noAvatarIds = contacts.filter { it.avatar == null }.map { it.number ?: "null" }
                        L.i { "[ContactorUtil] fetchAndSaveContactors total:${contacts.size}, withoutAvatar:${noAvatarIds.size}, ids:$noAvatarIds" }

                        
                        val oldContactorIds = wcdb.contactor.allObjects.map { it.id }.toSet()
                        
                        
                        
                        val preSyncOfficialIds = OfficialAccountCache.state.value

                        wcdb.contactor.deleteObjects()

                        if (contacts.size > 1000) {
                            L.i { "[ContactorUtil] Large contact list (${contacts.size}), using streaming" }
                            processContactsStreaming(contacts)
                        } else {
                            val allContactEntities = contacts.mapNotNull { from(it) }
                            wcdb.contactor.insertObjects(allContactEntities)
                            ContactRemarkCache.putAll(allContactEntities.associate { it.id to ContactRemarkInfo(remark = it.remark, remarkAvatar = it.remarkAvatar) })
                            OfficialAccountCache.replaceAll(
                                allContactEntities.filter { it.publicAccountType == PublicAccountType.OFFICIAL }.map { it.id }.toSet()
                            )
                            L.i { "[ContactorUtil] SaveContactors success:${allContactEntities.size}" }
                            emitContactsUpdate(allContactEntities.map { it.id })
                        }

                        
                        updateLocalContactDirectoryVersionIfChanged(directoryVersion)
                        L.i { "[ContactorUtil] SaveContactors complete directoryVersion=$directoryVersion" }

                        globalServices.userManager.update {
                            this.syncedContactsV5 = true
                        }

                        L.i { "[ContactorUtil] fetchAndSaveContactors complete" + contacts.size }
                        
                        val reconcileOk = application.getEntryPoint().getWeakContactReconciler().reconcile("fullSync")

                        
                        
                        
                        
                        
                        
                        
                        val newFriendIds = contacts.mapNotNull { it.number }.toSet()
                        val vanishedFriends = oldContactorIds - newFriendIds
                        if (vanishedFriends.isNotEmpty()) {
                            if (!serverReturnedFriendList) {
                                
                                L.w { "[ContactorUtil] fullSync: server returned no friend list (data null), skip room sweep (${vanishedFriends.size} would-be vanished)" }
                            } else if (!reconcileOk) {
                                L.w { "[ContactorUtil] fullSync: reconcile failed, skip mechanism-3 room sweep to avoid false deletions (${vanishedFriends.size} would-be vanished)" }
                            } else {
                                val entryPoint = application.getEntryPoint()
                                val pendingRepo = entryPoint.getPendingRemovalContactRepository()
                                val dbMessageStore = entryPoint.getDBMessageStore()
                                
                                
                                val pendingUids = pendingRepo.getAllExpireAt().keys
                                
                                val officialVanished = vanishedFriends intersect preSyncOfficialIds
                                if (officialVanished.isNotEmpty()) {
                                    L.w { "[ContactorUtil] fullSync: kept official conversation(s) out of room sweep count=${officialVanished.size}" }
                                }
                                val swept = (vanishedFriends - pendingUids - officialVanished).toList()
                                swept.forEach { uid -> dbMessageStore.removeRoomAndMessages(uid) }
                                if (swept.isNotEmpty()) {
                                    L.i { "[ContactorUtil] fullSync swept rooms for vanished non-weak friends: count=${swept.size}" }
                                }
                            }
                        }
                        emitGetContactsStatusUpdate(true)

                    } catch (e: CancellationException) {
                        
                        throw e
                    } catch (e: Exception) {
                        L.e(e) { "[ContactorUtil] fetchAndSaveContactors fail:" }
                        emitGetContactsStatusUpdate(false)
                    } finally {
                        firstSyncInFlight.set(false)
                        syncInFlight.set(false)
                        if (immediate) immediateSyncPending.value = false
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
            val type = object : TypeToken<MutableSet<String>>() {}.type
            val userManager = EntryPointAccessors.fromApplication<EntryPoint>(application).getUserManager()
            val set: MutableSet<String> = userManager.getUserData()?.contactRequestStatus?.let {
                globalServices.gson.fromJson(it, type)
            } ?: mutableSetOf()
            if (isDelete) {
                set.remove(contactID)
            } else {
                set.add(contactID)
            }
            userManager.update {
                this.contactRequestStatus = globalServices.gson.toJson(set, type)
            }
        } catch (e: Exception) {
            L.w(e) { "[ContactorUtil] processContactorFlow error:" }
        }
    }

    fun hasContactRequest(contactID: String): Boolean {
        val type = object : TypeToken<MutableSet<String>>() {}.type
        val userManager = EntryPointAccessors.fromApplication<EntryPoint>(application).getUserManager()
        val set: MutableSet<String> = userManager.getUserData()?.contactRequestStatus?.let {
            globalServices.gson.fromJson(it, type)
        } ?: mutableSetOf()
        return set.contains(contactID)
    }

    
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
            RemarkDecodeResult.Failure -> Unit 
        }
    }

    private fun updateContactRemark(remark: String?, contactId: String) {
        
        
        
        
        
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
        val officialIds = mutableSetOf<String>()
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
            officialIds += contactEntities.filter { it.publicAccountType == PublicAccountType.OFFICIAL }.map { it.id }

            yield()
        }

        ContactRemarkCache.putAll(remarkUpdates)
        OfficialAccountCache.replaceAll(officialIds)
        L.i { "[ContactorUtil] Streaming complete: total:${allContactIds.size}, withoutAvatar:$totalWithoutAvatar, ids:$noAvatarIds" }
        emitContactsUpdate(allContactIds)
    }

    












    private fun decodeRemarkV1OrPlain(remarkString: String?, contactId: String): RemarkDecodeResult {
        if (remarkString.isNullOrEmpty()) return RemarkDecodeResult.Success(null)
        val parts = remarkString.split("|")
        if (parts.size <= 1) return RemarkDecodeResult.Success(remarkString)
        return try {
            val decoded = ContactRemarkUtil.decodeRemark(Base64.decode(parts[1]), ContactRemarkUtil.keyForUid(contactId))
            
            
            
            
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
        
        data class Success(val plain: String?) : RemarkDecodeResult
        
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
        globalServices.gson.fromJson(this, AvatarResponse::class.java)
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