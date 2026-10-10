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

/**
 * The commit tail shared by both [RestoreOrchestrator] entry points: the pre-promote re-checks, the
 * identity install, the [PromoteStep] critical section and everything after it.
 *
 * **The commit boundary is P2 — the database rename.** Before it nothing is committed, so an ordinary
 * retryable failure is both true and safe. At or after it the primary database *is* the migrated one,
 * and this device can no longer prove the remaining work finished, so no verdict may be published in
 * either direction: [MigrationException.CommittedUnconfirmed] is raised, the data and the recovery
 * inputs are kept untouched, and the next cold start finishes the promote.
 *
 * The boundary is read off the filesystem via [PromoteStep.isDatabasePromoted] rather than from the
 * exception type, because a promote marker exists after a P1 failure too — where the rename never ran
 * and the live database is still the empty net-install one. Treating that as committed would walk the
 * user into signing in against an empty database.
 *
 * @param publish the orchestrator's progress publisher.
 * @param cleanupWork the orchestrator's work-area reclaim, called on every terminal path as before.
 */
class RestoreCommitTail(
    private val context: Context,
    private val userManager: UserManager,
    private val credentialInstaller: CredentialInstaller,
    private val publish: (stage: String, percent: Int) -> Unit,
    private val cleanupWork: (work: File?) -> Unit,
) {

    /**
     * Advance to PROMOTING, run promote, then the post-commit tail. Returns null when the migration
     * is ready for sign-in, a [RestoreResult.Rejected] when a pre-promote guard refused it.
     *
     * The net-install check is re-taken immediately before promote. The first check happens before the
     * import, and a large import can take minutes, during which a concurrent sign-in could establish an
     * account and write the live DB; promoting then would clobber it. So a device that became non-fresh
     * has its staging discarded and gets the same [MigrationError.TargetNotEmpty] rejection the early
     * guard returns, without promote ever being called.
     *
     * [carriedIdentity] is installed into secure storage right after that re-check and before promote.
     * The ordering is crash-safe in both directions: installed but crashed before promote, the retry
     * re-installs the same bytes idempotently; crashed after promote, the identity is already persisted
     * so the resumed sign-in publishes the original one. Placing it after the re-check means a rejected
     * restore never touches secure storage. The identity is deliberately not carried in the promote
     * marker — a private key must not land in a plaintext file, and this ordering already makes resume
     * correct.
     *
     * @throws MigrationException.CommittedUnconfirmed when the rename ran but the tail after it did not.
     */
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
        storageGuard: StorageGuard? = null,
    ): RestoreResult? {
        // Re-check: a device that became non-fresh during the import must not be promoted onto, which
        // would overwrite the now-live DB. Discard staging, clear the pre-promote flag and reject as the
        // early guard does. No marker exists yet, so clearing the flag leaves no cold-start residue.
        if (!isFreshTarget()) {
            L.w { "[MigrationV2][Restore] pre-promote re-check failed: target became non-fresh during import — discard, no promote" }
            return discardAndReject(staging, flagStore, work, MigrationError.TargetNotEmpty)
        }
        // Install the identity before promote — see this method's kdoc. The material is checked first:
        // sign-in's first request registers this device and revokes the old one while its second decodes
        // this identity, so a key that cannot decode strands the user on both devices.
        val identityToInstall = carriedIdentity?.let { carried ->
            try {
                CarriedIdentityNormalizer.normalize(carried)
            } catch (e: CarriedIdentityNormalizer.InvalidIdentity) {
                L.w { "[MigrationV2][Restore] carried identity unusable (${e.message}) — discard, no promote" }
                return discardAndReject(staging, flagStore, work, MigrationError.IdentityInvalid)
            }
        }
        // A no-login handoff whose container carried no credential must not be promoted: the premise is
        // that this device comes up signed in, and the sender would read a 200 as licence to wipe its own
        // session. Rejected before promote, exactly like an unusable identity.
        if (credentialHandoff && carriedCredential == null) {
            L.w { "[MigrationV2][Restore] handoff container carried no credential — discard, no promote" }
            return discardAndReject(staging, flagStore, work, MigrationError.CredentialMissing)
        }
        // Last chance to fail cleanly: past the rename, running out of space can no longer be answered
        // with a retryable verdict. Checked before the identity write below, which nothing reverts.
        storageGuard?.requireHeadroom("promote")
        if (identityToInstall != null) {
            installIdentity(identityToInstall)
        } else {
            L.i { "[MigrationV2][Restore] container carried no identity — login will generate one (pre-1.1 behaviour)" }
        }
        // Past this line a peer's /cancel can undo nothing; it is answered `too_late` and the promote
        // runs to its end. Every reject that could still stop this import is above.
        liveness.committing()
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
            // Provably back at the clean net-install state: nothing was committed, so this stays an
            // ordinary failure the caller re-prompts on.
            throw e
        } catch (e: CancellationException) {
            // A cancel that lands after the rename is still a committed database; before it, the
            // cancellation propagates as itself and nothing is resumed on a cancelled coroutine.
            if (PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath)) {
                committedUnconfirmed(e)
            }
            throw e
        } catch (e: Throwable) {
            if (PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath)) {
                committedUnconfirmed(e)
            }
            // Pre-P2: the marker and the staging products are exactly what a resume needs, and there is
            // no cold start on this path, so finish it here instead of waiting for one that may never
            // come. Classified from the resume's own result, never from the exception type.
            when (runCatching { promoteStep.resumeIfPending() }.getOrNull()) {
                PromoteStep.ResumeResult.ResumedWithData -> Unit
                else -> throw e
            }
        }
        return runTail(paths, flagStore, work, carriedCredential)
    }

    /**
     * Everything after the rename: the sign-in flag, the carried credential and the work-area reclaim.
     * Null means the migration is ready for sign-in.
     *
     * A failure here is retried a bounded number of times, reclaiming the receive spool in between. The
     * reclaim is scoped to [RestorePaths.spoolDir] and nothing else: the staging database and the
     * container work file share one directory, and deleting those would leave `resumeIfPending` with no
     * file to promote and every future cold start retrying the same dead end. By this point the body is
     * complete and every segment has been consumed, so the spool is usually already empty — the reclaim
     * is kept because it is free and does recover real space after an aborted receive.
     */
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
                // The credential lands after promote: the database it unlocks has to be on disk first.
                // An earlier attempt may already have written it (only the flag write failed), which
                // reads as installed. A foreign account that appeared meanwhile cannot be answered
                // with a retryable verdict past the rename: it is the committed-unconfirmed outcome.
                if (carriedCredential != null) {
                    when (credentialInstaller.install(carriedCredential)) {
                        CredentialInstaller.Result.Installed, CredentialInstaller.Result.AlreadyInstalled -> Unit
                        CredentialInstaller.Result.SkippedNotFresh ->
                            committedUnconfirmed(IllegalStateException("another account appeared after the promote"))
                    }
                    // No sign-in runs on this path, so nothing else would advance the flag. The two
                    // writes live in different stores and cannot be atomic; a crash between them is
                    // resolved at startup by `RestoreFlagStore.consumeSigningInFor`.
                    flagStore.advance(RestorePhase.COMPLETED)
                }
                publish("ready", 100)
                cleanupWork(work)
            }.onSuccess {
                return null
            }.onFailure { e ->
                // Already the final outcome: retrying cannot change what the install found.
                if (e is MigrationException.CommittedUnconfirmed) throw e
                lastFailure = e
                L.w { "[MigrationV2][Restore] post-commit tail attempt ${attempt + 1} failed: ${e.message}" }
                val reclaimed = reclaimSpool(paths)
                L.i { "[MigrationV2][Restore] reclaimed ${reclaimed / BYTES_PER_MB}MB from the spool; retrying the commit tail" }
                delay(TAIL_RETRY_DELAY_MS)
            }
        }
        // P4 already cleared the marker? Then only the flag is broken, and nothing resumes a flag left
        // at PROMOTING with no marker. Deleting a file succeeds where writing one does not, and it
        // leaves the honest end state: a populated primary database and an ordinary sign-in.
        if (!PromoteMarkerStore(context).exists()) runCatching { flagStore.clear() }
        committedUnconfirmed(lastFailure)
    }

    /** Discard the staging products and the pre-promote flag, then reject. No marker exists yet. */
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

    /** Bytes the spool still held, so the retry's log says whether the reclaim was worth anything. */
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

    /**
     * Install the carried identity material into secure storage as one atomic `userManager.update {}`
     * write, rather than through `EncryptionDataManager.updateAciIdentityKey`.
     *
     * That helper stamps `aciIdentityKeyGenTime = System.currentTimeMillis()`, and the old-key decrypt
     * fallback is gated by a 24h window measured from that timestamp, so re-stamping it to now would
     * silently re-open the fallback window and desynchronize it from the carried old key pair. The
     * carried generation time must be written verbatim, so the fields are written directly here.
     *
     * The restore target is a net install, so its identity, favKey and proxy fields are empty: this is
     * a plain install, not a merge. `commit = true` makes the write durable before promote runs, which
     * is what makes "crashed after promote implies identity persisted" literally true. It writes the
     * carried bytes verbatim and is therefore idempotent across any number of retries, which is why it
     * is allowed to precede the commit boundary without moving it.
     *
     * The log line carries only booleans and timestamps — never key or passphrase material.
     */
    private fun installIdentity(identity: NeutralIdentity) {
        userManager.update(commit = true) {
            aciIdentityPublicKey = identity.aciPublicKey
            aciIdentityPrivateKey = identity.aciPrivateKey
            aciIdentityOldPublicKey = identity.oldAciPublicKey
            aciIdentityOldPrivateKey = identity.oldAciPrivateKey
            // VERBATIM — see kdoc; a re-stamped value breaks the old-key fallback window.
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
        /** How many times the post-commit tail is retried before the outcome is called unconfirmed. */
        const val TAIL_ATTEMPTS = 3

        /** Pause between tail attempts, long enough for a transient storage condition to clear. */
        const val TAIL_RETRY_DELAY_MS = 250L

        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
