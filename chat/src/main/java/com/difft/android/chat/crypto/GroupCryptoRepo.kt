package com.difft.android.chat.crypto

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.group.requireGroupWriteSession
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







internal enum class RGroupWriteDecision { INSERT, OVERWRITE, SKIP }










internal fun decideRGroupWrite(storedVersion: Int?, incomingVersion: Int): RGroupWriteDecision {
    if (incomingVersion < 0) return RGroupWriteDecision.SKIP 
    return when {
        storedVersion == null -> RGroupWriteDecision.INSERT
        incomingVersion > storedVersion -> RGroupWriteDecision.OVERWRITE
        else -> RGroupWriteDecision.SKIP
    }
}







internal fun shouldApplyInitiatorRotation(storedVersion: Int?, version: Int): Boolean =
    storedVersion == null || version >= storedVersion





@Singleton
class GroupCryptoRepo @Inject constructor() {

    



    fun getRGroup(gid: String): String? {
        return wcdb.groupCryptoKeys.getFirstObject(
            DBGroupCryptoKeysModel.gid.eq(gid)
        )?.rGroup
    }

    



    fun getRGroupBytes(gid: String): ByteArray? {
        val rGroupBase64 = getRGroup(gid) ?: return null
        return try {
            Base64.decode(rGroupBase64, Base64.DEFAULT)
        } catch (e: Exception) {
            L.e { "[GroupCryptoRepo] Failed to decode R_group for group $gid: ${e.message}" }
            null
        }
    }

    



    fun getKeyVersion(gid: String): Int {
        return wcdb.groupCryptoKeys.getFirstObject(
            DBGroupCryptoKeysModel.gid.eq(gid)
        )?.keyVersion ?: 0
    }

    







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

    





















    @Synchronized
    fun saveOrRotateRGroup(gid: String, rGroup: ByteArray, incomingVersion: Int): Boolean {
        val stored = wcdb.groupCryptoKeys.getFirstObject(DBGroupCryptoKeysModel.gid.eq(gid))

        fun buildModel() = GroupCryptoKeysModel().apply {
            this.gid = gid
            this.rGroup = Base64.encodeToString(rGroup, Base64.NO_WRAP)
            this.keyVersion = incomingVersion
        }

        
        
        return when (decideRGroupWrite(stored?.keyVersion, incomingVersion)) {
            RGroupWriteDecision.INSERT -> {
                wcdb.groupCryptoKeys.insertOrReplaceObject(buildModel())
                L.i { "[GroupCryptoRepo] Saved R_group for group $gid v=$incomingVersion" }
                true
            }

            RGroupWriteDecision.OVERWRITE -> {
                
                
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

    










    @Synchronized
    fun setRotatedRGroup(gid: String, rGroup: ByteArray, version: Int): Boolean {
        val storedVersion = wcdb.groupCryptoKeys.getFirstObject(DBGroupCryptoKeysModel.gid.eq(gid))?.keyVersion
        if (!shouldApplyInitiatorRotation(storedVersion, version)) {
            L.w { "[GroupCrypto] stale rotate write ignored gid=$gid v=$version stored=$storedVersion" }
            return false
        }
        
        
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
        return true
    }

    






    fun resetSignatureVerify(gid: String) {
        wcdb.groupMemberContactor.updateValue(
            Value(), 
            DBGroupMemberContactorModel.signatureVerify,
            DBGroupMemberContactorModel.gid.eq(gid)
        )
        L.i { "[GroupCryptoRepo] reset signatureVerify gid=$gid" }
    }

    







    fun saveRGroupIfNeeded(gid: String, rGroup: ByteArray): Boolean {
        return saveOrRotateRGroup(gid, rGroup, 0)
    }

    



    fun deleteKeys(gid: String) {
        wcdb.groupCryptoKeys.deleteObjects(DBGroupCryptoKeysModel.gid.eq(gid))
        L.i { "[GroupCryptoRepo] Deleted keys for group $gid" }
    }

    



    fun hasKeys(gid: String): Boolean {
        return getRGroup(gid) != null
    }

    




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

    






    suspend fun verifyAllPendingForGroup(gid: String, groupRepo: GroupRepo, commitAllowed: (() -> Boolean)? = null) {
        if (gid.isEmpty()) return

        val pending = withContext(Dispatchers.IO) {
            requireGroupWriteSession(commitAllowed)
            wcdb.groupMemberContactor.getAllObjects(
                DBGroupMemberContactorModel.gid.eq(gid)
                    .and(DBGroupMemberContactorModel.signatureVerify.isNull())
            )
        }
        if (pending.isEmpty()) return

        runVerifyAndDispose(gid, pending, groupRepo, "verifyAll", commitAllowed)
    }

    




    suspend fun verifyAndDisposeInvalidMembers(
        gid: String,
        members: List<GroupMemberContactorModel>,
        groupRepo: GroupRepo,
    ) {
        if (members.isEmpty() || gid.isEmpty()) return
        runVerifyAndDispose(gid, members, groupRepo, "notify-verify")
    }

    






    private suspend fun runVerifyAndDispose(
        gid: String,
        members: List<GroupMemberContactorModel>,
        groupRepo: GroupRepo,
        tag: String,
        commitAllowed: (() -> Boolean)? = null,
    ) {
        L.i { "[GroupCryptoRepo] $tag start gid=$gid count=${members.size}" }

        data class VerifyResult(val verified: List<String>, val invalid: List<String>)

        val result = withContext(Dispatchers.IO) {
            requireGroupWriteSession(commitAllowed)
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

            if (verified.isNotEmpty()) batchMarkVerified(gid, verified, commitAllowed)
            VerifyResult(verified, invalid)
        } ?: return

        var serverRemoved = 0
        var serverRejected = 0
        if (result.invalid.isNotEmpty()) {
            L.w { "[GroupCryptoRepo] $tag gid=$gid invalid=${result.invalid.size} uids=${result.invalid}" }
            try {
                requireGroupWriteSession(commitAllowed)
                val resp = groupRepo.cryptoDispose(gid, CryptoDisposeReq(result.invalid))
                serverRemoved = resp.data?.removed?.size ?: 0
                serverRejected = resp.data?.rejected?.size ?: 0
                
                
                
                
                
                
                
                
                
                
                
                
                
                
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

    
    
    private suspend fun batchMarkVerified(gid: String, verifiedUids: List<String>, commitAllowed: (() -> Boolean)? = null) {
        if (verifiedUids.isEmpty()) return
        withContext(Dispatchers.IO) {
            verifiedUids.chunked(UPDATE_CHUNK_SIZE).forEach { chunk ->
                requireGroupWriteSession(commitAllowed)
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
