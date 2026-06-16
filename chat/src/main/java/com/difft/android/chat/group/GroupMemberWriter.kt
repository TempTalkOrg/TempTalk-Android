package com.difft.android.chat.group

import com.difft.android.base.log.lumberjack.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single write entry point for full-overwrite group member sync (W1, W3).
 * Inherits per-uid `signatureVerify` so a server-supplied list cannot wipe
 * verified state. No transaction — WAL keeps readers on a pre-write snapshot;
 * the residual race costs at most one extra verify pass, no correctness loss.
 */
@Singleton
class GroupMemberWriter @Inject constructor() {

    /**
     * @param gid           Must not be empty (W5 stub-row contract guard).
     * @param serverMembers Caller-supplied `signatureVerify` is overwritten —
     *                      this method owns inheritance.
     */
    suspend fun replaceAllForGroup(gid: String, serverMembers: List<GroupMemberContactorModel>) {
        require(gid.isNotEmpty()) {
            "GroupMemberWriter.replaceAllForGroup: gid must not be empty (W5 stub-row contract guard)"
        }
        withContext(Dispatchers.IO) {
            try {
                // signatureVerify is null/true two-state; WCDB-KSP reads a NULL column back as
                // `false` (#901), which re-inserted as 0 would permanently exclude a pending
                // member from the `signatureVerify IS NULL` re-verify query. Coerce false → null.
                val oldVerifyMap: Map<String, Boolean?> = wcdb.groupMemberContactor
                    .getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))
                    .associate { (it.id ?: "") to it.signatureVerify?.takeIf { v -> v } }

                serverMembers.forEach { m ->
                    val uid = m.id ?: return@forEach
                    m.signatureVerify = oldVerifyMap[uid] // null for new uids
                }

                wcdb.groupMemberContactor.deleteObjects(DBGroupMemberContactorModel.gid.eq(gid))
                if (serverMembers.isNotEmpty()) {
                    wcdb.groupMemberContactor.insertObjects(serverMembers)
                }
                L.i { "[GroupMemberWriter] replaced gid=$gid count=${serverMembers.size}" }
            } catch (e: CancellationException) {
                // Coroutine cancellation — must propagate, not swallow.
                throw e
            } catch (e: Exception) {
                // Partial-write surface (delete succeeded but insert failed) is rare but
                // observable: the table is left empty until the next sync. Log here for
                // diagnosability, then rethrow so the caller's outer catch can react
                // (e.g., return null from fetchAndSaveSingleGroupInfo, abort notify-batch
                // processing) instead of marching on as if the write succeeded.
                L.e { "[GroupMemberWriter] replaceAllForGroup failed gid=$gid: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }
}
