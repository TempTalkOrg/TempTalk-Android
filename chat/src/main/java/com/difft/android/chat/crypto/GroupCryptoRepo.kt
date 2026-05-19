package com.difft.android.chat.crypto

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.group.CryptoDisposeReq
import com.difft.android.network.group.GroupRepo
import com.tencent.wcdb.base.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.difft.app.database.models.DBGroupCryptoKeysModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.GroupCryptoKeysModel
import org.difft.app.database.models.GroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of access for group crypto keys (`group_crypto_keys` table) and
 * encrypted-group member signature verification.
 */
@Singleton
class GroupCryptoRepo @Inject constructor() {

    /**
     * Get R_group for a group. Returns Base64-encoded string, or null if not available.
     * Must be called from IO thread.
     */
    fun getRGroup(gid: String): String? {
        return wcdb.groupCryptoKeys.getFirstObject(
            DBGroupCryptoKeysModel.gid.eq(gid)
        )?.rGroup
    }

    /**
     * Get R_group as raw bytes. Returns null if not available.
     * Must be called from IO thread.
     */
    fun getRGroupBytes(gid: String): ByteArray? {
        val rGroupBase64 = getRGroup(gid) ?: return null
        return try {
            Base64.decode(rGroupBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            L.e { "[GroupCryptoRepo] Failed to decode R_group for group $gid: ${e.message}" }
            null
        }
    }

    /**
     * Save R_group if not already present. Idempotent — skips if key already exists.
     * Must be called from IO thread.
     *
     * @return true if key was saved (new), false if already existed
     */
    fun saveRGroupIfNeeded(gid: String, rGroup: ByteArray): Boolean {
        val existing = getRGroup(gid)
        if (existing != null) return false

        val model = GroupCryptoKeysModel().apply {
            this.gid = gid
            this.rGroup = Base64.encodeToString(rGroup, Base64.NO_WRAP)
        }
        // Use insertOrReplace to avoid PK constraint crash from concurrent callers
        // (handleGroupKeyMessage + DataMessage fallback can race on IO dispatcher).
        wcdb.groupCryptoKeys.insertOrReplaceObject(model)
        L.i { "[GroupCryptoRepo] Saved R_group for group $gid" }
        return true
    }

    /**
     * Delete crypto keys for a group. Called when group is disbanded/left/kicked.
     * Must be called from IO thread.
     */
    fun deleteKeys(gid: String) {
        wcdb.groupCryptoKeys.deleteObjects(DBGroupCryptoKeysModel.gid.eq(gid))
        L.i { "[GroupCryptoRepo] Deleted keys for group $gid" }
    }

    /**
     * Check if a group has crypto keys (i.e., we have received the R_group).
     * Must be called from IO thread.
     */
    fun hasKeys(gid: String): Boolean {
        return getRGroup(gid) != null
    }

    /**
     * Verify a member's UID signature. Returns true if valid, false if invalid.
     * Returns null if verification is not possible (no R_group, no signature).
     * Must be called from IO thread.
     */
    fun verifyMember(gid: String, uid: String, uidSignature: String?): Boolean? {
        if (uidSignature.isNullOrEmpty()) return null
        val rGroupBytes = getRGroupBytes(gid) ?: return null
        return try {
            val pkBind = GroupCrypto.derivePkBind(rGroupBytes)
            GroupCrypto.verifyUid(pkBind, uid, uidSignature)
        } catch (e: Exception) {
            L.e { "[GroupCryptoRepo] verifyMember failed for $uid in group $gid: ${e.message}" }
            null
        }
    }

    /**
     * Full verify of all pending (signatureVerify=null) members of a group.
     * Cheap when nothing pending — SQL filter runs first, no derivePkBind cost.
     * Caller-side fetch dedup (`groupsInProgress` in GroupUtil) means this can
     * only be called once per in-flight fetchAndSaveSingleGroupInfo, so no
     * additional time-based throttle here.
     */
    suspend fun verifyAllPendingForGroup(gid: String, groupRepo: GroupRepo) {
        if (gid.isEmpty()) return

        val pending = withContext(Dispatchers.IO) {
            wcdb.groupMemberContactor.getAllObjects(
                DBGroupMemberContactorModel.gid.eq(gid)
                    .and(DBGroupMemberContactorModel.signatureVerify.isNull())
            )
        }
        if (pending.isEmpty()) return

        runVerifyAndDispose(gid, pending, groupRepo, "verifyAll")
    }

    /**
     * Verify newly-added members from a notify and dispose invalid ones.
     * Verified members are persisted as signatureVerify=true so subsequent
     * full verify's SQL filter skips them.
     */
    suspend fun verifyAndDisposeInvalidMembers(
        gid: String,
        members: List<GroupMemberContactorModel>,
        groupRepo: GroupRepo,
    ) {
        if (members.isEmpty() || gid.isEmpty()) return
        runVerifyAndDispose(gid, members, groupRepo, "notify-verify")
    }

    /**
     * Shared verify + dispose pipeline. cryptoDispose runs OUTSIDE Dispatchers.IO
     * because Retrofit manages its own dispatcher. HTTP timeout is governed by
     * the project-wide ChativeHttpClient config (connect/read/write seconds);
     * no per-call withTimeout here. On failure, never set verify=false — leave
     * null so the next sync retries.
     */
    private suspend fun runVerifyAndDispose(
        gid: String,
        members: List<GroupMemberContactorModel>,
        groupRepo: GroupRepo,
        tag: String,
    ) {
        L.i { "[GroupCryptoRepo] $tag start gid=$gid count=${members.size}" }

        data class VerifyResult(val verified: List<String>, val invalid: List<String>)

        val result = withContext(Dispatchers.IO) {
            val rGroupBytes = getRGroupBytes(gid) ?: run {
                L.i { "[GroupCryptoRepo] $tag gid=$gid no R_group skip" }
                return@withContext null
            }
            val pkBind = try {
                GroupCrypto.derivePkBind(rGroupBytes)
            } catch (e: Exception) {
                L.e { "[GroupCryptoRepo] $tag derivePkBind failed gid=$gid: ${e.stackTraceToString()}" }
                return@withContext null
            }

            val verified = mutableListOf<String>()
            val invalid = mutableListOf<String>()
            members.forEach { m ->
                val uid = m.id ?: return@forEach
                val sig = m.uidSignature
                val ok = if (sig == null) {
                    // Encrypted-group invariant: every member must carry uidSignature.
                    // A null here is a server-side anomaly or active attack — treat
                    // as invalid so cryptoDispose can remove the member.
                    L.w { "[GroupCryptoRepo] $tag null uidSignature in encrypted group uid=$uid gid=$gid (treated as invalid)" }
                    false
                } else try {
                    GroupCrypto.verifyUid(pkBind, uid, sig)
                } catch (e: Exception) {
                    L.e { "[GroupCryptoRepo] $tag verifyUid threw uid=$uid gid=$gid (treated as invalid): ${e.stackTraceToString()}" }
                    false
                }
                if (ok) verified += uid else invalid += uid
            }

            if (verified.isNotEmpty()) batchMarkVerified(gid, verified)
            VerifyResult(verified, invalid)
        } ?: return

        var serverRemoved = 0
        var serverRejected = 0
        if (result.invalid.isNotEmpty()) {
            L.w { "[GroupCryptoRepo] $tag gid=$gid invalid=${result.invalid.size} uids=${result.invalid}" }
            try {
                val resp = groupRepo.cryptoDispose(gid, CryptoDisposeReq(result.invalid))
                serverRemoved = resp.data?.removed?.size ?: 0
                serverRejected = resp.data?.rejected?.size ?: 0
                // `removed`  = server confirms invalid; member-removal notify will
                //              physically delete the row (no local action here).
                // `rejected` = server claims the uid is actually valid. We do NOT
                //              mark it verified=true: the encrypted-group threat
                //              model explicitly distrusts the server (DB
                //              compromise, internal misconduct, API abuse). Trusting
                //              `rejected` would let an attacker controlling the
                //              server launder any injected uid into a trusted
                //              state. GroupCrypto.verifyUid is mathematically
                //              deterministic, so a single-uid local-fail-but-
                //              server-pass is near-certain to be the server lying.
                //              The uid stays signatureVerify=null and retries on
                //              the next sync; cryptoDispose is idempotent so the
                //              extra calls are harmless.
            } catch (e: Exception) {
                L.e { "[GroupCryptoRepo] $tag cryptoDispose failed gid=$gid: ${e.stackTraceToString()}" }
            }
        }

        L.i {
            "[GroupCryptoRepo] $tag gid=$gid " +
                    "verified=${result.verified.size} invalid=${result.invalid.size} " +
                    "serverRemoved=$serverRemoved serverRejected=$serverRejected"
        }
    }

    // Chunked under SQLite's SQLITE_MAX_VARIABLE_NUMBER (default 999); 500 leaves
    // headroom. Typical groups (≤ a few hundred members) are a single UPDATE.
    private suspend fun batchMarkVerified(gid: String, verifiedUids: List<String>) {
        if (verifiedUids.isEmpty()) return
        withContext(Dispatchers.IO) {
            verifiedUids.chunked(UPDATE_CHUNK_SIZE).forEach { chunk ->
                wcdb.groupMemberContactor.updateValue(
                    Value(true),
                    DBGroupMemberContactorModel.signatureVerify,
                    DBGroupMemberContactorModel.gid.eq(gid)
                        .and(DBGroupMemberContactorModel.id.`in`(chunk))
                )
            }
        }
    }

    companion object {
        private const val UPDATE_CHUNK_SIZE = 500
    }
}
