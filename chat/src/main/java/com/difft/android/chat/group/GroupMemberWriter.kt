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
                // Inherit the verified flag only when the member's uidSignature is UNCHANGED.
                // On a key rotation every uidSignature is re-signed under the new pk_bind, so
                // keying inheritance on (uid → old uidSignature) naturally drops the stale
                // verified flag and forces re-verification against the new pk_bind — and is
                // immune to a race with resetSignatureVerify (a concurrent refresh can't
                // re-stamp the old `true`, because the signature no longer matches).
                // signatureVerify is null/true two-state (WCDB-KSP reads NULL as false, #901),
                // so only rows that are explicitly `true` are inheritance candidates.
                val verifiedSigByUid: Map<String, String?> = wcdb.groupMemberContactor
                    .getAllObjects(DBGroupMemberContactorModel.gid.eq(gid))
                    .filter { it.signatureVerify == true }
                    .associate { (it.id ?: "") to it.uidSignature }

                serverMembers.forEach { m ->
                    val uid = m.id ?: return@forEach
                    m.signatureVerify =
                        if (verifiedSigByUid.containsKey(uid) && verifiedSigByUid[uid] == m.uidSignature) true else null
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
