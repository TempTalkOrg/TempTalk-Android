package com.difft.android.chat.contacts

import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.weakcontact.WeakContactClock
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.network.HttpService
import com.difft.android.network.responses.DeletedRecordDto
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.PendingRemovalContactRepository
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.PendingRemovalContactModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestration layer for weak contacts (delayed removal). Single owner for all five entry points:
 * [enterWeak] (ct=0/backfill), [removeWeak] (ct=1/real removal), [removeNow] (user action),
 * [clearWeakOnFriendRestored] (directory action=0), and [reconcile] (cold-start/full-sync).
 *
 * Room-delete ownership: [enterWeakLocked] deletes contactor + room (friend-era cleanup, backstop
 * for a missed directory action); [removeWeakLocked] (ct=1) deletes room too; [dropPlaceholderLocked]
 * (friend restored / reconcile vanished) keeps the room. Reconcile vanished never deletes rooms
 * because at cold start the contactor view lags — room deletion is left to real-time ct=1 and the
 * full-sync vanished-friend sweep in ContactorUtil.
 *
 * Concurrency: public entries acquire [mutex]; [reconcile] holds the lock and calls the
 * lock-free *Locked variants to avoid Kotlin Mutex re-entry deadlock.
 */
@Singleton
class WeakContactReconciler @Inject constructor(
    private val pendingRepo: PendingRemovalContactRepository,
    private val dbMessageStore: DBMessageStore,
    private val httpService: HttpService,                       // injected interface (eases @TestInstallIn)
    private val contactorCacheManager: ContactorCacheManager,   // precise invalidation (:chat already depends on :call)
    private val userManager: UserManager,
    private val wcdb: WCDB,
    private val gson: Gson,
) {
    private val mutex = Mutex()   // serializes reconcile / notify entries

    /** Token resolution matches the existing add-friend/contacts APIs (microToken). */
    private fun token(): String = userManager.getUserData()?.microToken ?: ""

    /** Enter weak state (ct=0 / backfill): write placeholder, delete contactor + room. See [enterWeakLocked]. */
    suspend fun enterWeak(uid: String, expireAt: Long, reason: Int, deleteTime: Long, snapshot: ContactorModel) =
        mutex.withLock { enterWeakLocked(uid, expireAt, reason, deleteTime, snapshot) }

    /** Real removal (ct=1): drop placeholder AND delete the room. Idempotent. See [removeWeakLocked]. */
    suspend fun removeWeak(uid: String) = mutex.withLock { removeWeakLocked(uid) }

    /** Friend restored (directory action=0): drop placeholder, KEEP room. Idempotent. See [dropPlaceholderLocked]. */
    suspend fun clearWeakOnFriendRestored(uid: String) = mutex.withLock { dropPlaceholderLocked(uid) }

    /**
     * User "Remove Now": call server DELETE, then [removeWeak] only on success. Pessimistic — dropping
     * the placeholder before server confirmation would let the next reconcile resurrect it (visible flicker).
     */
    suspend fun removeNow(uid: String): Result<Unit> = runCatching {
        httpService.deleteDeletedRecord(uid, token())   // idempotent; on success server pushes changeType=1 to other devices
        removeWeak(uid)                                  // local delete only after success
        Unit
    }.onFailure { L.w { "[WeakContact] removeNow uid=$uid failed (offline?): ${it.stackTraceToString()}" } }

    /**
     * Reconcile (cold start / after full refresh): fetch deletedRecords → gate → write clock anchor →
     * apply diff side-effects → full overwrite.
     *
     * @return true if the weak table was refreshed from a complete server response; false if the fetch
     * failed or was incomplete (data==null) and the weak table was left untouched. Callers that run a
     * downstream room sweep keyed on the weak set (e.g. ContactorUtil mechanism-3) MUST skip the sweep
     * when this returns false, or stale/empty weak data would cause false room deletions.
     */
    suspend fun reconcile(trigger: String): Boolean = mutex.withLock {
        val resp = runCatching { httpService.fetchDeletedRecords(token()) }
            .onFailure { L.w { "[WeakContact] reconcile($trigger) fetch error: ${it.stackTraceToString()}" } }
            .getOrNull()
        // Gate: data==null (server error / undeployed field) → skip to avoid wiping the weak table;
        //       data==[] (legitimately empty) → proceed normally.
        val records = resp?.data
        if (resp == null || !resp.isSuccess() || records == null) {
            L.w { "[WeakContact] reconcile($trigger) fetch incomplete (resp=${resp != null} success=${resp?.isSuccess()} dataNull=${resp?.data == null}), skip" }
            return@withLock false
        }
        val serverNow = resp.serverTimestamp ?: 0L
        WeakContactClock.update(serverNow, SystemClock.elapsedRealtime()) // write the clock anchor
        // Drop records with null/blank uid — gson fills nullable uid with null on key mismatch,
        // which NPEs at cold start. Filtering here guarantees all downstream uids are non-null.
        val validRecords = records.filter { !it.uid.isNullOrBlank() }
        val skipped = records.size - validRecords.size
        if (skipped > 0) {
            L.w { "[WeakContact] reconcile($trigger) skip $skipped records with null/blank uid" }
        }
        val latest = validRecords.associateBy { it.uid!! }               // non-null after the filter above
        val before = pendingRepo.snapshotBeforeOverwrite()                // local weak uids before overwrite
        // Existing weak-table snapshots for the already-weak uids. toContactorSnapshot reads remark
        // from wcdb.contactor, but that row was deleted at enterWeak time, so a re-derived snapshot
        // has no remark — without this backfill, overwriteAll would strip the remark preserved when
        // the uid first entered the weak state.
        val existingSnapshots = pendingRepo.getSnapshots(before.toList())

        // Cache snapshots before overwriteAll so a second DB read is unnecessary.
        val snapshots = HashMap<String, ContactorModel>()
        latest.forEach { (uid, dto) ->
            val snap = dto.toContactorSnapshot(uid)
            // Preserve the remark stored when the uid entered weak state (contactor row already gone).
            if (snap.remark.isNullOrEmpty()) {
                existingSnapshots[uid]?.remark?.let { snap.remark = it }
            }
            snapshots[uid] = snap
            if (uid !in before) {
                // Backfill: missed notify ct=0 or directory action — enterWeakLocked deletes contactor + room.
                enterWeakLocked(uid, dto.expireTime, dto.reason, dto.deleteTime, snap)
            }
            // else: still weak — overwriteAll below refreshes expireAt/snapshot.
            // Leave any coexisting contactor row: server record may lag a just-restored friend;
            // a stale contactor has the full-sync backstop (fetchAndSaveContactors).
        }
        (before - latest.keys).forEach { uid ->
            // Vanished: drop placeholder only — never delete the room here. At cold start the contactor
            // view lags, so we cannot distinguish a restored friend from a genuine removal.
            // Room deletion is handled by real-time ct=1 and the full-sync vanished-friend sweep.
            dropPlaceholderLocked(uid)
        }
        // Full overwrite to refresh expireAt/snapshot.
        val rows = latest.map { (uid, dto) ->
            PendingRemovalContactModel().also {
                it.uid = uid
                it.expireAt = dto.expireTime
                it.reason = dto.reason
                it.deleteTime = dto.deleteTime
                it.snapshotJson = gson.toJson(snapshots.getValue(uid))
            }
        }
        pendingRepo.overwriteAll(rows)                  // wipe + bulk insertOrReplace in one transaction
        L.i { "[WeakContact] reconcile($trigger) done latest=${latest.size} before=${before.size} serverNow=$serverNow" }
        true   // weak table refreshed from a complete response — sweep may proceed
    }

    // ── Lock-free internals — called by reconcile (already holds lock) to avoid Kotlin Mutex re-entry deadlock.

    /**
     * Write the weak placeholder, then hard-delete the contactor + room (friend-era cleanup).
     * Owns the room delete directly so a missed directory action doesn't leave a lingering conversation.
     */
    private suspend fun enterWeakLocked(uid: String, expireAt: Long, reason: Int, deleteTime: Long, snapshot: ContactorModel) = withContext(Dispatchers.IO) {
        L.i { "[WeakContact] enterWeak uid=$uid reason=$reason expireAt=$expireAt" }
        // Preserve existing remark in the snapshot before deleting the contactor row.
        wcdb.contactor.getFirstObject(DBContactorModel.id.eq(uid))?.remark
            ?.let { snapshot.remark = it }
        pendingRepo.upsert(uid, expireAt, reason, deleteTime, snapshot)  // write placeholder (atomic insertOrReplace)
        wcdb.contactor.deleteObjects(DBContactorModel.id.eq(uid))        // demote: drop the friend row
        dbMessageStore.removeRoomAndMessages(uid)                        // clear friend-era chat (backstop for a dropped action)
        invalidateAndEmit(uid)
    }

    /** Real removal (ct=1): drop placeholder AND delete the room. Idempotent. */
    private suspend fun removeWeakLocked(uid: String) {
        L.i { "[WeakContact] removeWeak (real removal) uid=$uid" }
        pendingRepo.remove(uid)                     // drop the weak placeholder
        dbMessageStore.removeRoomAndMessages(uid)   // ct=1 = real removal → clear the conversation too
        invalidateAndEmit(uid)
    }

    /** Drop the weak placeholder ONLY — KEEP the room (friend restored). Idempotent. */
    private suspend fun dropPlaceholderLocked(uid: String) {
        L.i { "[WeakContact] clearWeak (friend restored) uid=$uid" }
        pendingRepo.remove(uid)   // drop the weak placeholder; the room is intentionally left intact
        invalidateAndEmit(uid)
    }

    /** Maps a [DeletedRecordDto] to a [ContactorModel] snapshot, preserving the local remark if present. */
    private fun DeletedRecordDto.toContactorSnapshot(uid: String): ContactorModel = ContactorModel().also {
        it.id = uid
        it.name = name
        it.avatar = avatar
        wcdb.contactor.getFirstObject(DBContactorModel.id.eq(uid))?.remark?.let { rmk -> it.remark = rmk }
    }

    private fun invalidateAndEmit(uid: String) {
        contactorCacheManager.invalidateUser(uid)
        ContactorUtil.emitContactsUpdate(listOf(uid))
    }
}
