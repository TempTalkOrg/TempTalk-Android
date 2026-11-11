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

















@Singleton
class RestoreOrchestrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val containerReader: ContainerReader,
    




    private val migrationImportLauncher: MigrationImportLauncher,
    




    private val userManager: UserManager,
    




    private val credentialInstaller: CredentialInstaller,
) {

    private val _progress = MutableStateFlow<RestoreProgress?>(null)

    
    val progress: StateFlow<RestoreProgress?> = _progress.asStateFlow()

    










    suspend fun restore(
        source: ArchiveSource,
        passphrase: String,
        currentVerifyAccount: String,
        paths: RestorePaths = RestorePaths.forContext(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
        promoteStep: PromoteStep = PromoteStep(context),
        stagingFactory: (stagingDbPath: String) -> StagingDatabase = { StagingDatabase.create(context, it) },
        isFreshTarget: () -> Boolean = migrationImportLauncher::isFreshInstallTarget,
        storageGuard: StorageGuard = StorageGuard.forContext(context),
        commitTail: RestoreCommitTail = defaultCommitTail(),
    ): RestoreResult = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Restore] orchestrator start" }
        val work = File(paths.containerWorkFile).parentFile
        try {
            
            
            val containerFile = materialize(source, paths.containerWorkFile)
            publish("validating", 5)

            
            val validation = validate(containerFile, passphrase, currentVerifyAccount)
            if (validation is RestoreResult.Rejected) {
                L.w { "[MigrationV2][Restore] rejected at validation: ${validation.error.code}" }
                cleanupWork(work)
                return@withContext validation
            }

            
            
            
            
            
            if (!isFreshTarget()) {
                L.w { "[MigrationV2][Restore] rejected: target already has an established account (net-install-only)" }
                cleanupWork(work)
                return@withContext RestoreResult.Rejected(MigrationError.TargetNotEmpty)
            }

            
            
            flagStore.begin(sourceLabel(source), currentVerifyAccount)
            publish("importing", 20)
            
            
            
            
            
            var carriedIdentity: NeutralIdentity? = null
            val staging = importToStaging(
                containerFile, passphrase, paths, stagingFactory, currentVerifyAccount,
                identitySink = { carriedIdentity = it },
            )

            
            
            
            storageGuard.requireHeadroom("import-tail")
            commitTail.run(
                staging, paths, flagStore, work, promoteStep, isFreshTarget, carriedIdentity,
                storageGuard = storageGuard,
            )?.let { rejected -> _progress.update { null }; return@withContext rejected }
            L.i { "[MigrationV2][Restore] complete → phase=SIGNING_IN (A5 to continue signIn + restart)" }
            _progress.update { null }
            RestoreResult.ReadyForSignIn
        } catch (e: MigrationException.CommittedUnconfirmed) {
            
            
            _progress.update { null }
            throw e
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

    





























    suspend fun restoreFromStream(
        source: InputStream,
        transferKey: String,
        currentVerifyAccount: String,
        paths: RestorePaths = RestorePaths.forContext(context),
        flagStore: RestoreFlagStore = RestoreFlagStore(context),
        promoteStep: PromoteStep = PromoteStep(context),
        stagingFactory: (stagingDbPath: String) -> StagingDatabase = { StagingDatabase.create(context, it) },
        isFreshTarget: () -> Boolean = migrationImportLauncher::isFreshInstallTarget,
        
        
        onProgress: (Float) -> Unit = {},
        
        
        
        credentialHandoff: Boolean = false,
        
        
        liveness: TransferProgressReporter = TransferProgressReporter.None,
        
        
        livenessSessionId: String? = liveness.bodySessionId(),
        storageGuard: StorageGuard = StorageGuard.forContext(context),
        commitTail: RestoreCommitTail = defaultCommitTail(),
    ): RestoreResult = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Restore] streaming orchestrator start (P2P no-disk)" }
        val work = File(paths.containerWorkFile).parentFile
        try {
            
            
            
            publish("validating", 5)

            
            
            
            
            if (!isFreshTarget()) {
                L.w { "[MigrationV2][Restore] streaming rejected: target already has an established account (net-install-only)" }
                cleanupWork(work)
                return@withContext RestoreResult.Rejected(MigrationError.TargetNotEmpty)
            }

            
            flagStore.begin("p2p-stream", currentVerifyAccount)
            publish("importing", 20)
            liveness.stage(TransferStages.IMPORTING)
            
            
            
            var carriedIdentity: NeutralIdentity? = null
            
            var carriedCredential: NeutralCredential? = null
            val staging = importStreamToStaging(
                source, transferKey, paths, stagingFactory, currentVerifyAccount,
                identitySink = { carriedIdentity = it },
                credentialSink = if (credentialHandoff) ({ carriedCredential = it }) else null,
                onProgress = onProgress,
                liveness = liveness,
                storageGuard = storageGuard,
            )

            
            
            
            
            storageGuard.requireHeadroom("import-tail")
            commitTail.run(
                staging, paths, flagStore, work, promoteStep, isFreshTarget, carriedIdentity,
                credentialHandoff = credentialHandoff, carriedCredential = carriedCredential,
                liveness = liveness, livenessSessionId = livenessSessionId, storageGuard = storageGuard,
            )?.let { rejected -> _progress.update { null }; return@withContext rejected }
            L.i { "[MigrationV2][Restore] streaming complete → phase=SIGNING_IN (A5/I4 to continue signIn + restart)" }
            _progress.update { null }
            RestoreResult.ReadyForSignIn
        } catch (e: MigrationException.CommittedUnconfirmed) {
            
            
            
            _progress.update { null }
            throw e
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Restore] streaming cancelled" }
            cleanupOnFailure(paths, flagStore, work)
            throw e
        } catch (e: Throwable) {
            
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

    

    




    private suspend fun validate(
        containerFile: File,
        passphrase: String,
        currentVerifyAccount: String,
    ): RestoreResult? {
        
        
        val manifest: ContainerManifest = try {
            containerReader.peekManifest(containerFile, passphrase)
        } catch (e: MigrationException.HmacMismatch) {
            
            
            
            return RestoreResult.Rejected(MigrationError.PassphraseInvalid)
        } catch (e: MigrationException.DecryptionFailed) {
            return RestoreResult.Rejected(MigrationError.FileCorrupted)
        }

        
        
        if (!isFormatCompatible(manifest.formatVersion)) {
            L.w { "[MigrationV2][Restore] incompatible formatVersion=${manifest.formatVersion} supported=$SUPPORTED_FORMAT_VERSION" }
            val error = if (FormatCompat.isNewerThanSupported(manifest.formatVersion)) {
                MigrationError.VersionTooNew
            } else {
                MigrationError.UnsupportedVersion
            }
            return RestoreResult.Rejected(error)
        }

        
        
        if (!accountMatches(manifest.sourceAccount, currentVerifyAccount)) {
            L.w { "[MigrationV2][Restore] account mismatch (manifest vs current verify account)" }
            return RestoreResult.Rejected(MigrationError.AccountMismatch)
        }

        L.i { "[MigrationV2][Restore] validation passed formatVersion=${manifest.formatVersion}" }
        return null
    }

    



    private fun isFormatCompatible(manifestFormatVersion: String): Boolean =
        FormatCompat.isCompatible(manifestFormatVersion)

    



    private fun accountMatches(manifestAccount: String, currentVerifyAccount: String): Boolean =
        manifestAccount.isNotBlank() &&
            currentVerifyAccount.isNotBlank() &&
            manifestAccount.trim() == currentVerifyAccount.trim()

    

    





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
                    
                    
                    if (importer.isSkippedBytes(contentRef)) return@AttachmentByteTarget null
                    
                    
                    
                    if (declaredLength <= 0L) {
                        L.w { "[MigrationV2][Restore] empty attachment frame ref=$contentRef — not persisted" }
                        importer.onAttachmentBytesAbsent(contentRef)
                        return@AttachmentByteTarget null
                    }
                    attachmentFiles++
                    
                    
                    
                    
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
            
            
            staging.close()
            L.i { "[MigrationV2][Restore] import → staging complete attachments=$attachmentFiles writeFailures=$attachmentWriteFailures" }
            return staging
        } catch (e: Throwable) {
            runCatching { staging.discard() }
            throw e
        }
    }

    













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
        storageGuard: StorageGuard? = null,
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
            
            
            
            var sinceStorageCheck = 0
            containerReader.readStreaming(
                source = source,
                passphrase = transferKey, 
                attachmentTarget = AttachmentByteTarget { contentRef, declaredLength ->
                    
                    
                    if (importer.isSkippedBytes(contentRef)) {
                        progress.attachmentBytes(declaredLength)
                        liveness.attachmentBytesLanded(declaredLength)
                        return@AttachmentByteTarget null
                    }
                    
                    if (declaredLength <= 0L) {
                        L.w { "[MigrationV2][Restore] empty attachment frame ref=$contentRef — not persisted" }
                        importer.onAttachmentBytesAbsent(contentRef)
                        return@AttachmentByteTarget null
                    }
                    attachmentFiles++
                    
                    
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
                    if (++sinceStorageCheck >= STORAGE_CHECK_EVERY_RECORDS) {
                        sinceStorageCheck = 0
                        storageGuard?.requireHeadroom("import")
                    }
                },
                onManifest = { manifest ->
                    
                    
                    
                    assertManifestAccountMatch(manifest.sourceAccount, selfUid)
                    
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

    




    private fun assertManifestAccountMatch(manifestAccount: String, currentVerifyAccount: String) {
        if (!accountMatches(manifestAccount, currentVerifyAccount)) {
            L.w { "[MigrationV2][Restore] streaming account mismatch (manifest vs logging-in account) — reject" }
            throw MigrationException.RestoreFailed("streaming container account does not match the logging-in account")
        }
    }

    

    



    private fun materialize(source: ArchiveSource, workPath: String): File {
        source.asFile()?.let { return it }
        val workFile = File(workPath)
        workFile.parentFile?.mkdirs()
        source.openStream().use { input ->
            FileOutputStream(workFile).use { out -> input.copyTo(out) }
        }
        return workFile
    }

    
    private fun sourceLabel(source: ArchiveSource): String =
        source.asFile()?.name ?: "saf-source"

    
















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
        
        
        return EncryptedAttachmentAccess.encryptedFile(base.absolutePath)
    }

    














    private fun safeName(raw: String): String =
        AttachmentFileNaming.asPathComponent(raw) ?: run {
            L.w { "[MigrationV2][Restore] attachment name unusable as a path component, substituted" }
            "attachment-${UUID.randomUUID()}"
        }

    




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

    
    private fun cleanupWork(work: File?) {
        runCatching { work?.deleteRecursively() }
    }

    




    




    private fun resetAttachmentsStagingDir(paths: RestorePaths) {
        val dir = File(paths.attachmentsStagingDir)
        runCatching { if (dir.exists()) dir.deleteRecursively() }
            .onFailure { e -> L.w { "[MigrationV2][Restore] staging attachment dir wipe failed: ${e.stackTraceToString()}" } }
        dir.mkdirs()
        val legacy = File(File(context.filesDir, RestorePaths.WORK_DIR_NAME), RestorePaths.ATTACHMENTS_DIR_NAME)
        if (legacy != dir) runCatching { if (legacy.exists()) legacy.deleteRecursively() }
    }

    private fun cleanupOnFailure(paths: RestorePaths, flagStore: RestoreFlagStore, work: File?) {
        
        
        
        runCatching {
            
            
            
            
            
            val committed = PromoteMarkerStore(context).exists() ||
                flagStore.currentPhase().let { it == RestorePhase.SIGNING_IN || it == RestorePhase.COMPLETED }
            if (committed) {
                L.w { "[MigrationV2][Restore] failure after the commit boundary — keeping staging, flag and marker for recovery" }
                return
            }
            runCatching { StagingDatabase.deleteStagingArtifacts(paths.stagingDbPath) }
            runCatching { File(paths.attachmentsStagingDir).deleteRecursively() }
            flagStore.clear()
            cleanupWork(work)
        }.onFailure { e ->
            L.w { "[MigrationV2][Restore] cleanupOnFailure secondary failure (original error preserved): ${e.stackTraceToString()}" }
        }
    }

    
    private fun defaultCommitTail(): RestoreCommitTail = RestoreCommitTail(
        context = context,
        userManager = userManager,
        credentialInstaller = credentialInstaller,
        publish = ::publish,
        cleanupWork = ::cleanupWork,
    )

    companion object {
        



        const val SUPPORTED_FORMAT_VERSION = FormatCompat.SUPPORTED_FORMAT_VERSION

        
        const val STORAGE_CHECK_EVERY_RECORDS = 2_000
    }
}
