package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.handoff.CredentialInstaller
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.v2.model.NeutralIdentity
import com.difft.android.p2p.engine.session.TransferProgressReporter
import kotlinx.coroutines.delay
import java.io.File
import kotlin.coroutines.cancellation.CancellationException



















class RestoreCommitTail(
    private val context: Context,
    private val userManager: UserManager,
    private val credentialInstaller: CredentialInstaller,
    private val publish: (stage: String, percent: Int) -> Unit,
    private val cleanupWork: (work: File?) -> Unit,
) {

    



















    @Suppress("LongParameterList")
    suspend fun run(
        staging: StagingDatabase,
        paths: RestorePaths,
        flagStore: RestoreFlagStore,
        work: File?,
        promoteStep: PromoteStep,
        isFreshTarget: () -> Boolean,
        carriedIdentity: NeutralIdentity?,
        credentialHandoff: Boolean = false,
        carriedCredential: NeutralCredential? = null,
        liveness: TransferProgressReporter = TransferProgressReporter.None,
        
        livenessSessionId: String? = null,
        storageGuard: StorageGuard? = null,
    ): RestoreResult? {
        
        
        
        if (!isFreshTarget()) {
            L.w { "[MigrationV2][Restore] pre-promote re-check failed: target became non-fresh during import — discard, no promote" }
            return discardAndReject(staging, flagStore, work, MigrationError.TargetNotEmpty)
        }
        
        
        
        val identityToInstall = carriedIdentity?.let { carried ->
            try {
                CarriedIdentityNormalizer.normalize(carried)
            } catch (e: CarriedIdentityNormalizer.InvalidIdentity) {
                L.w { "[MigrationV2][Restore] carried identity unusable (${e.message}) — discard, no promote" }
                return discardAndReject(staging, flagStore, work, MigrationError.IdentityInvalid)
            }
        }
        
        
        
        if (credentialHandoff && carriedCredential == null) {
            L.w { "[MigrationV2][Restore] handoff container carried no credential — discard, no promote" }
            return discardAndReject(staging, flagStore, work, MigrationError.CredentialMissing)
        }
        
        
        storageGuard?.requireHeadroom("promote")
        
        
        
        
        if (!liveness.committing(livenessSessionId)) {
            L.w { "[MigrationV2][Restore] event=commit_refused reason=session_not_live" }
            return discardAndReject(staging, flagStore, work, MigrationError.Cancelled)
        }
        if (identityToInstall != null) {
            installIdentity(identityToInstall)
        } else {
            L.i { "[MigrationV2][Restore] container carried no identity — login will generate one (pre-1.1 behaviour)" }
        }
        flagStore.advance(RestorePhase.PROMOTING)
        publish("promoting", 80)
        try {
            promoteStep.promote(
                stagingDbPath = paths.stagingDbPath,
                stagingKey = staging.stagingKey,
                attachmentsStagingDir = paths.attachmentsStagingDir,
                realDbPath = paths.realDbPath,
                realAttachmentDir = paths.realAttachmentDir,
            )
        } catch (e: MigrationException.RestoreAbortedCleanState) {
            
            
            throw e
        } catch (e: CancellationException) {
            
            
            if (PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath)) {
                committedUnconfirmed(e)
            }
            throw e
        } catch (e: Throwable) {
            if (PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath)) {
                committedUnconfirmed(e)
            }
            
            
            
            when (runCatching { promoteStep.resumeIfPending() }.getOrNull()) {
                PromoteStep.ResumeResult.ResumedWithData -> Unit
                else -> throw e
            }
        }
        return runTail(paths, flagStore, work, carriedCredential)
    }

    










    private suspend fun runTail(
        paths: RestorePaths,
        flagStore: RestoreFlagStore,
        work: File?,
        carriedCredential: NeutralCredential?,
    ): RestoreResult? {
        var lastFailure: Throwable? = null
        repeat(TAIL_ATTEMPTS) { attempt ->
            runCatching {
                flagStore.advance(RestorePhase.SIGNING_IN)
                
                
                
                
                if (carriedCredential != null) {
                    when (credentialInstaller.install(carriedCredential)) {
                        CredentialInstaller.Result.Installed, CredentialInstaller.Result.AlreadyInstalled -> Unit
                        CredentialInstaller.Result.SkippedNotFresh ->
                            committedUnconfirmed(IllegalStateException("another account appeared after the promote"))
                    }
                    
                    
                    
                    flagStore.advance(RestorePhase.COMPLETED)
                }
                publish("ready", 100)
                cleanupWork(work)
            }.onSuccess {
                return null
            }.onFailure { e ->
                
                if (e is MigrationException.CommittedUnconfirmed) throw e
                lastFailure = e
                L.w { "[MigrationV2][Restore] post-commit tail attempt ${attempt + 1} failed: ${e.message}" }
                val reclaimed = reclaimSpool(paths)
                L.i { "[MigrationV2][Restore] reclaimed ${reclaimed / BYTES_PER_MB}MB from the spool; retrying the commit tail" }
                delay(TAIL_RETRY_DELAY_MS)
            }
        }
        
        
        
        if (!PromoteMarkerStore(context).exists()) runCatching { flagStore.clear() }
        committedUnconfirmed(lastFailure)
    }

    
    private fun discardAndReject(
        staging: StagingDatabase,
        flagStore: RestoreFlagStore,
        work: File?,
        error: MigrationError,
    ): RestoreResult {
        runCatching { staging.discard() }
        runCatching { flagStore.clear() }
        cleanupWork(work)
        return RestoreResult.Rejected(error)
    }

    
    private fun reclaimSpool(paths: RestorePaths): Long {
        val dir = File(paths.spoolDir)
        val held = runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
        runCatching { dir.deleteRecursively() }
        return held
    }

    private fun committedUnconfirmed(cause: Throwable?): Nothing {
        L.e {
            "[MigrationV2][Restore] post-commit tail failed — data kept, NO terminal published: " +
                "${cause?.stackTraceToString()}"
        }
        throw MigrationException.CommittedUnconfirmed("post-commit tail failed", cause)
    }

    
















    private fun installIdentity(identity: NeutralIdentity) {
        userManager.update(commit = true) {
            aciIdentityPublicKey = identity.aciPublicKey
            aciIdentityPrivateKey = identity.aciPrivateKey
            aciIdentityOldPublicKey = identity.oldAciPublicKey
            aciIdentityOldPrivateKey = identity.oldAciPrivateKey
            
            aciIdentityKeyGenTime = identity.identityKeyGenTime
            favKey = identity.favKey
            favKeyId = identity.favKeyId
            favKeyVersion = identity.favKeyVersion
            proxyShareLink = identity.proxyShareLink
            proxyEnabled = identity.proxyEnabled
            proxyProtectCallIp = identity.proxyProtectCallIp
        }
        L.i {
            "[MigrationV2][Restore] identity installed hasOldPair=" +
                "${!identity.oldAciPrivateKey.isNullOrEmpty()} genTime=${identity.identityKeyGenTime} " +
                "hasFavKey=${!identity.favKey.isNullOrEmpty()}"
        }
    }

    companion object {
        
        const val TAIL_ATTEMPTS = 3

        
        const val TAIL_RETRY_DELAY_MS = 250L

        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
