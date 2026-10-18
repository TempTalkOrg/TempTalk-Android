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

/**
 * The single place where a server conversation-config snapshot reaches the room table. Every
 * source (full pull, notify type 4, `conversation/set` echo, a [com.difft.android.chat.jobs.SettingSyncJob]
 * finishing) goes through here so the gating lives once:
 *
 * 1. a push the local device itself triggered is skipped;
 * 2. version gate: fields are written only from a snapshot newer than the one last applied
 *    (an authoritative pull may re-apply the same version);
 * 3. managed fields ([SyncField]) are additionally gated while a local operation is pending:
 *    only a push that changed that field, carrying a server timestamp later than the local
 *    operation, wins ("later operation wins"); pulls and echoes leave it alone.
 *
 * Writes and the new version go out in one UPDATE, so a crash cannot leave the version ahead of
 * the fields. All methods take the room's [SettingSyncState.lockFor] lock; callers must not hold it.
 */
@Singleton
class ConversationSettingsApplier @Inject constructor(
    private val dbRoomStore: DBRoomStore,
    private val syncState: SettingSyncState,
    private val messageArchiveManager: MessageArchiveManager,
    private val settingsManager: Lazy<ConversationSettingsManager>,
) {
    private sealed class Mode {
        data class Normal(val source: SnapshotSource) : Mode()

        /** A job's terminal write-back: [minVersion] is the version its accepted SET produced. */
        data class Final(val jobId: String, val jobTs: Long, val minVersion: Int) : Mode()
    }

    /** What one application wrote: the fields that changed (null when nothing was written). */
    private class Applied(val outcome: FinalOutcome?, val update: ConversationSettingUpdate?)

    /**
     * Applies a snapshot from a pull, push or echo.
     *
     * @param announce emit the [ConversationSettingUpdate] for what was written. The bulk sync
     * passes false: its rows are read on open, and a burst of events would overflow the flow.
     * @return the fields that changed, so a caller holding its own copy can merge them atomically.
     */
    suspend fun applyServerSnapshot(
        snapshot: ServerConfigSnapshot,
        source: SnapshotSource,
        announce: Boolean = true,
    ): ConversationSettingUpdate? = syncState.lockFor(snapshot.conversation).withLock {
        val applied = applyLocked(snapshot, Mode.Normal(source))
        if (announce) applied.update?.let { settingsManager.get().emitConversationSettingUpdate(it) }
        applied.update
    }

    /**
     * Applies the snapshot a finishing job fetched. The managed field is written only when the
     * snapshot is at least [minVersion] and the job is still the newest local intent; see
     * [FinalOutcome].
     */
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
        // Every comparison below is against the version read on entry; the new version is committed last.
        // A snapshot without a version (0) cannot be ordered: it is applied without the version gate
        // and never advances the local version.
        val prev = room.conversationConfigVersion
        val versioned = snapshot.version > 0
        val v = if (versioned) snapshot.version else prev
        // Strictly older snapshots are ignored, pushes included: a push replayed out of order
        // (V11 after V12) would otherwise re-adjudicate a conflict a newer push already settled.
        // The price is the cross-platform rule's accepted edge: a change that only ever reaches
        // this device through a pull (no operation time) lets the local intent win.
        if (mode is Mode.Normal && v < prev) {
            // A pull behind the local version means the server version went backwards: worth a warning.
            if (source is SnapshotSource.Pull) {
                L.w { "[SettingSync] applier staleAuthoritative room=$roomId v=$v applied=$prev" }
            } else {
                L.i { "[SettingSync] applier stale room=$roomId src=${mode.tag()} v=$v applied=$prev" }
            }
            return Applied(null, null)
        }

        // Pull and echo come straight from the server for this conversation; a same-version one may
        // re-apply. A push is only ordered by its version (and its timestamp for the managed field).
        // A final snapshot below the job's accepted version is a lagging read and is trusted for nothing.
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

        // Only columns whose value actually changes are written and announced; a full sync that
        // brings nothing new must not touch (and refresh) every room.
        val expiry = snapshot.messageExpiry?.takeIf { writeOthers }?.let { normalizeExpiry(roomId, it) }
            ?.takeIf { it != room.messageExpiry }
        val mute = snapshot.muteStatus?.takeIf { writeOthers && it != room.muteStatus }
        val block = snapshot.blockStatus?.takeIf { writeOthers && it != room.blockStatus }
        val anchor = snapshot.messageClearAnchor?.takeIf { writeOthers && it != room.messageClearAnchor }
        val newVersion = v.takeIf { it > prev }
        if (listOf<Any?>(mute, block, managedWrite, expiry, anchor, newVersion).any { it != null }) {
            dbRoomStore.updateConversationSettings(
                roomId = roomId,
                muteStatus = mute,
                blockStatus = block,
                confidentialMode = managedWrite,
                messageExpiry = expiry,
                messageClearAnchor = anchor,
                conversationConfigVersion = newVersion,
            )
        }
        val update = ConversationSettingUpdate(
            conversationId = roomId,
            muteStatus = mute,
            blockStatus = block,
            confidentialMode = managedWrite,
            messageExpiry = expiry,
            messageClearAnchor = anchor,
        ).takeIf { listOf<Any?>(mute, block, managedWrite, expiry, anchor).any { it != null } }
        L.i {
            "[SettingSync] applier room=$roomId mode=${mode.tag()} v=$v applied=$prev " +
                "others=$writeOthers managed=$managedWrite outcome=$outcome"
        }
        if (mode is Mode.Final && outcome != FinalOutcome.NEEDS_RECONCILE) finalizeJob(mode, roomId, field)
        return Applied(outcome, update)
    }

    /** Gating for the managed field from a pull, push or echo while nothing or something is pending. */
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
            // Same-version pull is the recovery path when a local write was never enqueued.
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

    /** Mirrors `ConversationSettingsManager`: the self conversation has no expiry, negative means default. */
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
