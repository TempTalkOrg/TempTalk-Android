package com.difft.android.migration.v2.transfer.send

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.snapshot.BackupSnapshotter
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.migration.v2.container.TransferProgress
import com.difft.android.migration.v2.export.NeutralExporterImpl
import com.difft.android.migration.v2.export.NeutralProjections
import com.difft.android.migration.v2.export.SnapshotReadSource
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.transfer.P2pContainerFusion
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.VerdictWatch
import com.difft.android.p2p.engine.model.QrPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.io.File
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.migration.v2.transfer.MigrationEnvironment

/**
 * The send driver on the old device. It reads the live account and this build's environment inside
 * [sendRealExport], at send time, and passes them to [P2pContainerFusion.sendRealExport] with a
 * non-null source environment, so a real handshake goes out in prepare-upload and the receiver's gate
 * fires. Identity is always read live, never carried in an Intent extra that could be logged or
 * dumped, and the ephemeral transfer key is generated inside the fusion and never surfaced here.
 *
 * The export reads a consistent point-in-time snapshot rather than the live database, and the
 * snapshot is closed and deleted in `finally` on every exit, so the live database and the
 * message-send path are never disturbed.
 */
class MigrationP2pSendController(
    context: Context,
    writer: ContainerWriter,
    reader: ContainerReader,
    crypto: BackupCrypto,
    private val snapshotter: BackupSnapshotter,
    private val environmentHelper: EnvironmentHelper,
    private val userManager: StorageBoundUserManager,
    private val appVersion: String,
    // Built by default from the engine dependencies; injectable so unit tests can assert the real
    // account, environment and handshake arguments without standing up native sockets or WCDB.
    private val fusion: P2pContainerFusion = P2pContainerFusion(writer = writer, reader = reader, crypto = crypto),
    // Defaults to the real WCDB-backed factory; injectable so unit tests avoid loading the
    // native-WCDB-referencing SnapshotReadSource class, which throws UnsatisfiedLinkError on the JVM.
    private val openReadSource: (Context, String) -> SnapshotReadSource = { ctx, path ->
        SnapshotReadSource.open(ctx, path)
    },
    // Defaults to the real exporter's row enumeration; injectable so unit tests avoid the native-WCDB
    // query builders, for the same JVM reason as `openReadSource` above.
    private val preCount: suspend (NeutralExporterImpl) -> ContainerTotals = { it.preCount() },
) {

    private val appContext = context.applicationContext

    /**
     * Streams this device's export to the scanned [target] over chunked P2P, stamping the live account,
     * environment and handshake so the receiver's gate fires. Runs on the caller's [scope]; no local
     * scope is created or cancelled.
     *
     * @return true only if the upload returned 200, which the receiver delays until it has verified. A
     *   gate reject throws `PrepareUploadRejectedException` out of the fusion.
     */
    suspend fun sendRealExport(
        scope: CoroutineScope,
        target: QrPayload,
        onProgress: (Float) -> Unit,
        onBodySent: () -> Unit = {},
        // The user's choice on the pairing page: carry attachment bytes, or leave them behind as
        // re-downloadable pointers (voice notes always travel). Also shapes the manifest totals.
        includeAttachments: Boolean = true,
        // Same-device no-login handoff: also carry the session credential, and declare the mode in the
        // handshake so a receiver that will not install it refuses before any byte.
        credentialHandoff: Boolean = false,
        // The id minted and persisted by the FGS before this call; travels inside the credential
        // record so both sides can later agree on which handoff completed.
        handoffId: String = "",
        // Desktop link, account switch: keep knocking on `prepare-upload` while the Desktop waits for
        // its user's confirmation; [onAwaitingPeerConfirm] fires once when that wait is detected.
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        // The upload 200's headers: the receiver's explicit acknowledgments, read by the FGS before
        // it acts on a handoff.
        onReply: (Map<String, String>) -> Unit = {},
        // The verdict wait beyond the upload connection — see [P2pContainerFusion.sendRealExport].
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
        onVerdictStall: (Boolean) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        // Read the live account first and fail fast before taking a snapshot, so a device that is
        // unexpectedly not logged in never sends an empty source account nor does a pointless snapshot
        // and round-trip. The raw account is never logged, only whether it was empty.
        val userData = userManager.getUserData()
        val account = userData?.account
        if (account.isNullOrEmpty()) {
            L.w { "[MigrationV2][P2P][Tx] send aborted — no live account (not logged in)" }
            error("not logged in")
        }
        val environment = MigrationEnvironment.wireName(environmentHelper)
        // Identity continuity: read live from secure storage — never an Intent extra, never logged —
        // and prepend to the record stream so the new device installs the original identity before
        // login publishes one. Only the production send carries identity; the debug demo sender
        // streams a synthetic account and must not ship real key material.
        val identity = NeutralProjections.identity(userData)
        // Session credential, for the same-device no-login handoff only. Read live from secure storage
        // and never logged, not even in a way that could be correlated with a value.
        val credential = if (credentialHandoff) NeutralProjections.credential(userData, handoffId) else null
        // Fail before the snapshot: a handoff that cannot carry a complete credential must not send a
        // single byte, or the receiver would promote a database the user has no session for while this
        // device waits for a receipt that can never be honest.
        if (credentialHandoff && (credential == null || identity == null)) {
            L.w { "[MigrationV2][P2P][Tx] handoff aborted — credential or identity unavailable" }
            error(HANDOFF_CREDENTIAL_UNAVAILABLE)
        }
        // Do not log the raw account or environment, only that the handshake will be sent.
        L.i { "[MigrationV2][P2P][Tx] production send start handshake=true handoff=$credentialHandoff" }

        // Snapshot the live DB for a consistent read (reuse the production snapshot wiring).
        val snapshot: File = snapshotter.takeSnapshot()
        var readSource: SnapshotReadSource? = null
        try {
            readSource = openReadSource(appContext, snapshot.absolutePath)
            val exporter = NeutralExporterImpl(readSource).apply {
                selfUid = account
                this.includeAttachments = includeAttachments
            }
            // Exact totals for the manifest, which both ends draw their bars from. A read-only walk over
            // the snapshot, with no file reads and no re-encode; it must finish before streaming starts
            // because it rides in the first frame. The identity record prepended below is not counted.
            val counted = preCount(exporter)
            val extraRecords = (if (identity == null) 0 else 1) + (if (credential == null) 0 else 1)
            val totals = counted.copy(recordCount = counted.recordCount + extraRecords)
            L.i {
                "[MigrationV2][P2P][Tx] totals records=${totals.recordCount} " +
                    "attachments=${totals.attachmentCount} attachmentBytes=${totals.attachmentBytes} " +
                    "includeAttachments=$includeAttachments"
            }
            // Advisory FileMetadata.size, for a peer that still draws from it: put it on the same
            // nominal scale as the formula so it at least ends near 100% instead of 3× over.
            val estimatedTotal = totals.recordCount * TransferProgress.RECORD_WEIGHT_BYTES + totals.attachmentBytes
            // `onStart` emits the identity record immediately after the manifest frame, then the
            // credential; both are memory-sink records that never reach the staging database.
            val records = exporter.export().let { dbRecords ->
                if (identity == null && credential == null) {
                    dbRecords
                } else {
                    dbRecords.onStart {
                        if (identity != null) emit(NeutralRecord.Identity(identity))
                        if (credential != null) emit(NeutralRecord.Credential(credential))
                    }
                }
            }
            fusion.sendRealExport(
                target = target,
                scope = scope,
                records = records,
                attachmentSource = exporter.attachmentByteSource(),
                sourceAccount = account, // REAL — gate's account-match target
                sourceAppVersion = appVersion, // BuildConfig.VERSION_NAME
                sourceEnvironment = environment, // REAL → fusion builds + sends the MigrationHandshake
                sourceFormatVersion = FormatCompat.SUPPORTED_FORMAT_VERSION,
                onProgress = onProgress,
                onBodySent = onBodySent,
                onReply = onReply,
                estimatedTotalBytes = estimatedTotal,
                totals = totals,
                senderAlias = DeviceNames.local(appContext),
                credentialHandoff = if (credentialHandoff) true else null,
                awaitPeerConfirm = awaitPeerConfirm,
                onAwaitingPeerConfirm = onAwaitingPeerConfirm,
                onSessionPrepared = onSessionPrepared,
                verdictWatch = verdictWatch,
                onVerdictStall = onVerdictStall,
            )
        } finally {
            // Resource cleanup only; the caller's scope is not cancelled here. Close the second database
            // handle, then delete the sensitive plaintext snapshot, on every exit path including
            // cancellation, where this `finally` still runs.
            runCatching { readSource?.close() }
                .onFailure { L.w { "[MigrationV2][P2P][Tx] snapshot close failed: ${it.message}" } }
            if (snapshot.exists()) {
                runCatching { snapshot.delete() }
                    .onFailure { L.w { "[MigrationV2][P2P][Tx] snapshot delete failed: ${it.message}" } }
            }
        }
    }

    companion object {
        /**
         * Abort token raised when a handoff send cannot assemble a complete credential (or identity).
         * Travels in the transfer state's reason string like the handshake reject tokens.
         */
        const val HANDOFF_CREDENTIAL_UNAVAILABLE = "handoff-credential-unavailable"
    }
}
