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
 * Outcome of the version-decision for an incoming R_group write.
 * - [INSERT]    no stored key — write the new (gid, rGroup, version).
 * - [OVERWRITE] incoming is strictly newer — write AND reset signatureVerify.
 * - [SKIP]      stale / equal / invalid incoming — leave the stored key alone.
 */
internal enum class RGroupWriteDecision { INSERT, OVERWRITE, SKIP }

/**
 * Pure, WCDB-free decision for whether an incoming R_group version should be
 * written. Centralizes the version gate (incl. the uint32→Int sign-overflow
 * guard) so it can be unit-tested on the host JVM without native WCDB.
 *
 * @param storedVersion the currently-stored keyVersion, or null if no row.
 * @param incomingVersion the parsed incoming keyVersion (raw, may be negative
 *   if a uint32 ≥ 2^31 was parsed into a signed Int — treated as invalid).
 */
internal fun decideRGroupWrite(storedVersion: Int?, incomingVersion: Int): RGroupWriteDecision {
    if (incomingVersion < 0) return RGroupWriteDecision.SKIP // uint32→Int sign overflow guard
    return when {
        storedVersion == null -> RGroupWriteDecision.INSERT
        incomingVersion > storedVersion -> RGroupWriteDecision.OVERWRITE
        else -> RGroupWriteDecision.SKIP
    }
}

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
     * Get the stored key generation for a group. Returns 0 if no row exists
     * (0 = original/un-rotated baseline). Must be called from IO thread.
     */
    fun getKeyVersion(gid: String): Int {
        return wcdb.groupCryptoKeys.getFirstObject(
            DBGroupCryptoKeysModel.gid.eq(gid)
        )?.keyVersion ?: 0
    }

    /**
     * Single atomic read of (decoded R_group bytes, keyVersion) for the send
     * paths. ONE getFirstObject so the bytes and the version are a consistent
     * snapshot (separate getRGroupBytes + getKeyVersion calls could otherwise
     * straddle a concurrent rotation and ship mismatched bytes/version).
     * Returns null if there is no row or the stored R_group fails to decode.
     * Must be called from IO thread.
     */
    fun getRGroupWithVersion(gid: String): Pair<ByteArray, Int>? {
        val row = wcdb.groupCryptoKeys.getFirstObject(
            DBGroupCryptoKeysModel.gid.eq(gid)
        ) ?: return null
        val rGroupBase64 = row.rGroup ?: return null
        return try {
            Base64.decode(rGroupBase64, Base64.DEFAULT) to row.keyVersion
        } catch (e: Exception) {
            L.e { "[GroupCryptoRepo] Failed to decode R_group for group $gid: ${e.message}" }
            null
        }
    }

    /**
     * Version-aware write of R_group. The single write funnel for both receive
     * paths (GroupKeyMessage + DataMessage.Group fallback). Must be called from
     * IO thread.
     *
     * - no stored key  → insert (gid, rGroup, incomingVersion) — new join /
     *   all-members-lost recovery.
     * - incomingVersion > stored.keyVersion → overwrite with the newer key and
     *   reset member signatureVerify (pk_bind changed, old verifications are
     *   stale and must be re-run against the new pk_bind).
     * - incomingVersion <= stored.keyVersion → skip (blocks a stale/older-version
     *   key from regressing a freshly rotated key).
     *
     * @return true if the key was inserted or overwritten, false if skipped.
     *
     * `@Synchronized` serializes the read-then-write across all in-process
     * callers (GroupCryptoRepo is @Singleton, so the method monitor is a single
     * process-wide lock). Without it, two concurrent callers for the same gid
     * could both pass the version gate and the last writer could regress to an
     * older version. Synchronizing AROUND the OVERWRITE transaction is safe — a
     * monitor enclosing a WCDB transaction does not deadlock.
     */
    @Synchronized
    fun saveOrRotateRGroup(gid: String, rGroup: ByteArray, incomingVersion: Int): Boolean {
        val stored = wcdb.groupCryptoKeys.getFirstObject(DBGroupCryptoKeysModel.gid.eq(gid))

        fun buildModel() = GroupCryptoKeysModel().apply {
            this.gid = gid
            this.rGroup = Base64.encodeToString(rGroup, Base64.NO_WRAP)
            this.keyVersion = incomingVersion
        }

        // Use insertOrReplace to avoid PK constraint crash from concurrent callers
        // (handleGroupKeyMessage + DataMessage fallback can race on IO dispatcher).
        return when (decideRGroupWrite(stored?.keyVersion, incomingVersion)) {
            RGroupWriteDecision.INSERT -> {
                wcdb.groupCryptoKeys.insertOrReplaceObject(buildModel())
                L.i { "[GroupCryptoRepo] Saved R_group for group $gid v=$incomingVersion" }
                true
            }

            RGroupWriteDecision.OVERWRITE -> {
                // Atomic: key write + signatureVerify reset must commit together,
                // else a crash between them leaves a new key with stale verify=true.
                wcdb.db.runTransaction {
                    wcdb.groupCryptoKeys.insertOrReplaceObject(buildModel())
                    resetSignatureVerify(gid)
                    true
                }
                L.i { "[GroupCryptoRepo] rotated R_group gid=$gid v=${stored?.keyVersion}->$incomingVersion" }
                true
            }

            RGroupWriteDecision.SKIP -> {
                L.i { "[GroupCryptoRepo] skip stale/invalid R_group gid=$gid incoming=$incomingVersion stored=${stored?.keyVersion}" }
                false
            }
        }
    }

    /**
     * Unconditionally persist the locally-generated rotated R_group for the rotation
     * INITIATOR. Unlike [saveOrRotateRGroup] (receive-side, version-gated), the
     * initiator's freshly-generated key is authoritative and must always be written,
     * even if [version] is not strictly greater than the stored one (e.g. the server
     * omitted keyVersion and the group was already at the same generation locally).
     * Also resets member signatureVerify (new pk_bind invalidates prior verifications).
     * Same `@Synchronized` + transaction semantics as the OVERWRITE path of
     * [saveOrRotateRGroup]. Must be called from IO thread.
     */
    @Synchronized
    fun setRotatedRGroup(gid: String, rGroup: ByteArray, version: Int) {
        // Atomic: key write + signatureVerify reset must commit together, else a
        // crash between them leaves a new key with stale verify=true.
        wcdb.db.runTransaction {
            wcdb.groupCryptoKeys.insertOrReplaceObject(GroupCryptoKeysModel().apply {
                this.gid = gid
                this.rGroup = Base64.encodeToString(rGroup, Base64.NO_WRAP)
                this.keyVersion = version
            })
            resetSignatureVerify(gid)
            true
        }
        L.i { "[GroupCryptoRepo] set rotated R_group gid=$gid v=$version (initiator)" }
    }

    /**
     * Reset member signature verification for a group:
     * `UPDATE group_member_contactor SET signatureVerify = NULL WHERE gid = ?`.
     * Called on key rotation — the new pk_bind invalidates every prior
     * verification, so null lets [verifyAllPendingForGroup] re-verify against
     * the new key. Must be called from IO thread.
     */
    fun resetSignatureVerify(gid: String) {
        wcdb.groupMemberContactor.updateValue(
            Value(), // NULL
            DBGroupMemberContactorModel.signatureVerify,
            DBGroupMemberContactorModel.gid.eq(gid)
        )
        L.i { "[GroupCryptoRepo] reset signatureVerify gid=$gid" }
    }

    /**
     * Save R_group if not already present. Idempotent — skips if key already exists.
     * Thin wrapper over [saveOrRotateRGroup] with version 0 (original/un-rotated)
     * for create-group / upgrade callers that have no version context.
     * Must be called from IO thread.
     *
     * @return true if key was saved (new), false if already existed
     */
    fun saveRGroupIfNeeded(gid: String, rGroup: ByteArray): Boolean {
        return saveOrRotateRGroup(gid, rGroup, 0)
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
