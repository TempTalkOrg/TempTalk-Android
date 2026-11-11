package com.difft.android.setting.repo

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.chat.crypto.IdentityKeyUtil
import com.difft.android.chat.cryptonew.EncryptionDataManager
import com.difft.android.chat.gif.favorite.FavoriteWriteRepository
import com.difft.android.login.data.RenewIdentityKeyRequestBody
import com.difft.android.login.repo.LoginRepo
import com.difft.android.websocket.internal.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class IdentityRotationRepository @Inject constructor(
    private val login: LoginRepo,
    private val encryption: EncryptionDataManager,
    private val users: com.difft.android.base.storage.user.StorageBoundUserManager,
    private val favorites: FavoriteWriteRepository,
    @Named("application") private val scope: CoroutineScope,
) {
    private data class AcceptedCandidate(
        val account: String,
        val authorization: String?,
        val old: IdentityKeyPair,
        val next: IdentityKeyPair,
        val acceptedAt: Long,
        var localStage: Int = 0,
    )
    private val mutex = Mutex()
    private var accepted: AcceptedCandidate? = null

    
    suspend fun renewOrComplete(): AccountMutationResult {
        val account = globalServices.myId
        val authorization = users.getUserData()?.baseAuth
        if (!mutex.tryLock()) return AccountMutationResult.Busy
        return scope.async(Dispatchers.Main.immediate) {
            try {
                if (account != globalServices.myId) return@async AccountMutationResult.StaleAccount
                PageOperations.track("identity-rotation:$account") {
                    val candidate = accepted?.let {
                        if (it.account != account || it.authorization != authorization) { accepted = null; return@track AccountMutationResult.StaleAccount }
                        it
                    } ?: createAcceptedCandidate(account, authorization) ?: return@track AccountMutationResult.Rejected(null)
                    if (!isCurrent(candidate)) return@track AccountMutationResult.StaleAccount
                    complete(candidate)
                }
            } finally { mutex.unlock() }
        }.await()
    }

    private suspend fun createAcceptedCandidate(account: String, authorization: String?): AcceptedCandidate? {
        
        val registrationId = KeyHelper.generateRegistrationId(false)
        val next = IdentityKeyUtil.generateIdentityKeyPair()
        val old = encryption.getAciIdentityKey()
        val data = "${Base64.encodeBytesWithoutPadding(next.publicKey.serialize())}$registrationId"
        val signature = next.privateKey.calculateSignature(data.toByteArray())
        val oldSignature = old.privateKey.calculateSignature(signature)
        val request = RenewIdentityKeyRequestBody(
            Base64.encodeBytesWithoutPadding(next.publicKey.serialize()), registrationId,
            Base64.encodeBytesWithoutPadding(signature), Base64.encodeBytesWithoutPadding(oldSignature),
        )
        val result = withContext(Dispatchers.IO) { login.renewIdentityKey(JsonUtil.toJson(request)) }
        if (result.status != 0) return null
        return AcceptedCandidate(account, authorization, old, next, System.currentTimeMillis()).also { accepted = it }
    }

    private fun isCurrent(candidate: AcceptedCandidate): Boolean =
        candidate.account == globalServices.myId && users.getUserData()?.let {
            it.account == candidate.account && it.baseAuth == candidate.authorization
        } == true

    private suspend fun complete(candidate: AcceptedCandidate): AccountMutationResult {
        try {
            if (!isCurrent(candidate)) return AccountMutationResult.StaleAccount
            if (candidate.localStage == 0) {
                users.commitStrict(candidate.account) {
                    check(baseAuth == candidate.authorization) { "Session changed before identity commit" }
                    aciIdentityOldPublicKey = Base64.encodeBytes(candidate.old.publicKey.serialize())
                    aciIdentityOldPrivateKey = Base64.encodeBytes(candidate.old.privateKey.serialize())
                }
                candidate.localStage = 1
            }
            if (!isCurrent(candidate)) return AccountMutationResult.StaleAccount
            if (candidate.localStage == 1) {
                users.commitStrict(candidate.account) {
                    check(baseAuth == candidate.authorization) { "Session changed before identity commit" }
                    aciIdentityPublicKey = Base64.encodeBytes(candidate.next.publicKey.serialize())
                    aciIdentityPrivateKey = Base64.encodeBytes(candidate.next.privateKey.serialize())
                }
                candidate.localStage = 2
            }
            if (!isCurrent(candidate)) return AccountMutationResult.StaleAccount
            if (candidate.localStage == 2) {
                users.commitStrict(candidate.account) {
                    check(baseAuth == candidate.authorization) { "Session changed before identity commit" }
                    aciIdentityKeyGenTime = candidate.acceptedAt
                }
                candidate.localStage = 3
            }
            try {
                favorites.rewrapOnMasterKeyRotation(candidate.old.privateKey.serialize(), candidate.next.privateKey.serialize(), candidate.account, candidate.authorization)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                L.w { "[Privacy] favorites_rewrap_failed error=${e.javaClass.simpleName}" }
            }
            accepted = null
            L.i { "[Privacy] identity_rotation_applied favorites=attempted" }
            return AccountMutationResult.Applied
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            L.e { "[Privacy] identity_local_commit_failed stage=${candidate.localStage} error=${e.stackTraceToString()}" }
            return AccountMutationResult.AcceptedLocalCommitFailed(e)
        }
    }
}
