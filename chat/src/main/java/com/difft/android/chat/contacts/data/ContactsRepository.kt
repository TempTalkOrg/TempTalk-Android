package com.difft.android.chat.contacts.data

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.RoomChangeTracker
import androidx.annotation.VisibleForTesting
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.chat.group.GroupUtil
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.WCDB
import kotlinx.coroutines.flow.filter
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.groupMemberCounts
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.roomMessageExpiries

class ContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wcdb: WCDB,
    private val pendingRepository: PendingRemovalContactRepository,
    private val groupUtil: GroupUtil,
) {
    
    private val shownTimers = AtomicReference<Map<String, Long>>(emptyMap())
    private val weakBackfillRunning = AtomicBoolean(false)

    
    @VisibleForTesting
    internal var timerReadDispatcher: CoroutineDispatcher = Dispatchers.IO

    
    fun changes(): Flow<Unit> = merge(
        ContactorUtil.contactsUpdate.sampleAfterFirst(2000).map { Unit },
        groupUtil.getGroupsStatusUpdate.map { Unit },
        
        groupUtil.singleGroupsUpdate.sampleAfterFirst(2000).map { Unit },
        OfficialAccountCache.state.drop(1).map { Unit },
        TextSizeUtil.textSizeState.drop(1).map { Unit },
        timerChanges(),
    )

    




    private fun timerChanges(): Flow<Unit> = RoomChangeTracker.roomChanges
        .map { batch -> batch.filter { it.type == RoomChangeType.REFRESH && it.roomId in shownTimers.get() }.map { it.roomId } }
        .filter { ids ->
            if (ids.isEmpty()) return@filter false
            val shown = shownTimers.get()
            val current = withContext(timerReadDispatcher) { wcdb.roomMessageExpiries(ids) }
            ids.any { (current[it] ?: 0L) != shown[it] }
        }
        .map { Unit }

    @VisibleForTesting
    internal fun rememberShownTimers(ids: Collection<String>, timers: Map<String, Long>) {
        shownTimers.set(ids.associateWith { timers[it] ?: 0L })
    }

    



    suspend fun refreshAll() {
        withContext(Dispatchers.IO) {
            launch { withTimeoutOrNull(CONTACTS_REFRESH_TIMEOUT_MS) { ContactorUtil.refreshNow() } }
            launch { groupUtil.refreshAllNow() }
        }
    }

    suspend fun snapshot(): ContactsSnapshot = withContext(Dispatchers.IO) {
        val friends = wcdb.contactor.allObjects
        val expireAt = pendingRepository.getAllExpireAt()
        val weakUids = expireAt.keys.toList()
        
        val unresolvedWeak = if (weakUids.isEmpty()) emptyList() else {
            val friendIds = friends.map { it.id }.toSet()
            val known = wcdb.groupMemberContactor.getAllObjects(DBGroupMemberContactorModel.id.`in`(weakUids))
                .map { it.id }.toSet()
            weakUids.filter { it !in known && it !in friendIds }
        }
        val weakContacts = if (weakUids.isEmpty()) emptyList() else wcdb.getContactorsFromAllTable(weakUids)
        val deletedAt = wcdb.pendingRemovalContact.allObjects.associate { it.uid to it.deleteTime }
        val groups = wcdb.group.getAllObjects(DBGroupModel.status.eq(0))
        val groupIds = groups.mapNotNull { it.gid }
        
        val memberCounts = wcdb.groupMemberCounts(groupIds)
        val ids = (friends + weakContacts).map { it.id }.toSet()
        val expiries = wcdb.roomMessageExpiries(ids)
        rememberShownTimers(ids, expiries)
        ContactsSnapshot(
            friends = friends,
            weakContacts = weakContacts,
            pending = expireAt.mapValues { (id, at) -> PendingContactMetadata(at, deletedAt[id] ?: 0) },
            groups = groups,
            memberCounts = memberCounts,
            messageExpirySeconds = expiries,
            officialIds = OfficialAccountCache.state.value,
            selfId = globalServices.myId,
            favoritesName = context.getString(com.difft.android.base.R.string.chat_favorites),
            textSize = TextSizeUtil.textSizeState.value,
            unresolvedWeakIds = unresolvedWeak,
        )
    }

    




    suspend fun backfillWeakContacts(ids: List<String>): Boolean {
        if (ids.isEmpty() || !weakBackfillRunning.compareAndSet(false, true)) return false
        return try {
            ContactorUtil.fetchContactors(ids, context).any { it.id in ids }
        } finally {
            weakBackfillRunning.set(false)
        }
    }
}

private const val CONTACTS_REFRESH_TIMEOUT_MS = 15_000L
