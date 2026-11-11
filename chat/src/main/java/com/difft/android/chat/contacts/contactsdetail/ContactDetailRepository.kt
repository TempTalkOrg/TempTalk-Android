package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.weakcontact.WeakContactCountdown
import com.difft.android.chat.common.AvatarCacheCipher
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Input
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.UrlManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.cache.ContactRemarkCache
import org.difft.app.database.getCommonGroupsCount
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBRoomModel
import org.difft.app.database.models.PublicAccountType
import java.io.File
import javax.inject.Inject


class ContactDetailRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: WCDB,
    private val pending: PendingRemovalContactRepository,
    private val urlManager: UrlManager,
    private val weakContactReconciler: com.difft.android.chat.contacts.WeakContactReconciler,
) {
    fun isSelf(id: String): Boolean = id.isNotEmpty() && id == globalServices.myId

    




    suspend fun load(input: Input, serverContact: ContactorModel? = null, fetched: ContactorModel? = null): ContactDetailUiState =
        withContext(Dispatchers.IO) {
        val expiry = pending.getAllExpireAt()[input.contactId]
        val weak = expiry != null
        val local = database.contactor.getFirstObject(DBContactorModel.id.eq(input.contactId))
        val friend = local != null && !weak
        val contact = serverContact ?: if (friend) local else {
            
            
            val row = database.getContactorFromAllTable(input.contactId)
            fetched?.fillGapsOf(row) ?: row
        } ?: ContactorModel().apply {
            id = input.contactId
            name = input.contactName
        }
        input.avatar?.takeIf { it.isNotEmpty() }?.let { contact.avatar = it }
        input.joinedAt?.takeIf { it.isNotEmpty() }?.let { contact.joinedAt = it }
        val self = isSelf(input.contactId)
        val official = contact.id.isOfficialAccount()
        val remark = ContactRemarkCache.getRemark(contact.id)?.takeIf { it.isNotEmpty() }
            ?: contact.remark?.takeIf { it.isNotEmpty() }
            ?: contact.groupMemberContactor?.remark?.takeIf { it.isNotEmpty() }
        val remarkAvatar = ContactRemarkCache.getRemarkAvatar(contact.id)?.takeIf { it.isNotEmpty() }
            ?: contact.remarkAvatar?.takeIf { it.isNotEmpty() }
            ?: contact.groupMemberContactor?.remarkAvatar?.takeIf { it.isNotEmpty() }
        val commonGroups = if (self || official) 0 else runCatching {
            database.getCommonGroupsCount(input.contactId, globalServices.myId)
        }.getOrElse {
            L.e { "[ContactCard] common_groups_failed error=${it.stackTraceToString()}" }
            0
        }
        val messageExpiry = runCatching {
            database.room.getFirstObject(DBRoomModel.roomId.eq(input.contactId))?.messageExpiry ?: 0L
        }.getOrElse {
            L.e { "[ContactCard] message_expiry_failed error=${it.stackTraceToString()}" }
            0L
        }
        ContactDetailUiState(
            contactor = contact,
            isFriend = friend,
            isRelationResolved = true,
            isWeakPending = weak,
            isSelf = self,
            isOfficialAccount = official,
            displayName = remark ?: contact.getDisplayNameForUI(),
            originalName = if (remark != null) contact.getDisplayNameWithoutRemarkForUI() else null,
            hasRemark = remark != null,
            hasRemarkAvatar = remarkAvatar != null,
            originalAvatarJson = if (remarkAvatar != null) contact.avatar else null,
            userId = contact.customUid?.takeIf { it.isNotEmpty() }
                ?: input.customId?.takeIf { it.isNotEmpty() } ?: contact.id.formatBase58Id(false),
            joinedAt = contact.joinedAt,
            sourceDescribe = contact.sourceDescribe.takeUnless { self },
            commonGroupsCount = commonGroups,
            website = if (official) urlManager.installationGuideUrl else null,
            pendingRemovalDays = expiry?.let(WeakContactCountdown::daysLeftFromClock),
            messageExpirySeconds = messageExpiry,
            presentation = contactCardPresentation(true, friend, weak, self, official),
        )
    }

    



    @VisibleForTesting
    internal fun ContactorModel.fillGapsOf(row: ContactorModel?): ContactorModel = ContactorModel().also { merged ->
        fun String?.or(fetched: String?): String? = takeUnless { it.isNullOrEmpty() } ?: fetched
        merged.id = row?.id?.takeIf { it.isNotEmpty() } ?: id
        merged.name = row?.name.or(name)
        merged.email = row?.email.or(email)
        merged.avatar = row?.avatar.or(avatar)
        merged.meetingVersion = row?.meetingVersion ?: meetingVersion
        merged.publicName = row?.publicName.or(publicName)
        merged.timeZone = row?.timeZone.or(timeZone)
        merged.joinedAt = row?.joinedAt.or(joinedAt)
        merged.sourceDescribe = row?.sourceDescribe.or(sourceDescribe)
        merged.findyouDescribe = row?.findyouDescribe.or(findyouDescribe)
        merged.customUid = row?.customUid.or(customUid)
        merged.publicAccountType = row?.publicAccountType?.takeIf { it != PublicAccountType.NORMAL } ?: publicAccountType
        merged.remark = row?.remark
        merged.remarkAvatar = row?.remarkAvatar
        merged.groupMemberContactor = row?.groupMemberContactor
    }

    suspend fun refresh(input: Input): ContactorModel? =
        ContactorUtil.fetchContactors(listOf(input.contactId), context).firstOrNull()

    fun notifyContactChanged(id: String) {
        L.i { "[ContactCard] contact_changed id=$id" }
        ContactorUtil.emitContactsUpdate(listOf(id))
    }

    data class AddResult(val status: Int, val reason: String?)

    suspend fun addFriend(input: Input, scope: kotlinx.coroutines.CoroutineScope): AddResult {
        val response = ContactorUtil.fetchAddFriendRequest(context, input.contactId, input.sourceType, input.source)
        if (response.status == 0) ContactorUtil.sendFriendRequestMessage(scope,
            context.getString(com.difft.android.chat.R.string.contact_friend_request),
            difft.android.messageserialization.For.Account(input.contactId))
        return AddResult(response.status, response.reason)
    }

    suspend fun removeNow(id: String): Result<Unit> = weakContactReconciler.removeNow(id)

    suspend fun ensureOriginalAvatarCached(contact: ContactorModel) = withContext(Dispatchers.IO) {
        val data = contact.avatar?.getContactAvatarData() ?: return@withContext
        val url = data.getContactAvatarUrl() ?: return@withContext
        val file = File(FileUtil.getAvatarCachePath(), "avatar_${url.substringAfterLast('/')}")
        if (file.exists()) return@withContext
        L.i { "[ContactCard] original_avatar_fetch id=${contact.id}" }
        try {
            AvatarCacheCipher.writeEncrypted(file, AvatarUtil.fetchAvatar(context, url, data.encKey ?: ""))
            L.i { "[ContactCard] original_avatar_cached id=${contact.id}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[ContactCard] avatar_cache_failed error=${e.stackTraceToString()}" }
        }
    }
}
