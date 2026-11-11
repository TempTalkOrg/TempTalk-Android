package com.difft.android.call.manager

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactorCacheManager @Inject constructor(
    private val lazyCallToChatController: dagger.Lazy<LCallToChatController>,
    @param:ApplicationContext private val context: Context,
) {
    private val callToChatController: LCallToChatController
        get() = lazyCallToChatController.get()

    private val contactorCache: ConcurrentHashMap<String, CacheEntry> = ConcurrentHashMap()

    private sealed class CacheEntry {
        data class Hit(val contactor: ContactorModel) : CacheEntry()
        data object Miss : CacheEntry()
    }

    

    suspend fun updateCallContactorCache(uidList: List<String>) {
        withContext(Dispatchers.IO) {
            val toUpdate = uidList.filter { uid -> contactorCache.containsKey(uid) }
            toUpdate.forEach { uid ->
                val result = callToChatController.getContactorById(context, uid)
                if (result.isPresent) {
                    contactorCache[result.get().id] = CacheEntry.Hit(result.get())
                } else {
                    contactorCache[uid] = CacheEntry.Miss
                }
            }
        }
    }

    

    suspend fun getDisplayName(id: String?): String? {
        return withContext(Dispatchers.IO) {
            id?.let {
                val userId = it.split(".").firstOrNull() ?: it
                val cached = contactorCache[userId]
                if (cached is CacheEntry.Hit) return@withContext cached.contactor.getDisplayNameForUI()

                val result = callToChatController.getContactorById(context, userId)
                if (result.isPresent) {
                    contactorCache[userId] = CacheEntry.Hit(result.get())
                    return@withContext result.get().getDisplayNameForUI()
                } else {
                    contactorCache[userId] = CacheEntry.Miss
                }

                L.i { "[Call] ContactorCacheManager getDisplayName No displayName found for userId: $userId" }
            }
            return@withContext null
        }
    }

    

    suspend fun getDisplayNameById(id: String?): String? {
        return getDisplayName(id) ?: convertToBase58UserName(id)
    }

    suspend fun getDisplayGroupNameById(id: String?): String? {
        if (id.isNullOrEmpty()) return null
        val group = callToChatController.getSingleGroupInfo(id)
        if (group == null) {
            L.i { "[Call] ContactorCacheManager getDisplayGroupNameById No group name found for id: $id" }
        }
        return group?.name
    }

    



    suspend fun getParticipantDisplayInfo(uid: String): CallUserDisplayInfo =
        withContext(Dispatchers.IO) {
            val userId = uid.split(".").firstOrNull() ?: uid
            val contactor = getContactorByUserId(userId)
            val name = contactor?.getDisplayNameForUI() ?: getDisplayNameById(uid)
            val avatarData: AvatarData = if (contactor != null) {
                AvatarData.FromContactor(contactor)
            } else {
                AvatarData.FromNameOrUid(name, userId)
            }
            L.d { "[ContactorCacheManager] getParticipantDisplayInfo uid=$uid hasContactor=${contactor != null}" }
            CallUserDisplayInfo(uid, name, avatarData)
        }

    

    private suspend fun getContactorByUserId(userId: String): ContactorModel? {
        return withContext(Dispatchers.IO) {
            val entry = contactorCache[userId]
            if (entry is CacheEntry.Hit) return@withContext entry.contactor
            val result = callToChatController.getContactorById(context, userId)
            if (result.isPresent) {
                contactorCache[userId] = CacheEntry.Hit(result.get())
                result.get()
            } else {
                contactorCache[userId] = CacheEntry.Miss
                null
            }
        }
    }

    



    suspend fun getCriticalAlertNotificationContent(
        conversationId: String,
        sourceId: String
    ): Pair<String, String> {
        val title = if (ValidatorUtil.isGid(conversationId)) {
            getDisplayGroupNameById(conversationId)
        } else {
            getDisplayNameById(conversationId)
        }
        val alertTitle = title ?: ResUtils.getString(R.string.notification_critical_alert_title_default)

        val callerName = getDisplayNameById(sourceId)
        val alertContent = callerName?.let {
            ResUtils.getString(R.string.notification_critical_alert_content_from, it)
        } ?: ResUtils.getString(R.string.notification_critical_alert_content_default)

        return alertTitle to alertContent
    }

    

    private val _participantDisplayMap = MutableStateFlow<Map<String, CallUserDisplayInfo>>(emptyMap())
    val participantDisplayMap: StateFlow<Map<String, CallUserDisplayInfo>> = _participantDisplayMap

    private var contactsObserveJob: Job? = null

    
    fun startParticipantObservation(scope: CoroutineScope) {
        if (contactsObserveJob?.isActive == true) return
        L.i { "[Call][ContactorCache] startParticipantObservation started" }
        contactsObserveJob = scope.launch(Dispatchers.IO) {
            callToChatController.getContactsUpdateListener().collect { updatedIds ->
                val currentMap = _participantDisplayMap.value
                val toUpdate = currentMap.keys.filter { uid ->
                    val userId = uid.split(".").firstOrNull() ?: uid
                    updatedIds.contains(userId)
                }
                if (toUpdate.isEmpty()) return@collect
                val participantUserIds = toUpdate.map { uid -> uid.split(".").firstOrNull() ?: uid }
                updateCallContactorCache(participantUserIds)
                val updated = toUpdate.associateWith { uid -> getParticipantDisplayInfo(uid) }
                _participantDisplayMap.update { it + updated }
            }
        }
    }

    suspend fun loadParticipantDisplay(uid: String) {
        val info = getParticipantDisplayInfo(uid)
        _participantDisplayMap.update { it + (uid to info) }
    }

    fun stopParticipantObservation() {
        L.i { "[Call][ContactorCache] stopParticipantObservation called" }
        contactsObserveJob?.cancel()
        contactsObserveJob = null
        _participantDisplayMap.value = emptyMap()
    }

    

    fun clearContactorCache() {
        contactorCache.clear()
    }

    



    fun invalidateUser(uid: String) {
        contactorCache.remove(uid)
    }

    

    private fun convertToBase58UserName(identity: String?): String? {
        val userId = identity?.split(".")?.firstOrNull() ?: return null
        if (!ValidatorUtil.isUid(userId)) {
            L.e { "[ContactorCacheManager] convertToBase58UserName error identity:$identity" }
            return null
        }
        return userId.formatBase58Id()
    }
}

