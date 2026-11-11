package com.difft.android.chat.setting.sync

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.setting.ConversationSettingUpdate
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.Lazy
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
















@Singleton
class ConversationSettingsApplier @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val syncState: SettingSyncState,
    private val messageArchiveManager: MessageArchiveManager,
    private val settingsManager: Lazy<ConversationSettingsManager>,
) {
    private sealed class Mode {
        data class Normal(val source: SnapshotSource) : Mode()

        
        data class Final(val jobId: String, val jobTs: Long, val minVersion: Int) : Mode()
    }

    
    private class Applied(val outcome: FinalOutcome?, val update: ConversationSettingUpdate?)

    






    suspend fun applyServerSnapshot(
        snapshot: ServerConfigSnapshot,
        source: SnapshotSource,
        announce: Boolean = true,
    ): ConversationSettingUpdate? = syncState.lockFor(snapshot.conversation).withLock {
        val applied = applyLocked(snapshot, Mode.Normal(source))
        if (announce) applied.update?.let { settingsManager.get().emitConversationSettingUpdate(it) }
        applied.update
    }

    




    suspend fun applyFinal(snapshot: ServerConfigSnapshot, jobId: String, jobTs: Long, minVersion: Int): FinalOutcome =
        syncState.lockFor(snapshot.conversation).withLock {
            val applied = applyLocked(snapshot, Mode.Final(jobId, jobTs, minVersion))
            applied.update?.let { settingsManager.get().emitConversationSettingUpdate(it) }
            applied.outcome ?: FinalOutcome.COMPLETED
        }

    private suspend fun applyLocked(snapshot: ServerConfigSnapshot, mode: Mode): Applied {
        val roomId = snapshot.conversation
        val field = SyncField.CONFIDENTIAL_MODE
        val room = dbRoomStore.getRoom(roomId)
        if (room == null) {
            L.w { "[SettingSync] applier roomGone room=$roomId mode=${mode.tag()}" }
            if (mode is Mode.Final) finalizeJob(mode, roomId, field)
            return Applied(null, null)
        }
        val source = (mode as? Mode.Normal)?.source
        if (source is SnapshotSource.Push && isOwnPush(source)) {
            L.i { "[SettingSync] applier skipOwnPush room=$roomId v=${snapshot.version}" }
            return Applied(null, null)
        }
        
        
        
        val prev = room.conversationConfigVersion
        val versioned = snapshot.version > 0
        val v = if (versioned) snapshot.version else prev
        
        
        
        
        if (mode is Mode.Normal && v < prev) {
            
            if (source is SnapshotSource.Pull) {
                L.w { "[SettingSync] applier staleAuthoritative room=$roomId v=$v applied=$prev" }
            } else {
                L.i { "[SettingSync] applier stale room=$roomId src=${mode.tag()} v=$v applied=$prev" }
            }
            return Applied(null, null)
        }

        
        
        
        val authoritative = mode is Mode.Final || source is SnapshotSource.Pull || source is SnapshotSource.Echo || !versioned
        val finalThreshold = (mode as? Mode.Final)?.let { max(prev, it.minVersion) }
        val untrusted = finalThreshold != null && v < finalThreshold
        val writeOthers = (v > prev || (authoritative && v == prev)) && !untrusted
        val managedWrite: Int?
        val outcome: FinalOutcome?
        when (mode) {
            is Mode.Final -> {
                when {
                    untrusted -> {
                        managedWrite = null
                        outcome = FinalOutcome.NEEDS_RECONCILE
                    }
                    !isNewestIntent(roomId, field, mode) -> {
                        managedWrite = null
                        outcome = FinalOutcome.SUPERSEDED
                    }
                    else -> {
                        managedWrite = snapshot.confidentialMode?.takeIf { it != room.confidentialMode }
                        outcome = FinalOutcome.COMPLETED
                    }
                }
            }
            is Mode.Normal -> {
                outcome = null
                managedWrite = decideManagedNormal(roomId, field, snapshot, mode.source, prev, v, authoritative)
                    ?.takeIf { it != room.confidentialMode }
            }
        }

        
        
        val expiry = snapshot.messageExpiry?.takeIf { writeOthers }?.let { normalizeExpiry(roomId, it) }
            ?.takeIf { it != room.messageExpiry }
        val mute = snapshot.muteStatus?.takeIf { writeOthers && it != room.muteStatus }
        val block = snapshot.blockStatus?.takeIf { writeOthers && it != room.blockStatus }
        val anchor = snapshot.messageClearAnchor?.takeIf { writeOthers && it != room.messageClearAnchor }
        val allowMe = snapshot.allowMentionMe?.takeIf { writeOthers && it != room.allowMentionMe }
        val allowAll = snapshot.allowMentionAll?.takeIf { writeOthers && it != room.allowMentionAll }
        val allowCall = snapshot.allowGroupCall?.takeIf { writeOthers && it != room.allowGroupCall }
        val newVersion = v.takeIf { it > prev }
        if (listOf<Any?>(mute, block, managedWrite, expiry, anchor, allowMe, allowAll, allowCall, newVersion).any { it != null }) {
            dbRoomStore.updateConversationSettings(
                roomId = roomId,
                muteStatus = mute,
                blockStatus = block,
                confidentialMode = managedWrite,
                messageExpiry = expiry,
                messageClearAnchor = anchor,
                conversationConfigVersion = newVersion,
                allowMentionMe = allowMe,
                allowMentionAll = allowAll,
                allowGroupCall = allowCall,
            )
        }
        val update = ConversationSettingUpdate(
            conversationId = roomId,
            muteStatus = mute,
            blockStatus = block,
            confidentialMode = managedWrite,
            messageExpiry = expiry,
            messageClearAnchor = anchor,
            allowMentionMe = allowMe,
            allowMentionAll = allowAll,
            allowGroupCall = allowCall,
        ).takeIf { listOf<Any?>(mute, block, managedWrite, expiry, anchor, allowMe, allowAll, allowCall).any { it != null } }
        L.i {
            "[SettingSync] applier room=$roomId mode=${mode.tag()} v=$v applied=$prev " +
                "others=$writeOthers managed=$managedWrite outcome=$outcome"
        }
        if (mode is Mode.Final && outcome != FinalOutcome.NEEDS_RECONCILE) finalizeJob(mode, roomId, field)
        return Applied(outcome, update)
    }

    
    private suspend fun decideManagedNormal(
        roomId: String,
        field: SyncField,
        snapshot: ServerConfigSnapshot,
        source: SnapshotSource,
        prev: Int,
        v: Int,
        authoritative: Boolean,
    ): Int? {
        val value = snapshot.confidentialMode ?: return null
        if (!syncState.isPending(roomId, field)) {
            
            return value.takeIf { v > prev || (authoritative && v == prev) }
        }
        if (source !is SnapshotSource.Push || source.changeType != field.changeType) return null
        val localTs = syncState.latestIntentTs(roomId, field) ?: 0L
        val serverWins = source.serverTimestamp > localTs
        L.i { "[SettingSync] applier conflict room=$roomId pushTs=${source.serverTimestamp} localTs=$localTs serverWins=$serverWins" }
        return value.takeIf { serverWins }
    }

    private suspend fun isNewestIntent(roomId: String, field: SyncField, mode: Mode.Final): Boolean {
        val memory = syncState.activeIntent(roomId, field)
        if (memory != null && memory.ts != mode.jobTs) return false
        return syncState.pendingJobs(roomId, field, excludeJobId = mode.jobId).isEmpty()
    }

    private fun finalizeJob(mode: Mode.Final, roomId: String, field: SyncField) {
        syncState.markFinalized(mode.jobId, SettingSyncState.queueKey(roomId, field))
        syncState.clearIntentIf(roomId, field, mode.jobTs)
    }

    private fun isOwnPush(push: SnapshotSource.Push): Boolean =
        push.operator != null && push.operator == globalServices.myId && push.operatorDeviceId == DEFAULT_DEVICE_ID

    
    private fun normalizeExpiry(roomId: String, raw: Long): Long = when {
        roomId == globalServices.myId -> 0L
        raw >= 0 -> raw
        else -> messageArchiveManager.getDefaultMessageArchiveTime()
    }

    private fun Mode.tag(): String = when (this) {
        is Mode.Normal -> when (source) {
            SnapshotSource.Pull -> "pull"
            SnapshotSource.Echo -> "echo"
            is SnapshotSource.Push -> "push"
        }
        is Mode.Final -> "final"
    }
}
