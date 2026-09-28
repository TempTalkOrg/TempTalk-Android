package com.difft.android.migration.v2.transfer

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.v2.container.AttachmentByteTarget
import com.difft.android.migration.v2.container.ContainerManifest
import com.difft.android.migration.v2.container.ContainerReader
import com.difft.android.migration.v2.container.ContainerTotals
import com.difft.android.migration.v2.container.ContainerWriter
import com.difft.android.migration.v2.container.ProgressReporter
import com.difft.android.migration.v2.container.counting
import com.difft.android.migration.v2.model.NeutralRecord
import com.difft.android.migration.v2.restore.RestoreOrchestrator
import com.difft.android.migration.v2.restore.RestoreResult
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.android.server.ReceiverHttpServer
import com.difft.android.p2p.android.tls.SelfSignedCert
import com.difft.android.p2p.engine.model.DeviceInfo
import com.difft.android.p2p.engine.model.FileMetadata
import com.difft.android.p2p.engine.model.MigrationHandshake
import com.difft.android.migration.v2.container.FormatCompat
import com.difft.android.p2p.engine.model.QrPayload
import com.difft.android.p2p.engine.session.PinPolicy
import com.difft.android.p2p.engine.session.SessionManager
import com.difft.android.p2p.engine.session.TerminalRecordStore
import com.difft.android.p2p.engine.session.TransferProgressReporter
import com.difft.android.p2p.engine.upload.MigrationHandshakeValidator
import com.difft.android.p2p.engine.upload.UploadReplyHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.UUID

/**
 * Joins the migration container engine to the `:p2p` transport so a container streams device to
 * device with no `.ttmig` file on either side. It is the only class that touches both, keeping the
 * module edge one-way (`:app → :p2p`).
 *
 * Receivers stand up the HTTPS server, mint the ephemeral transfer key and route the upload body
 * straight into a [P2pContainerReceiveSink]. Senders open a [P2pContainerPipe] and run the writer
 * and the uploader on separate coroutines — a pipe deadlocks if both run on one. The transfer key is
 * a live decryption key and is never logged here.
 */
class P2pContainerFusion(
    private val writer: ContainerWriter,
    private val reader: ContainerReader,
    private val crypto: BackupCrypto,
) {

    /**
     * Handle returned by the receiver starters: the QR to render plus the live receive outcome. [R] is
     * the consumer's outcome type — a [ContainerManifest] for the byte pipe, a [RestoreResult] for the
     * real-import receiver.
     */
    class ReceiverSession<R> internal constructor(
        qrPayload: QrPayload,
        val transferKey: String,
        private val server: ReceiverHttpServer,
        private val receiveSink: P2pContainerReceiveSink<R>,
        private val pinPolicy: PinPolicy,
    ) : ReceiverLinger.Listener {
        /** The pairing payload to show; [rotatePin] replaces its PIN. */
        @Volatile
        var qrPayload: QrPayload = qrPayload
            private set

        /** Issues a fresh pairing PIN, invalidating the old one at once, and returns the payload to show. */
        fun rotatePin(): QrPayload {
            val next = qrPayload.copy(pin = pinPolicy.issue())
            qrPayload = next
            return next
        }

        /** Suspends until the body is streamed, HMAC-verified and consumed, or throws on failure. */
        suspend fun awaitOutcome(): R = receiveSink.result.await()

        /** Keep answering for the finished session only; a new `prepare-upload` is refused. */
        override fun closeToNewSessions() {
            server.closeToNewSessions()
        }

        /** Tears down the server and its sessions. Idempotent. */
        override fun stop() {
            runCatching { server.stop() }
        }
    }

    /**
     * Starts the byte-pipe receiver: HTTPS server, ephemeral transfer key in the QR, upload body read
     * straight into `ContainerReader.readStreaming`. The consumer returns the verified manifest; this
     * is the byte pipe, not the real import (see [startRestoreReceiver]).
     *
     * @param ip the LAN IP advertised in the QR and in the cert SAN.
     * @param scope the scope the streaming reader runs on; the caller owns its lifecycle.
     * @param cert the receiver's self-signed cert, whose fingerprint the sender pins.
     */
    fun startReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit = {},
        port: Int = ReceiverHttpServer.DEFAULT_PORT,
        requirePin: Boolean = true,
    ): ReceiverSession<ContainerManifest> = bringUpReceiver(
        ip = ip,
        scope = scope,
        cert = cert,
        alias = alias,
        onEvent = onEvent,
        port = port,
        requirePin = requirePin,
        identity = null,            // byte-pipe (I1/I2): no advertised identity, no gate
        migrationValidator = null,
    ) { transferKey, _ ->
        // I1/I2: read the stream straight into readStreaming; the outcome is the verified manifest.
        P2pContainerReceiveSink<ContainerManifest>(scope = scope) { input ->
            reader.readStreaming(input, transferKey, attachmentTarget, onRecord = onRecord)
        }
    }

    /**
     * Starts the real-import receiver: the upload body goes straight into
     * [RestoreOrchestrator.restoreFromStream], which reads, stages, runs the net-install and account
     * guards, and promotes. The 200 fires only on [RestoreResult.ReadyForSignIn]; a rejection returns
     * normally but maps to a non-200, so the sender is never told the migration completed.
     *
     * @param currentVerifyAccount the logging-in account the manifest's `sourceAccount` must match,
     *   and the importer's `selfUid`.
     */
    fun startRestoreReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        orchestrator: RestoreOrchestrator,
        currentVerifyAccount: String,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit = {},
        port: Int = ReceiverHttpServer.DEFAULT_PORT,
        requirePin: Boolean = true,
        // This device's advertised identity for the QR, and the pre-transfer gate run at
        // prepare-upload. Both null on the byte-pipe path, where the QR carries no identity and no
        // gate runs; non-null on the production login-triggered receive.
        identity: ReceiverIdentity? = null,
        migrationValidator: MigrationHandshakeValidator? = null,
        // Receive progress in [0,1] from the manifest totals, reported by the restore orchestrator as
        // it consumes records and attachment bytes; silent when the source wrote no totals.
        onProgress: (Float) -> Unit = {},
        // The whole body is in and only the import's verdict is outstanding: the receive bar has
        // nothing left to show, so the screen moves on before the import tail and the promote run.
        onBodyComplete: () -> Unit = {},
        // Same-device no-login handoff only: supplies the account the gate adopted from the sender's
        // handshake. Read when the body starts, strictly after the gate accepted, so a single reference
        // is race-free; the orchestrator re-confirms it against the HMAC'd manifest.
        credentialHandoffAccount: (() -> String)? = null,
        // Where this receiver remembers how its sessions ended, so `/status` can still answer after
        // the upload connection is gone. Null keeps the memory in-process only (tests).
        terminalStore: TerminalRecordStore? = null,
        // Every LAN address this device can be reached at, [ip] first (see `NetworkUtils.lanCandidates`).
        ips: List<String> = listOf(ip),
    ): ReceiverSession<RestoreResult> = bringUpReceiver(
        ip = ip,
        ips = ips,
        scope = scope,
        cert = cert,
        alias = alias,
        onEvent = onEvent,
        port = port,
        requirePin = requirePin,
        identity = identity,
        migrationValidator = migrationValidator,
        terminalStore = terminalStore,
    ) { transferKey, liveness ->
        P2pContainerReceiveSink<RestoreResult>(
            scope = scope,
            // Only a promoted import confirms the 200; a guard rejection must not.
            isSuccess = { it is RestoreResult.ReadyForSignIn },
            // The handoff sender wipes itself on the receipt, so the receipt must say the credential is
            // installed; ReadyForSignIn on that path is reached only after the install.
            successHeaders = { outcome ->
                if (credentialHandoffAccount != null && outcome is RestoreResult.ReadyForSignIn) {
                    mapOf(UploadReplyHeaders.HANDOFF_INSTALLED to "true")
                } else {
                    emptyMap()
                }
            },
            onBodyComplete = onBodyComplete,
            // Not the byte counter: it would measure against the advertised size, which is only an
            // estimate, and fight the exact figure the orchestrator reports from the manifest.
        ) { input ->
            // The transferKey rides in the passphrase position and is never logged.
            val account = credentialHandoffAccount?.invoke()?.takeIf { it.isNotBlank() } ?: currentVerifyAccount
            orchestrator.restoreFromStream(
                source = input,
                transferKey = transferKey,
                currentVerifyAccount = account,
                onProgress = onProgress,
                credentialHandoff = credentialHandoffAccount != null,
                liveness = liveness,
            )
        }
    }

    /**
     * Shared receiver bring-up for both starters: mint the ephemeral transfer key, build the
     * [sinkFactory]'s sink over it, stand up the HTTPS server, and return the QR plus the session.
     * The two callers differ only in what runs over the body stream. The transfer key is never logged.
     */
    private fun <R> bringUpReceiver(
        ip: String,
        scope: CoroutineScope,
        cert: SelfSignedCert,
        alias: String,
        onEvent: (ReceiverHttpServer.ServerEvent) -> Unit,
        port: Int,
        requirePin: Boolean,
        identity: ReceiverIdentity?,
        migrationValidator: MigrationHandshakeValidator?,
        terminalStore: TerminalRecordStore? = null,
        // Every address the code advertises, [ip] first; the connecting side races them.
        ips: List<String> = listOf(ip),
        sinkFactory: (transferKey: String, liveness: TransferProgressReporter) -> P2pContainerReceiveSink<R>,
    ): ReceiverSession<R> {
        val transferKey = generateTransferKey()
        val pinPolicy = PinPolicy()
        val pinCode = pinPolicy.issue()

        // The session manager is also what the import reports its progress into, so it exists
        // before the sink that will do the reporting.
        val sessionManager = SessionManager(terminalStore = terminalStore)
        val receiveSink = sinkFactory(transferKey, sessionManager)

        val deviceInfo = DeviceInfo(alias = alias, fingerprint = cert.sha256Fingerprint, port = port)
        val server = ReceiverHttpServer(
            cert = cert,
            deviceInfo = deviceInfo,
            sessionManager = sessionManager,
            pinPolicy = pinPolicy,
            fileSink = receiveSink,
            requirePin = requirePin,
            onEvent = onEvent,
            // I4: the env/account/version fail-fast gate (null on the byte-pipe path → no gate).
            migrationValidator = migrationValidator,
        )
        // A same-device handoff advertises loopback alone, so the listener stays off the LAN. It binds
        // the advertised literal itself: `InetAddress.getLoopbackAddress()` is `::1` on Android, which a
        // peer dialling 127.0.0.1 cannot reach. A cross-device receiver advertises several LAN addresses
        // and keeps the wildcard bind.
        val loopbackOnly = ips.isNotEmpty() && ips.all { it == PeerApps.LOOPBACK_IP }
        val boundPort = server.start(port, if (loopbackOnly) InetAddress.getByName(PeerApps.LOOPBACK_IP) else null)

        val qr = QrPayload(
            ip = ip,
            ips = ips,
            port = boundPort,
            fp = cert.sha256Fingerprint,
            pin = pinCode,
            alias = alias,
            transferKey = transferKey, // rides in the QR; never logged
            // Advertise this device's identity so the sender can pre-check on scan; the authoritative
            // check is still the receiver gate at prepare-upload. Null on the byte-pipe path.
            environment = identity?.environment,
            account = identity?.account,
            appVersion = identity?.appVersion,
            formatVersion = identity?.formatVersion,
        )
        L.i { "[MigrationV2][P2P][Rx] receiver up ip=$ip port=$boundPort requirePin=$requirePin gate=${migrationValidator != null}" }
        return ReceiverSession(qr, transferKey, server, receiveSink, pinPolicy)
    }

    /**
     * The receiver's advertised migration identity, placed in the [QrPayload] so the sender can
     * pre-check on scan. The authoritative check is still [MigrationHandshakeGate] at prepare-upload.
     * Not secret, but not logged raw either.
     */
    data class ReceiverIdentity(
        val environment: String,
        val account: String,
        val appVersion: String,
        val formatVersion: String,
    )

    /**
     * Streams a synthesized container to the scanned [target], keying the writer with the QR's
     * transfer key and failing fast when the QR carries none. The length is pre-counted, so this path
     * frames the upload with a known `Content-Length`.
     *
     * @return true only if the upload returned 200, which the receiver delays until it has verified.
     */
    suspend fun sendContainer(
        target: QrPayload,
        scope: CoroutineScope,
        records: List<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        onProgress: (Float) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val transferKey = target.transferKey
            ?: error("QR has no transferKey — not a migration receiver")

        // Pre-count the exact container length by dry-encoding through a counting sink, so this path
        // can stream with a known Content-Length.
        val length = countContainerLength(records, attachmentSource, transferKey, sourceAccount, sourceAppVersion)
        L.i { "[MigrationV2][P2P][Tx] container length=$length records=${records.size} framing=content-length" }

        streamThroughPipe(
            target = target,
            transferKey = transferKey,
            declaredLength = length,
            // Content-Length path: the real length is the progress denominator, so no estimate is needed.
            estimatedTotalBytes = 0L,
            records = records.asFlow(),
            attachmentSource = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
            handshake = null, // synthesized byte-pipe (tests): no gate on its receiver
            onProgress = onProgress,
            verdictWatch = verdictWatch,
        )
    }

    /**
     * Streams a real export read from the live database to the scanned [target], with no temp file
     * anywhere. Same wiring as [sendContainer] except the length is not known up front — pre-counting
     * would dry-encode the whole database twice — so the upload is framed chunked.
     *
     * @param records the neutral record stream, read-only over the live database.
     * @param attachmentSource attachments stream from their live on-disk locations, never copied.
     * @return true only if the upload returned 200, which the receiver delays until it has verified.
     */
    suspend fun sendRealExport(
        target: QrPayload,
        scope: CoroutineScope,
        records: Flow<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        onProgress: (Float) -> Unit = {},
        // Fires once the whole body is on the wire; from then on only the verdict is outstanding.
        onBodySent: () -> Unit = {},
        // The 200's headers — the receiver's explicit acknowledgments ([UploadReplyHeaders]).
        onReply: (Map<String, String>) -> Unit = {},
        // This device's source environment under its wire name. When non-null a handshake is sent in
        // prepare-upload so the receiver's gate can fail fast; null means no handshake is sent.
        sourceEnvironment: String? = null,
        sourceFormatVersion: String = FormatCompat.SUPPORTED_FORMAT_VERSION,
        // Approximate progress denominator (snapshot DB size plus carried attachment lengths). The wire
        // stays chunked; this only rides in the advisory metadata size and drives both ends' bars.
        estimatedTotalBytes: Long = 0L,
        // Exact totals for the manifest and both ends' bars; null falls back to the byte estimate above.
        totals: ContainerTotals? = null,
        // What the receiver shows as "receiving from …"; the production controller passes the real
        // device name.
        senderAlias: String = "migration-sender",
        // Same-device no-login handoff: declared in the handshake so the receiver's gate can refuse a
        // mode disagreement before any byte. Null on every other path, and omitted from the wire.
        credentialHandoff: Boolean? = null,
        // Desktop account switch: retry `prepare-upload` through the Desktop's confirmation wait
        // instead of failing on the first transport timeout.
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        // The session the receiver opened for this send, as soon as it is known — what the owner
        // needs to ask `/status` or `/cancel` on a connection of its own.
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        // When present and the receiver serves `/status`, the verdict is read from there once the
        // body is sent, and the upload connection's own response stops being the only word.
        verdictWatch: VerdictWatch? = null,
        // The receiver's reported progress stopped moving (true) or resumed (false).
        onVerdictStall: (Boolean) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        val transferKey = target.transferKey
            ?: error("QR has no transferKey — not a migration receiver")
        L.i {
            "[MigrationV2][P2P][Tx] real export start framing=chunked (no pre-count, no disk) " +
                "handshake=${sourceEnvironment != null} estimatedTotalBytes=$estimatedTotalBytes"
        }

        streamThroughPipe(
            target = target,
            transferKey = transferKey,
            declaredLength = P2pContainerPipe.UNKNOWN_LENGTH,
            estimatedTotalBytes = estimatedTotalBytes,
            records = records,
            attachmentSource = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
            awaitPeerConfirm = awaitPeerConfirm,
            onAwaitingPeerConfirm = onAwaitingPeerConfirm,
            handshake = sourceEnvironment?.let {
                MigrationHandshake(
                    sourceEnvironment = it,
                    sourceAccount = sourceAccount,
                    sourceAppVersion = sourceAppVersion,
                    sourceFormatVersion = sourceFormatVersion,
                    credentialHandoff = credentialHandoff,
                )
            },
            onProgress = onProgress,
            onBodySent = onBodySent,
            onReply = onReply,
            totals = totals,
            senderAlias = senderAlias,
            onSessionPrepared = onSessionPrepared,
            verdictWatch = verdictWatch,
            onVerdictStall = onVerdictStall,
        )
    }

    /**
     * Shared sender mechanics for both send paths: open a [P2pContainerPipe], `prepare-upload`, then
     * run the writer (filling the pipe) and the uploader (draining it) on separate coroutines, since a
     * full pipe blocks the writer until the reader drains and one thread would deadlock. Both are
     * children of this call, so whichever fails first cancels the other and is what the caller sees.
     * [declaredLength] selects the framing: non-negative gives a `Content-Length`,
     * [P2pContainerPipe.UNKNOWN_LENGTH] gives chunked transfer-encoding.
     */
    private suspend fun streamThroughPipe(
        target: QrPayload,
        transferKey: String,
        declaredLength: Long,
        // Approximate progress denominator for the chunked path; 0 on the Content-Length path, where
        // `declaredLength` is both the real size and the denominator.
        estimatedTotalBytes: Long,
        records: Flow<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        handshake: MigrationHandshake?,
        awaitPeerConfirm: Boolean = false,
        onAwaitingPeerConfirm: () -> Unit = {},
        onProgress: (Float) -> Unit,
        onBodySent: () -> Unit = {},
        onReply: (Map<String, String>) -> Unit = {},
        senderAlias: String = "migration-sender",
        totals: ContainerTotals? = null,
        onSessionPrepared: (SenderSessionRef) -> Unit = {},
        verdictWatch: VerdictWatch? = null,
        onVerdictStall: (Boolean) -> Unit = {},
    ): Boolean {
        val pipe = P2pContainerPipe(declaredLength = declaredLength)
        val client = SenderHttpClient(target)
        val fileId = UUID.randomUUID().toString()
        // Advertised size in the prepare-upload metadata, which the session slot does not gate on: the
        // real container size on the Content-Length path, the estimate on the chunked path so the
        // receiver can drive its own bar. Advisory only — the receiver still de-chunks by
        // Transfer-Encoding.
        val advertisedSize =
            if (declaredLength >= 0L) declaredLength else estimatedTotalBytes.coerceAtLeast(0L)
        val meta = FileMetadata(
            id = fileId,
            fileName = pipe.fileSource.fileName,
            size = advertisedSize,
            fileType = pipe.fileSource.mimeType,
            sha256 = null, // container's own trailing HMAC is the integrity gate (not a pre-hash)
        )
        val self = DeviceInfo(alias = senderAlias, fingerprint = "tx-${fileId.take(8)}")
        // The handshake, when present, carries the source identity, so the receiver's gate can reject
        // with a typed 403 before any bytes flow.
        val prepared = if (awaitPeerConfirm) {
            PeerConfirmRetry.run(onWaiting = onAwaitingPeerConfirm) {
                client.prepareUpload(self, mapOf(fileId to meta), target.pin, handshake)
            }
        } else {
            client.prepareUpload(self, mapOf(fileId to meta), target.pin, handshake)
        }
        val token = prepared.files[fileId] ?: run {
            L.w { "[MigrationV2][P2P][Tx] no upload token for fileId" }
            return false
        }
        val session = SenderSessionRef(
            ip = target.ip,
            port = target.port,
            fingerprint = target.fp,
            sessionId = prepared.sessionId,
            sessionToken = prepared.sessionToken,
            caps = prepared.caps.orEmpty(),
        )
        L.i { "[MigrationV2][P2P][Tx] session prepared caps=${session.caps} statusToken=${session.sessionToken != null}" }
        onSessionPrepared(session)

        // Progress. With totals, count what the writer frames — records and attachment payload — against
        // the same totals the receiver reads from the manifest, so both bars run the one formula and end
        // together at 100%. The writer runs at most one pipe buffer (1 MiB) ahead of the wire, so this
        // leads the true upload by that much and no more. Without totals, the byte estimate below stands.
        val reporter = ProgressReporter(onProgress).also { it.start(totals) }
        val countedRecords = if (totals != null) records.onEach { reporter.recordDone() } else records
        val countedAttachments = if (totals != null) attachmentSource.counting(reporter::attachmentBytes) else attachmentSource

        // Writer and uploader on separate coroutines, both children of this scope so a failure in
        // either is contained. Whatever ends this early, the receiver is told by name.
        return abortingSessionOnFailure(client, prepared.sessionId, prepared.sessionToken) {
            coroutineScope {
                val writeJob = async(Dispatchers.IO) {
                    writer.write(
                        records = countedRecords,
                        sink = pipe.archiveSink,
                        passphrase = transferKey, // R3: ephemeral key in the passphrase position; never logged
                        attachmentBytes = countedAttachments,
                        sourceAccount = sourceAccount,
                        sourceAppVersion = sourceAppVersion,
                        totals = totals,
                    )
                }
                // The moment the last body byte left, for the verdict watch's clocks.
                val bodySent = CompletableDeferred<Long>()
                val uploadJob = async(Dispatchers.IO) {
                    runCatching {
                        client.upload(
                            sessionId = prepared.sessionId,
                            fileId = fileId,
                            token = token,
                            source = pipe.fileSource,
                            onProgress = { sent, total ->
                                if (totals != null) return@upload // the reporter above owns progress
                                // Prefer the real Content-Length, else the estimate on the chunked path; neither known
                                // leaves the bar indeterminate. The estimate is approximate, so coerce into [0,1].
                                val denominator = if (total > 0L) total else estimatedTotalBytes
                                val ratio = if (denominator > 0L) (sent.toFloat() / denominator).coerceIn(0f, 1f) else 0f
                                onProgress(ratio)
                            },
                            onBodySent = {
                                bodySent.complete(System.currentTimeMillis())
                                onBodySent()
                            },
                            onReply = onReply,
                        )
                    }.onFailure {
                        // The uploader is gone before it drained the pipe — it never opened the body, or
                        // was cancelled first. The writer may be parked on a full buffer, and cancelling
                        // its coroutine cannot wake a blocked write; closing the read end does.
                        pipe.abortRead()
                    }
                }
                // The receiver's own account of the session, on a second connection, from the moment
                // the body is in. Only with a receiver that serves it; a Desktop that does not keeps
                // the upload response as the one word, exactly as before.
                val watchJob = if (verdictWatch != null && session.supportsStatus) {
                    async(Dispatchers.IO) {
                        val sentAt = bodySent.await()
                        verdictWatch.run(
                            bodySentAt = sentAt,
                            status = { client.status(session.sessionId, session.sessionToken.orEmpty()) },
                            onStall = onVerdictStall,
                        )
                    }
                } else {
                    null
                }
                val ok = awaitVerdict(uploadJob, watchJob, bodySent)
                writeJob.await()
                L.i { "[MigrationV2][P2P][Tx] upload complete result=$ok" }
                ok
            }
        }
    }

    /**
     * Whichever speaks first decides — with two exceptions. An upload connection that ends without a
     * response after the body was sent (a timeout, a reset, a receiver too busy to answer) is not a
     * verdict: the watch on its own connection still is, so the wait goes on there. And a verdict
     * that is not `succeeded` is thrown as [SendVerdictException], so the caller can tell a receiver
     * that said `failed` from one that said nothing at all, and so no `/cancel` follows it.
     */
    private suspend fun awaitVerdict(
        uploadJob: Deferred<Result<Boolean>>,
        watchJob: Deferred<Verdict>?,
        bodySent: CompletableDeferred<Long>,
    ): Boolean {
        if (watchJob == null) return uploadJob.await().getOrThrow()
        val first: Any = select {
            uploadJob.onAwait { it }
            watchJob.onAwait { it }
        }
        if (first is Verdict) {
            L.i { "[MigrationV2][P2P][Tx] verdict from /status: $first" }
            uploadJob.cancel()
            return first.confirmed()
        }
        @Suppress("UNCHECKED_CAST")
        val result = first as Result<Boolean>
        return result.fold(
            onSuccess = { ok ->
                watchJob.cancel()
                ok
            },
            onFailure = { e ->
                if (e is kotlin.coroutines.cancellation.CancellationException || !bodySent.isCompleted) {
                    // Cancelled, or the body never fully left: there is nothing to ask the receiver about.
                    watchJob.cancel()
                    throw e
                }
                L.w { "[MigrationV2][P2P][Tx] upload connection ended before the verdict, asking /status: ${e.message}" }
                watchJob.await().confirmed()
            },
        )
    }

    private fun Verdict.confirmed(): Boolean =
        if (this is Verdict.Succeeded) true else throw SendVerdictException(this)

    /**
     * iOS parity: a sender that stops — cancelled, or its upload failed — names its session on
     * `POST /cancel`, so the receiver reads the abort as the source leaving rather than a dropped
     * link. The hang-up itself has already happened by then (cancelling the call closes the socket),
     * so the notice runs non-cancellable with its own short deadline, best-effort.
     */
    private suspend inline fun <T> abortingSessionOnFailure(
        client: SenderHttpClient,
        sessionId: String,
        sessionToken: String?,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: Throwable) {
            // A verdict the receiver itself gave, or a wait that ran out: the receiver is not to be
            // told to stop. On a timeout in particular a cancel could only turn "still importing" into
            // an ambiguity the status route exists to remove.
            if (e !is SendVerdictException) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(CANCEL_NOTICE_TIMEOUT_MS) { client.cancel(sessionId, sessionToken) }
                }
            }
            throw e
        }

    /** Dry-encodes the container through [ContainerSizeCounter] to learn its exact byte length. */
    private suspend fun countContainerLength(
        records: List<NeutralRecord>,
        attachmentSource: com.difft.android.migration.v2.container.AttachmentByteSource,
        transferKey: String,
        sourceAccount: String,
        sourceAppVersion: String,
    ): Long {
        val counter = ContainerSizeCounter()
        writer.write(
            records = records.asFlow(),
            sink = counter,
            passphrase = transferKey,
            attachmentBytes = attachmentSource,
            sourceAccount = sourceAccount,
            sourceAppVersion = sourceAppVersion,
        )
        return counter.byteCount
    }

    /** Generates the ephemeral 256-bit transfer key, base64-encoded for the QR. Never logged. */
    private fun generateTransferKey(): String =
        Base64.encodeToString(crypto.randomBytes(TRANSFER_KEY_BYTES), Base64.NO_WRAP)

    companion object {
        /** Length of the ephemeral transfer key. */
        const val TRANSFER_KEY_BYTES = 32

        /** How long the sender waits for its `/cancel` notice to land before giving up on it. */
        private const val CANCEL_NOTICE_TIMEOUT_MS = 3_000L
    }
}
