package com.difft.android.migration.v2.restore

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.user.UserManager
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.v2.handoff.CredentialInstaller
import com.difft.android.migration.v2.model.NeutralCredential
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.container.ArchiveSource
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.CountingOutputStream
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.container.ProgressReporter
import com.difft.android.migration.v2.importer.NeutralImporter
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.session.TransferProgressReporter
import com.difft.android.migration.v2.model.NeutralIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * The restore engine. Given a selected `.ttmig` source, a passphrase and the current verify account it
 * validates (HMAC-authenticate, decrypt, read the manifest, then check format-version compatibility
 * and account match), guards that the target is a fresh install, imports the neutral records into a
 * fresh [StagingDatabase] while streaming attachment bytes into a staging dir, promotes the staging
 * products into the live locations via [PromoteStep], and returns [RestoreResult.ReadyForSignIn].
 *
 * It never signs in or restarts the process: after [RestoreResult.ReadyForSignIn] the flag is at
 * [RestorePhase.SIGNING_IN] and the caller drives the rest.
 *
 * Phase milestones go to the [RestoreFlagStore]. [PromoteStep] writes its own authoritative marker for
 * the critical section, so this class does not double-handle promote recovery: on any failure it
 * discards the staging products and surfaces the failure, and cold start finishes or rolls back a
 * promote interrupted mid-section. Restore runs only on a net install, so the worst case of any
 * interruption is a redo from the empty state.
 */
@Singleton
class RestoreOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerReader: ContainerReader,
    /**
     * The unified net-install signal: the guard reuses the same
     * [MigrationImportLauncher.isFreshInstallTarget] that gates the login import prompt, so the restore
     * engine and `:login` never diverge on what a net-install target means.
     */
    private val migrationImportLauncher: MigrationImportLauncher,
    /**
     * Secure-storage writer for the carried identity material. The install is a single atomic update
     * performed immediately before promote — see [installIdentity] for why it must not go through
     * `EncryptionDataManager.updateAciIdentityKey`.
     */
    private val userManager: UserManager,
    /**
     * Writer for a carried session credential (same-device no-login handoff). Kept out of this class on
     * purpose: the write needs a freshness test inside its own update block, a distinct concern from
     * orchestrating the restore. Untouched unless the handoff is on.
     */
    private val credentialInstaller: CredentialInstaller,
) {

    private val _progress = MutableStateFlow<RestoreProgress?>(null)

    /** Restore progress for the caller's UI; null before a restore starts and after a terminal result. */
    val progress: StateFlow<RestoreProgress?> = _progress.asStateFlow()

    /**
     * Run the full restore and return a typed [RestoreResult]. All IO runs on [Dispatchers.IO]. The
     * validation rejects do not throw — they return [RestoreResult.Rejected], so the caller can show a
     * clear message and stay on the picker.
     *
     * @param currentVerifyAccount the account the caller just verified in memory, compared against the
     *   manifest's `sourceAccount`. Not read from storage here, since it is not persisted until sign-in.
     * @param isFreshTarget the net-install precondition probe: true only when no user account has ever
     *   been established on this install. Injectable so the decision is unit-testable without native
     *   storage.
     */
    suspend fun restore(
        source: ArchiveSource,
        passphrase: String,
        currentVerifyAccount: String,
        paths: RestorePaths = RestorePaths.forContext(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
        promoteStep: PromoteStep = PromoteStep(context),
        stagingFactory: (stagingDbPath: String) -> StagingDatabase = { StagingDatabase.create(context, it) },
        isFreshTarget: () -> Boolean = migrationImportLauncher::isFreshInstallTarget,
    ): RestoreResult = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Restore] orchestrator start" }
        val work = File(paths.containerWorkFile).parentFile
        try {
            // Materialize the source to a randomly-accessible work file: verify-before-trust is a
            // two-pass read and SAF input is forward-only.
            val containerFile = materialize(source, paths.containerWorkFile)
            publish("validating", 5)

            // Validation chain: HMAC, decrypt and manifest, then version, then account.
            val validation = validate(containerFile, passphrase, currentVerifyAccount)
            if (validation is RestoreResult.Rejected) {
                L.w { "[MigrationV2][Restore] rejected at validation: ${validation.error.code}" }
                cleanupWork(work)
                return@withContext validation
            }

            // Net-install guard: the target must be a fresh install with no account ever established
            // here. A passive device kick preserves the account, so a kicked device is correctly not
            // fresh. Surfaced as Rejected rather than a thrown Failed, so the file and streaming paths
            // return the identical result for the same condition. The pre-promote re-check below closes
            // the TOCTOU window for both.
            if (!isFreshTarget()) {
                L.w { "[MigrationV2][Restore] rejected: target already has an established account (net-install-only)" }
                cleanupWork(work)
                return@withContext RestoreResult.Rejected(MigrationError.TargetNotEmpty)
            }

            // Import into staging. begin() writes the phase, source, start time and the account the
            // data belongs to, which login binds the pending sign-in to.
            flagStore.begin(sourceLabel(source), currentVerifyAccount)
            publish("importing", 20)
            // currentVerifyAccount is the migrating account's own uid, already matched against the
            // manifest above, and the importer uses it so the room-state recompute excludes own-sent
            // messages from the unread count — the role globalServices.myId plays on the live path.
            // A well-formed container carries exactly one identity record, emitted first; the last one
            // wins defensively. Held in memory only, never staged.
            var carriedIdentity: NeutralIdentity? = null
            val staging = importToStaging(
                containerFile, passphrase, paths, stagingFactory, currentVerifyAccount,
                identitySink = { carriedIdentity = it },
            )

            // Promote and finalize, shared with the streaming path; promote is the single commit. The
            // shared tail re-checks isFreshTarget immediately before it, so a device that became
            // non-fresh during the import discards staging instead of clobbering the live DB.
            promoteAndFinalize(staging, paths, flagStore, work, promoteStep, isFreshTarget, carriedIdentity)
                ?.let { rejected -> _progress.update { null }; return@withContext rejected }
            L.i { "[MigrationV2][Restore] complete → phase=SIGNING_IN (A5 to continue signIn + restart)" }
            _progress.update { null }
            RestoreResult.ReadyForSignIn
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Restore] cancelled" }
            cleanupOnFailure(paths, flagStore, work)
            throw e
        } catch (e: Throwable) {
            val error = MigrationError.from(e)
            L.e { "[MigrationV2][Restore] failed code=${error.code}: ${e.stackTraceToString()}" }
            cleanupOnFailure(paths, flagStore, work)
            _progress.update { null }
            RestoreResult.Failed(error)
        }
    }

    /**
     * The receiver-side twin of [restore], consuming a forward-only [source] — the de-chunked P2P
     * container body — through [ContainerReader.readStreaming] instead of materializing and two-pass
     * reading a `.ttmig` file. It reuses, rather than reimplements, the staging open, [NeutralImporter],
     * [PromoteStep], promote-marker and cleanup machinery [restore] uses.
     *
     * Every data-safety guard of the file path is preserved:
     *  - The same net-install signal is checked before any record reaches staging and re-checked
     *    immediately before promote, and a receiver that is or becomes non-fresh is rejected with the
     *    same [MigrationError.TargetNotEmpty] the file path returns.
     *  - Account match is fail-fast: the manifest is the first frame, so `onManifest` compares its
     *    `sourceAccount` to [currentVerifyAccount] before the record loop pours anything into staging,
     *    and a mismatch throws, discards staging and never promotes.
     *  - Format-version compatibility is self-gated inside [ContainerReader.readStreaming], before any
     *    record is emitted, by the same [FormatCompat] rule the file path applies.
     *  - Environment isolation is enforced earlier, at the P2P handshake: the receiver compares the
     *    sender's declared environment against its own and hard-rejects a mismatch before any session
     *    exists, so no container byte reaches this method. The manifest carries no environment field,
     *    so this method does not re-compare it.
     *  - App-lock passcode is structurally absent from the neutral schema, so it is never in the stream.
     *  - Promote is gated on `readStreaming` returning normally. Records stream into the discardable
     *    staging DB as they decrypt, and any throw — tamper, truncation, HMAC failure, account mismatch
     *    — propagates out of [importStreamToStaging], discards staging and never reaches promote.
     *
     * @param source forward-only container byte stream; not closed here.
     * @param transferKey the ephemeral per-transfer key from the handshake, fed to
     *   [ContainerReader.readStreaming] in the passphrase position. Never logged.
     * @param currentVerifyAccount the logging-in account the manifest's `sourceAccount` must match, and
     *   also the importer's own-uid for the room-state recompute.
     */
    suspend fun restoreFromStream(
        source: InputStream,
        transferKey: String,
        currentVerifyAccount: String,
        paths: RestorePaths = RestorePaths.forContext(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
        promoteStep: PromoteStep = PromoteStep(context),
        stagingFactory: (stagingDbPath: String) -> StagingDatabase = { StagingDatabase.create(context, it) },
        isFreshTarget: () -> Boolean = migrationImportLauncher::isFreshInstallTarget,
        // Transfer progress in [0,1] from the manifest totals: records consumed and attachment bytes
        // landed against what the source announced. Silent when it announced none.
        onProgress: (Float) -> Unit = {},
        // Same-device no-login handoff: the container is expected to carry a session credential, and
        // this install adopts it right after promote so it comes up already signed in. A handoff
        // stream carrying NO credential is rejected before promote (see [promoteAndFinalize]).
        credentialHandoff: Boolean = false,
        // What the peer reads as "still working" through GET /status: stage, record count, attachment
        // bytes as they land, and the commit point. Distinct from onProgress, this device's own bar.
        liveness: TransferProgressReporter = TransferProgressReporter.None,
    ): RestoreResult = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Restore] streaming orchestrator start (P2P no-disk)" }
        val work = File(paths.containerWorkFile).parentFile
        try {
            // Publish the stage early so the UI sequence matches the file path. This path has no
            // materialize-and-peek pass, but it still validates, so "validating" is the right first
            // signal.
            publish("validating", 5)

            // Net-install guard before accepting the stream: a receiver with an established account is
            // rejected up front, with no staging opened and no bytes consumed. Returned as Rejected
            // rather than a thrown Failed, so the caller shows a clear reason and no RESTORING residue
            // is left. Account match and format compatibility are enforced inside the stream read.
            if (!isFreshTarget()) {
                L.w { "[MigrationV2][Restore] streaming rejected: target already has an established account (net-install-only)" }
                cleanupWork(work)
                return@withContext RestoreResult.Rejected(MigrationError.TargetNotEmpty)
            }

            // Import into staging by streaming; begin() writes the phase bound to the account.
            flagStore.begin("p2p-stream", currentVerifyAccount)
            publish("importing", 20)
            liveness.stage(TransferStages.IMPORTING)
            // currentVerifyAccount is both the account-match target and the importer's own uid, the
            // same dual use as the file path. A mismatch throws from the early-manifest hook before any
            // record reaches staging, so staging is discarded and nothing is promoted.
            var carriedIdentity: NeutralIdentity? = null
            // Memory only, never staged, never logged — the same seam the identity record rides.
            var carriedCredential: NeutralCredential? = null
            val staging = importStreamToStaging(
                source, transferKey, paths, stagingFactory, currentVerifyAccount,
                identitySink = { carriedIdentity = it },
                credentialSink = if (credentialHandoff) ({ carriedCredential = it }) else null,
                onProgress = onProgress,
                liveness = liveness,
            )

            // Promote and finalize — the identical tail to the file path. Promote is the single commit
            // point and is reached only because readStreaming returned normally with a verified HMAC.
            // The shared tail re-checks isFreshTarget immediately before it, so a device that became
            // non-fresh during a long stream import discards staging instead of clobbering the live DB.
            promoteAndFinalize(
                staging, paths, flagStore, work, promoteStep, isFreshTarget, carriedIdentity,
                credentialHandoff = credentialHandoff, carriedCredential = carriedCredential,
                liveness = liveness,
            )?.let { rejected -> _progress.update { null }; return@withContext rejected }
            L.i { "[MigrationV2][Restore] streaming complete → phase=SIGNING_IN (A5/I4 to continue signIn + restart)" }
            _progress.update { null }
            RestoreResult.ReadyForSignIn
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Restore] streaming cancelled" }
            cleanupOnFailure(paths, flagStore, work)
            throw e
        } catch (e: Throwable) {
            // As in the reader: a stream closed by a cancel fails before the cancellation is delivered.
            if (!currentCoroutineContext().isActive) {
                L.w { "[MigrationV2][Restore] streaming cancelled (stream closed)" }
                cleanupOnFailure(paths, flagStore, work)
                _progress.update { null }
                currentCoroutineContext().ensureActive()
            }
            val error = MigrationError.from(e)
            L.e { "[MigrationV2][Restore] streaming failed code=${error.code}: ${e.stackTraceToString()}" }
            cleanupOnFailure(paths, flagStore, work)
            _progress.update { null }
            RestoreResult.Failed(error)
        }
    }

    // ── validation chain ────────────────────────────────────────────────────────────────────────

    /**
     * [ContainerReader.peekManifest] authenticates, decrypts and decodes the manifest; this then checks
     * format-version compatibility and account match. Each reject maps to a stable [MigrationError];
     * a pass returns null and the caller proceeds to import.
     */
    private suspend fun validate(
        containerFile: File,
        passphrase: String,
        currentVerifyAccount: String,
    ): RestoreResult? {
        // Authenticate, decrypt and read the manifest. An HMAC mismatch means a wrong passphrase or a
        // tampered file; DecryptionFailed means bad magic, version, truncation or corruption.
        val manifest: ContainerManifest = try {
            containerReader.peekManifest(containerFile, passphrase)
        } catch (e: MigrationException.HmacMismatch) {
            // The file carries only salt, KDF and HMAC, so a mismatch cannot distinguish a wrong
            // passphrase from tampering. Surface PassphraseInvalid, by far the common cause;
            // FileCorrupted is reserved for structural failures.
            return RestoreResult.Rejected(MigrationError.PassphraseInvalid)
        } catch (e: MigrationException.DecryptionFailed) {
            return RestoreResult.Rejected(MigrationError.FileCorrupted)
        }

        // Format-version compatibility: a cross-MAJOR container is a hard reject. A newer MAJOR gets
        // its own error so the UI can show upgrade guidance instead of a dead end.
        if (!isFormatCompatible(manifest.formatVersion)) {
            L.w { "[MigrationV2][Restore] incompatible formatVersion=${manifest.formatVersion} supported=$SUPPORTED_FORMAT_VERSION" }
            val error = if (FormatCompat.isNewerThanSupported(manifest.formatVersion)) {
                MigrationError.VersionTooNew
            } else {
                MigrationError.UnsupportedVersion
            }
            return RestoreResult.Rejected(error)
        }

        // Account match: the manifest's sourceAccount against the current verify account. A
        // cross-account import is rejected.
        if (!accountMatches(manifest.sourceAccount, currentVerifyAccount)) {
            L.w { "[MigrationV2][Restore] account mismatch (manifest vs current verify account)" }
            return RestoreResult.Rejected(MigrationError.AccountMismatch)
        }

        L.i { "[MigrationV2][Restore] validation passed formatVersion=${manifest.formatVersion}" }
        return null
    }

    /**
     * Cross-MAJOR compatibility check, delegating to the single-owner [FormatCompat.isCompatible] so
     * every gate applies the identical rule.
     */
    private fun isFormatCompatible(manifestFormatVersion: String): Boolean =
        FormatCompat.isCompatible(manifestFormatVersion)

    /**
     * Account-match comparison: both sides are the same account-identity string, compared verbatim
     * after trimming. The raw values are never logged.
     */
    private fun accountMatches(manifestAccount: String, currentVerifyAccount: String): Boolean =
        manifestAccount.isNotBlank() &&
            currentVerifyAccount.isNotBlank() &&
            manifestAccount.trim() == currentVerifyAccount.trim()

    // ── import ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Open a fresh [StagingDatabase], run the [NeutralImporter] over the decrypted record stream, and
     * stream attachment bytes into the staging attachment dir. Returns the staging DB closed, so its
     * WAL is checkpointed into the db file and it is promote-ready. On any failure the staging DB is
     * discarded and the exception propagates.
     */
    private suspend fun importToStaging(
        containerFile: File,
        passphrase: String,
        paths: RestorePaths,
        stagingFactory: (stagingDbPath: String) -> StagingDatabase,
        selfUid: String,
        identitySink: (NeutralIdentity) -> Unit,
    ): StagingDatabase {
        resetAttachmentsStagingDir(paths)
        val staging = stagingFactory(paths.stagingDbPath)
        try {
            val importer = NeutralImporter(staging, selfUid = selfUid, identitySink = identitySink)
            var attachmentFiles = 0
            var attachmentWriteFailures = 0
            containerReader.read(
                file = containerFile,
                passphrase = passphrase,
                attachmentTarget = AttachmentByteTarget { contentRef, declaredLength ->
                    // Bytes of a skipped unknown record are consumed and authenticated but never
                    // persisted, so no orphan staging files are left.
                    if (importer.isSkippedBytes(contentRef)) return@AttachmentByteTarget null
                    // An empty frame carries nothing decryptable. Persisting it leaves a 0-byte at-rest
                    // file that reads as a corrupt attachment forever, while writing no file leaves the
                    // row pointer-only and the app re-downloads it.
                    if (declaredLength <= 0L) {
                        L.w { "[MigrationV2][Restore] empty attachment frame ref=$contentRef — not persisted" }
                        importer.onAttachmentBytesAbsent(contentRef)
                        return@AttachmentByteTarget null
                    }
                    attachmentFiles++
                    // Canonical `<ownerKey>/<fileName>` under the staging dir, so promote lands the bytes
                    // where the app reads them. With no placement the write falls back to a flat name.
                    // The failure hook fires on both an open failure and a mid-write one, and the row
                    // has no file either way, so it is also where the row is corrected.
                    openStagingAttachment(paths, contentRef, importer.placementFor(contentRef)) {
                        attachmentWriteFailures++
                        importer.onAttachmentBytesAbsent(contentRef)
                    }
                },
                onRecord = { record ->
                    currentCoroutineContext().ensureActive()
                    importer.onRecord(record)
                },
            )
            importer.finish()
            // Checkpoint and close, so the staging db file is self-contained and promote-ready. A
            // checkpoint failure throws and staging is discarded below.
            staging.close()
            L.i { "[MigrationV2][Restore] import → staging complete attachments=$attachmentFiles writeFailures=$attachmentWriteFailures" }
            return staging
        } catch (e: Throwable) {
            runCatching { staging.discard() }
            throw e
        }
    }

    /**
     * The streaming twin of [importToStaging]: the same staging open, the same [NeutralImporter], the
     * same canonical attachment subtree. The only difference is the record source —
     * [ContainerReader.readStreaming] over a forward-only [source] instead of a random-access file.
     *
     * Verify-before-commit: records pour into the discardable staging DB as they decrypt, before the
     * end-of-stream HMAC is known. [ContainerReader.readStreaming] throws on an HMAC mismatch, a
     * truncation, a cross-MAJOR manifest or an account mismatch from [onManifest], and on any throw this
     * method discards staging and re-throws, so the commit is never reached. It returns the closed
     * staging DB only on a fully verified stream.
     *
     * @param selfUid both the importer's own uid and the manifest account-match target; [onManifest]
     *   compares `manifest.sourceAccount` to it before any record is emitted and throws on mismatch.
     */
    private suspend fun importStreamToStaging(
        source: InputStream,
        transferKey: String,
        paths: RestorePaths,
        stagingFactory: (stagingDbPath: String) -> StagingDatabase,
        selfUid: String,
        identitySink: (NeutralIdentity) -> Unit,
        credentialSink: ((NeutralCredential) -> Unit)?,
        onProgress: (Float) -> Unit = {},
        liveness: TransferProgressReporter = TransferProgressReporter.None,
    ): StagingDatabase {
        resetAttachmentsStagingDir(paths)
        val staging = stagingFactory(paths.stagingDbPath)
        try {
            val importer = NeutralImporter(
                staging, selfUid = selfUid, identitySink = identitySink, credentialSink = credentialSink,
                onImported = liveness::recordsImported,
            )
            val progress = ProgressReporter(onProgress)
            var attachmentFiles = 0
            var attachmentWriteFailures = 0
            containerReader.readStreaming(
                source = source,
                passphrase = transferKey, // R3: ephemeral key in the passphrase position; NEVER logged
                attachmentTarget = AttachmentByteTarget { contentRef, declaredLength ->
                    // As on the file path above. The bytes still arrive and still count toward the
                    // total the manifest announced.
                    if (importer.isSkippedBytes(contentRef)) {
                        progress.attachmentBytes(declaredLength)
                        liveness.attachmentBytesLanded(declaredLength)
                        return@AttachmentByteTarget null
                    }
                    // As on the file path: never persist an empty frame as a 0-byte file.
                    if (declaredLength <= 0L) {
                        L.w { "[MigrationV2][Restore] empty attachment frame ref=$contentRef — not persisted" }
                        importer.onAttachmentBytesAbsent(contentRef)
                        return@AttachmentByteTarget null
                    }
                    attachmentFiles++
                    // Same placement as the file path. Counted per write so a peer reading /status sees
                    // a long attachment land as progress; a file that cannot open still counts as drained.
                    val out = openStagingAttachment(paths, contentRef, importer.placementFor(contentRef)) {
                        attachmentWriteFailures++
                        importer.onAttachmentBytesAbsent(contentRef)
                    }
                    if (out == null) {
                        progress.attachmentBytes(declaredLength)
                        liveness.attachmentBytesLanded(declaredLength)
                        return@AttachmentByteTarget null
                    }
                    CountingOutputStream(out) { landed ->
                        progress.attachmentBytes(landed)
                        liveness.attachmentBytesLanded(landed)
                    }
                },
                onRecord = { record ->
                    currentCoroutineContext().ensureActive()
                    importer.onRecord(record)
                    progress.recordDone()
                },
                onManifest = { manifest ->
                    // Fail-fast account match before any record reaches staging. This path has no
                    // peekManifest pre-pass, and the manifest is the first frame, so this hook is the
                    // earliest possible identity gate; a mismatch throws and staging is discarded.
                    assertManifestAccountMatch(manifest.sourceAccount, selfUid)
                    // The manifest is the first frame, so from here the bar has its denominator.
                    progress.start(ContainerTotals.fromManifest(manifest))
                },
            )
            importer.finish()
            staging.close()
            L.i { "[MigrationV2][Restore] stream import → staging complete attachments=$attachmentFiles writeFailures=$attachmentWriteFailures" }
            return staging
        } catch (e: Throwable) {
            runCatching { staging.discard() }
            throw e
        }
    }

    /**
     * The shared tail for both [restore] and [restoreFromStream]: advance to PROMOTING, run the
     * [PromoteStep] critical section — the single commit point, which owns its own marker and cold-start
     * recovery — then advance to SIGNING_IN and clean the work area. Promote is reached only after a
     * verified import, so no unverified data is ever committed.
     *
     * The net-install check is re-taken immediately before promote. The first check happens before the
     * import, and a large import can take minutes, during which a concurrent sign-in could establish an
     * account and write the live DB; promoting then would clobber it. So a device that became non-fresh
     * has its staging discarded and gets the same [MigrationError.TargetNotEmpty] rejection the early
     * guard returns, without promote ever being called. Returns null when the re-check passed and
     * promote ran.
     *
     * [carriedIdentity] is installed into secure storage right after that re-check and before promote.
     * The ordering is crash-safe in both directions: installed but crashed before promote, the retry
     * re-installs the same bytes idempotently; crashed after promote, the identity is already persisted
     * so the resumed sign-in publishes the original one. Placing it after the re-check means a rejected
     * restore never touches secure storage. The identity is deliberately not carried in the promote
     * marker — a private key must not land in a plaintext file, and this ordering already makes resume
     * correct.
     */
    private suspend fun promoteAndFinalize(
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
    ): RestoreResult? {
        // Re-check: a device that became non-fresh during the import must not be promoted onto, which
        // would overwrite the now-live DB. Discard staging, clear the pre-promote flag and reject as the
        // early guard does. No marker exists yet, so clearing the flag leaves no cold-start residue.
        if (!isFreshTarget()) {
            L.w { "[MigrationV2][Restore] pre-promote re-check failed: target became non-fresh during import — discard, no promote" }
            runCatching { staging.discard() }
            runCatching { flagStore.clear() }
            cleanupWork(work)
            return RestoreResult.Rejected(MigrationError.TargetNotEmpty)
        }
        // Install the identity before promote — see this method's kdoc. The material is checked first:
        // sign-in's first request registers this device and revokes the old one while its second decodes
        // this identity, so a key that cannot decode strands the user on both devices.
        val identityToInstall = carriedIdentity?.let { carried ->
            try {
                CarriedIdentityNormalizer.normalize(carried)
            } catch (e: CarriedIdentityNormalizer.InvalidIdentity) {
                L.w { "[MigrationV2][Restore] carried identity unusable (${e.message}) — discard, no promote" }
                runCatching { staging.discard() }
                runCatching { flagStore.clear() }
                cleanupWork(work)
                return RestoreResult.Rejected(MigrationError.IdentityInvalid)
            }
        }
        if (identityToInstall != null) {
            installIdentity(identityToInstall)
        } else {
            L.i { "[MigrationV2][Restore] container carried no identity — login will generate one (pre-1.1 behaviour)" }
        }
        // A no-login handoff whose container carried no credential must not be promoted: the premise is
        // that this device comes up signed in, and the sender would read a 200 as licence to wipe its own
        // session. Rejected before promote, exactly like an unusable identity.
        if (credentialHandoff && carriedCredential == null) {
            L.w { "[MigrationV2][Restore] handoff container carried no credential — discard, no promote" }
            runCatching { staging.discard() }
            runCatching { flagStore.clear() }
            cleanupWork(work)
            return RestoreResult.Rejected(MigrationError.CredentialMissing)
        }
        // Past this line a peer's /cancel can undo nothing; it is answered `too_late` and the promote
        // runs to its end. Every reject that could still stop this import is above.
        liveness.committing()
        flagStore.advance(RestorePhase.PROMOTING)
        publish("promoting", 80)
        promoteStep.promote(
            stagingDbPath = paths.stagingDbPath,
            stagingKey = staging.stagingKey,
            attachmentsStagingDir = paths.attachmentsStagingDir,
            realDbPath = paths.realDbPath,
            realAttachmentDir = paths.realAttachmentDir,
        )
        flagStore.advance(RestorePhase.SIGNING_IN)
        // The credential lands after promote: the database it unlocks has to be on disk first, and a
        // crash before this point leaves a device that can still finish on the ordinary login path. A
        // skipped install means a sign-in raced it, so reject and let the sender keep its credential.
        if (carriedCredential != null) {
            if (credentialInstaller.install(carriedCredential) is CredentialInstaller.Result.SkippedNotFresh) {
                cleanupWork(work)
                return RestoreResult.Rejected(MigrationError.TargetNotEmpty)
            }
            // No sign-in runs on this path, so nothing else would advance the flag. The two writes live
            // in different stores and cannot be atomic; a crash between them is resolved at startup by
            // `RestoreFlagStore.consumeSigningInFor`.
            flagStore.advance(RestorePhase.COMPLETED)
        }
        publish("ready", 100)
        cleanupWork(work)
        return null
    }

    /**
     * Fail-fast account-match assertion for the streaming path, mirroring the file path's [accountMatches].
     * A mismatch throws, so the read propagates it before any record is committed and staging is
     * discarded. The raw account values are compared but never logged.
     */
    private fun assertManifestAccountMatch(manifestAccount: String, currentVerifyAccount: String) {
        if (!accountMatches(manifestAccount, currentVerifyAccount)) {
            L.w { "[MigrationV2][Restore] streaming account mismatch (manifest vs logging-in account) — reject" }
            throw MigrationException.RestoreFailed("streaming container account does not match the logging-in account")
        }
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
     * is what makes "crashed after promote implies identity persisted" literally true.
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

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Materialize [source] to [workPath]. A source already backed by a stable local file is read in
     * place; a forward-only SAF source is copied to the work file for random access.
     */
    private fun materialize(source: ArchiveSource, workPath: String): File {
        source.asFile()?.let { return it }
        val workFile = File(workPath)
        workFile.parentFile?.mkdirs()
        source.openStream().use { input ->
            FileOutputStream(workFile).use { out -> input.copyTo(out) }
        }
        return workFile
    }

    /** A best-effort short label for the flag; never the raw URI. */
    private fun sourceLabel(source: ArchiveSource): String =
        source.asFile()?.name ?: "saf-source"

    /**
     * Resolve the staging-dir destination for an attachment's bytes.
     *
     * With a canonical placement from the importer, write into the relative `<ownerKey>/<fileName>`
     * subtree, each component sanitized, so the promote dir-move lands the bytes where the app
     * reconstructs the path at load time. The owner-key directory is created up front.
     *
     * The stream carries the at-rest ciphertext verbatim, so the file must land at the ciphertext
     * address, not the bare plaintext one: readers resolve the ciphertext by name, and the legacy
     * plaintext purge treats anything else as plaintext to encrypt. Writing it at the plaintext address
     * makes readers feed ciphertext to the decoder and makes the purge encrypt it a second time. The
     * base name stays whatever the record carried; only the address is derived here.
     *
     * With no placement known — an orphan or blank ref, or an owner with no key — this falls back to a
     * flat write, which is harmless because the importer leaves the row's path null in that case. The
     * fallback still runs [safeName], keeping the blank-ref guard.
     */
    private fun stagingAttachmentFile(
        stagingDir: String,
        contentRef: String,
        placement: NeutralImporter.AttachmentPlacement?,
    ): File {
        val base = if (placement != null) {
            val ownerDir = File(stagingDir, safeName(placement.ownerKey)).apply { mkdirs() }
            File(ownerDir, safeName(placement.fileName))
        } else {
            File(stagingDir, safeName(contentRef))
        }
        // Ask the at-rest layout itself for the ciphertext address rather than re-spelling the suffix:
        // writer, reader and the legacy purge must never be able to drift apart.
        return EncryptedAttachmentAccess.encryptedFile(base.absolutePath)
    }

    /**
     * Make [raw] usable as a single path component without renaming it.
     *
     * The attachment must land under the exact name the record carries, because the reader derives its
     * on-disk path from `AttachmentModel.fileName`. Scrubbing everything outside `[A-Za-z0-9._-]`
     * silently renames every non-ASCII attachment, and the bytes are then on disk where no reader can
     * find them: the attachment presents as not downloaded and falls back to a re-download whose server
     * pointer has long expired.
     *
     * Only characters that genuinely cannot be a path component are substituted — separators, NUL, and
     * the few external-storage rejects — while CJK, spaces and parentheses are preserved verbatim. A
     * substitution is logged, since that is the one case where the on-disk name and the DB name diverge.
     * A blank name, or one that reduces to a directory reference, gets a generated name instead:
     * `File(dir, "")` resolves to the directory itself and the open would fail opaquely.
     */
    private fun safeName(raw: String): String =
        AttachmentFileNaming.asPathComponent(raw) ?: run {
            L.w { "[MigrationV2][Restore] attachment name unusable as a path component, substituted" }
            "attachment-${UUID.randomUUID()}"
        }

    /**
     * Open the staging file for [contentRef]'s bytes. A file that cannot be opened or written costs
     * only that attachment: [onFailure] counts it, the frame is drained, and the missing file leaves
     * the row re-downloadable. Null means drain without storing.
     */
    private fun openStagingAttachment(
        paths: RestorePaths,
        contentRef: String,
        placement: NeutralImporter.AttachmentPlacement?,
        onFailure: () -> Unit,
    ): OutputStream? {
        val file = stagingAttachmentFile(paths.attachmentsStagingDir, contentRef, placement)
        val raw = try {
            FileOutputStream(file)
        } catch (e: IOException) {
            L.w { "[MigrationV2][Restore] attachment open failed ref=$contentRef: ${e.javaClass.simpleName}: ${e.message}" }
            onFailure()
            return null
        }
        return FaultIsolatingOutputStream(file, raw, onFailure)
    }

    private fun publish(stage: String, percent: Int) {
        _progress.update { RestoreProgress(stage, percent) }
    }

    /** Delete the transient work area — container copy and staging products — after success or reject. */
    private fun cleanupWork(work: File?) {
        runCatching { work?.deleteRecursively() }
    }

    /**
     * Failure cleanup: discard the staging products and the work area. The restore flag is left alone
     * when a promote marker exists, since [PromoteStep] owns that recovery; otherwise the failure was
     * before or during import, so the flag is cleared and no stale RESTORING residue is left.
     */
    /**
     * Start an import from a known-empty staging attachment dir.
     *
     * `cleanupOnFailure` clears it on every failure the orchestrator can observe, but a process death
     * mid-import runs no catch block at all, leaving an abandoned attempt's bytes on disk with nothing
     * to remove them. Wiping on entry gives the attachment side the same guarantee `StagingDatabase`
     * already has, and keeps a retry from promoting a mix of two attempts' bytes. Best-effort: a delete
     * failure must not block the retry.
     *
     * Attachments used to stage under `filesDir`; an attempt interrupted under such a build leaves
     * bytes there that the current work-dir wipe no longer reaches, so that location is reclaimed too.
     */
    private fun resetAttachmentsStagingDir(paths: RestorePaths) {
        val dir = File(paths.attachmentsStagingDir)
        runCatching { if (dir.exists()) dir.deleteRecursively() }
            .onFailure { e -> L.w { "[MigrationV2][Restore] staging attachment dir wipe failed: ${e.stackTraceToString()}" } }
        dir.mkdirs()
        val legacy = File(File(context.filesDir, RestorePaths.WORK_DIR_NAME), RestorePaths.ATTACHMENTS_DIR_NAME)
        if (legacy != dir) runCatching { if (legacy.exists()) legacy.deleteRecursively() }
    }

    private fun cleanupOnFailure(paths: RestorePaths, flagStore: RestoreFlagStore, work: File?) {
        // The whole cleanup is best-effort and must not mask the original typed failure: the marker
        // probe and flag.clear() do file IO that can throw under the same storage condition that caused
        // it, which would lose the original result.
        runCatching {
            runCatching { StagingDatabase.deleteStagingArtifacts(paths.stagingDbPath) }
            runCatching { File(paths.attachmentsStagingDir).deleteRecursively() }
            // A live marker means promote owns recovery; SIGNING_IN/COMPLETED means promote finished and
            // the data is on disk, so the flag must survive for the sign-in to resume. Anything else
            // failed before promote, so clear it.
            val promoted = flagStore.currentPhase().let { it == RestorePhase.SIGNING_IN || it == RestorePhase.COMPLETED }
            if (!PromoteMarkerStore(context).exists() && !promoted) {
                flagStore.clear()
            }
            cleanupWork(work)
        }.onFailure { e ->
            L.w { "[MigrationV2][Restore] cleanupOnFailure secondary failure (original error preserved): ${e.stackTraceToString()}" }
        }
    }

    companion object {
        /**
         * The container format version this app supports, single-sourced from
         * [FormatCompat.SUPPORTED_FORMAT_VERSION] and retained here for the log line.
         */
        const val SUPPORTED_FORMAT_VERSION = FormatCompat.SUPPORTED_FORMAT_VERSION
    }
}
