package com.difft.android.migration.v2.container

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.model.NeutralRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sink for attachment bytes streamed out of the container during a read, so an arbitrarily large
 * attachment never enters memory whole. Returning null from [openForRef] discards the bytes for that
 * ref; the reader still consumes and authenticates them, since they are inside the HMAC.
 */
fun interface AttachmentByteTarget {
    /** Open the destination stream for the attachment named [contentRef], or null to discard. */
    fun openForRef(contentRef: String, declaredLength: Long): OutputStream?
}

/**
 * Reads and authenticates the container produced by [ContainerWriter], the exact inverse of
 * [ContainerWriterImpl]. Runs on [Dispatchers.IO].
 *
 * Both read paths share [ContainerBodyReader] and [BackupCrypto]; they differ only in their
 * [CiphertextSource] and therefore in when the HMAC is verified.
 *
 * [read] and [peekManifest] have the whole random-access file, so they verify before they trust: a
 * first pass compares HMAC(header ‖ ciphertext) against the trailing MAC and only a pass lets the
 * second pass decrypt and emit. No record or attachment byte is ever surfaced from a wrong
 * passphrase or a tampered file.
 *
 * [readStreaming] has a forward-only stream, so a two-pass read is impossible: it emits frames as
 * they arrive and verifies at end-of-stream. That is safe only because the caller's commit point is
 * promote, gated on this method returning normally — see its kdoc.
 */
interface ContainerReader {

    /**
     * Read the container at [file], emit each [NeutralRecord] to [onRecord] in stream order, and
     * stream each attachment's bytes to the [OutputStream] [attachmentTarget] provides.
     *
     * @return the [ContainerManifest] embedded as the first frame, with counts tallied from the
     *   stream rather than the writer's advisory values.
     * @throws MigrationException.HmacMismatch wrong passphrase or tampered file (first-pass MAC).
     * @throws MigrationException.DecryptionFailed bad magic / version / truncated / malformed frame.
     */
    suspend fun read(
        file: File,
        passphrase: String,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest

    /**
     * Authenticate the container and decode only its manifest frame, surfacing no records or
     * attachment bytes, so the restore validation chain can run its format-version and account-match
     * checks before any record is imported. Runs the same verify-before-trust first pass as [read];
     * the rest of the body is never decrypted.
     *
     * The returned counts are the writer's advisory values, not [read]'s tally — enough for
     * validation, which inspects only the identity and version fields.
     *
     * @throws MigrationException.HmacMismatch wrong passphrase or tampered file (first-pass MAC).
     * @throws MigrationException.DecryptionFailed bad magic / version / truncated / malformed manifest.
     */
    suspend fun peekManifest(file: File, passphrase: String): ContainerManifest

    /**
     * Read and authenticate the container from a forward-only [source] (no seek, no known length):
     * decrypts frames as they arrive, emits each [NeutralRecord] to [onRecord] and each attachment's
     * bytes to [attachmentTarget], accumulates HMAC(header ‖ ciphertext), and at end-of-stream
     * finalizes and constant-time-compares the trailing HMAC. Only the [CiphertextSource] differs
     * from [read], so a stream the writer produced round-trips to exactly what [read] would accept.
     *
     * This path verifies before COMMIT, not before trust: records reach the importer before the
     * end-of-stream HMAC is known. That is safe only because [onRecord] writes to the discardable
     * staging DB and staging attachment dir, never the live DB, and the commit point is `promote`.
     * A tampered, corrupt or truncated stream may therefore surface records into staging, but this
     * method throws rather than returning success, so promote never fires. Do not change the caller
     * to promote on anything but this method's normal return.
     *
     * [onManifest] is invoked with the manifest — the first body frame — as soon as it is decrypted
     * and before anything is emitted, so a receiver can fail-fast on an account mismatch by throwing
     * from it with no record having reached staging. Format-version compatibility is always
     * self-gated here regardless of [onManifest], since this path has no external gate before the
     * promote commit.
     *
     * @param source forward-only container byte stream; not closed by this method.
     * @param passphrase the key fed to the KDF; for P2P this is the ephemeral transfer key.
     * @return the [ContainerManifest] with counts tallied from the stream, only after the HMAC
     *   verifies.
     * @throws MigrationException.HmacMismatch the end-of-stream HMAC did not match. The caller must
     *   discard staging and not promote.
     * @throws MigrationException.DecryptionFailed bad magic / version / malformed frame, or a
     *   cross-MAJOR container. The caller must discard staging and not promote.
     * @throws MigrationException.StreamInterrupted the stream ended early. The peer or the link, not
     *   a verdict on the data: nothing was verified. The caller must discard staging and not promote.
     */
    suspend fun readStreaming(
        source: InputStream,
        passphrase: String,
        attachmentTarget: AttachmentByteTarget,
        onManifest: suspend (ContainerManifest) -> Unit = {},
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest
}

@Singleton
class ContainerReaderImpl @Inject constructor(
    private val crypto: BackupCrypto,
) : ContainerReader {

    private val codec = RecordJsonCodec()

    override suspend fun read(
        file: File,
        passphrase: String,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Container] read start size=${file.length()}" }
        if (!file.exists()) {
            throw MigrationException.DecryptionFailed("container file does not exist: ${file.absolutePath}")
        }
        try {
            // 1. Parse header (plaintext) + derive keys once.
            val header = readHeaderBytes(file)
            val parsed = parseHeaderOrThrow(header)
            val keys = crypto.deriveKeys(passphrase, parsed.salt)

            // 2. FIRST PASS — authenticate header ‖ ciphertext against the trailing HMAC.
            //    No plaintext is produced or parsed here; a failure means wrong passphrase or
            //    tampering and we never reach the parse pass.
            verifyHmac(file, keys.macKey)

            // 3. SECOND PASS — bytes are now authenticated; decrypt + parse frames safely.
            val manifest = decryptAndParseFile(file, parsed, keys, attachmentTarget, onRecord)
            L.i { "[MigrationV2][Container] read done records=${manifest.recordCount} attachments=${manifest.attachmentCount}" }
            manifest
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Container] read cancelled" }
            throw e
        } catch (e: MigrationException) {
            L.e { "[MigrationV2][Container] read failed: ${e.stackTraceToString()}" }
            throw e
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Container] read failed: ${e.stackTraceToString()}" }
            throw MigrationException.DecryptionFailed("container read failed", e)
        }
    }

    override suspend fun peekManifest(file: File, passphrase: String): ContainerManifest =
        withContext(Dispatchers.IO) {
            L.i { "[MigrationV2][Container] peekManifest start size=${file.length()}" }
            if (!file.exists()) {
                throw MigrationException.DecryptionFailed("container file does not exist: ${file.absolutePath}")
            }
            try {
                val header = readHeaderBytes(file)
                val parsed = parseHeaderOrThrow(header)
                val keys = crypto.deriveKeys(passphrase, parsed.salt)
                // Same verify-before-trust first pass as read(): authenticate header ‖ ciphertext.
                verifyHmac(file, keys.macKey)
                // Decrypt ONLY the manifest frame (first body frame); the rest is never decrypted.
                val manifest = decryptManifestOnly(file, parsed, keys)
                L.i { "[MigrationV2][Container] peekManifest done formatVersion=${manifest.formatVersion}" }
                manifest
            } catch (e: CancellationException) {
                throw e
            } catch (e: MigrationException) {
                L.e { "[MigrationV2][Container] peekManifest failed: ${e.stackTraceToString()}" }
                throw e
            } catch (e: Throwable) {
                L.e { "[MigrationV2][Container] peekManifest failed: ${e.stackTraceToString()}" }
                throw MigrationException.DecryptionFailed("container peekManifest failed", e)
            }
        }

    override suspend fun readStreaming(
        source: InputStream,
        passphrase: String,
        attachmentTarget: AttachmentByteTarget,
        onManifest: suspend (ContainerManifest) -> Unit,
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Container] readStreaming start (forward-only, verify-before-commit)" }
        try {
            val input = if (source.markSupported()) source else BufferedInputStream(source, BackupCrypto.IO_BUFFER_BYTES)

            // 1. Read + parse the plaintext header from the FRONT of the stream, derive keys.
            val header = input.readExactly(BackupCrypto.HEADER_SIZE)
                ?: throw MigrationException.StreamInterrupted("stream ended before the header")
            val parsed = parseHeaderOrThrow(header)
            val keys = crypto.deriveKeys(passphrase, parsed.salt)

            // 2. Seed the MAC with the plaintext header (the writer signs header ‖ ciphertext).
            val mac: Mac = crypto.newHmacSha256(keys.macKey)
            mac.update(header)

            // 3. Decrypt + parse frames forward while the ciphertext source feeds the running MAC
            //    and holds back the trailing HMAC bytes. Records reach the staging callbacks before
            //    the HMAC is known — see the verify-before-commit note on readStreaming.
            val cipher: Cipher = crypto.newAesCtrCipher(Cipher.DECRYPT_MODE, keys.encKey, parsed.iv)
            val ctSource = HmacTailCiphertextSource(input, mac, BackupCrypto.HMAC_BYTES)
            val body = ContainerBodyReader(ctSource, cipher)
            // Self-gate on format version before any frame is emitted to staging. The file path
            // gates externally in RestoreOrchestrator.validate; this path has no external gate
            // before the promote commit, so it must reject a cross-MAJOR container here.
            val manifest = parseFrames(body, attachmentTarget, onRecord, gateFormatVersion = true, onManifest = onManifest)

            // 4. END sentinel reached. Finalize the MAC over the consumed ciphertext and constant-time
            //    compare to the trailing HMAC the source held back. THIS is the commit gate: on
            //    mismatch / truncation we throw, the caller discards staging, and nothing is promoted.
            val trailing = ctSource.trailingHmac()
            val expected = mac.doFinal()
            if (!crypto.constantTimeEquals(expected, trailing)) {
                L.w { "[MigrationV2][Container] readStreaming HMAC mismatch — wrong key or tampered stream" }
                throw MigrationException.HmacMismatch("streaming HMAC verification failed")
            }
            L.i { "[MigrationV2][Container] readStreaming done (HMAC verified) records=${manifest.recordCount} attachments=${manifest.attachmentCount}" }
            manifest
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Container] readStreaming cancelled" }
            throw e
        } catch (e: MigrationException) {
            L.e { "[MigrationV2][Container] readStreaming failed: ${e.stackTraceToString()}" }
            throw e
        } catch (e: IOException) {
            // The transport gave out before the trailer. A cancel closes the stream before the
            // coroutine is resumed with the cancellation, so that read fails first and is the cancel;
            // otherwise the peer or the link went away. Nothing was verified, so nothing is corrupt.
            if (!currentCoroutineContext().isActive) {
                L.w { "[MigrationV2][Container] readStreaming cancelled (stream closed)" }
                currentCoroutineContext().ensureActive()
            }
            L.w { "[MigrationV2][Container] readStreaming interrupted before the trailer: ${e.javaClass.simpleName}: ${e.message}" }
            throw MigrationException.StreamInterrupted("stream ended before its trailer", e)
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Container] readStreaming failed: ${e.stackTraceToString()}" }
            throw MigrationException.DecryptionFailed("container readStreaming failed", e)
        }
    }

    // ── file path internals (verify-before-trust) ───────────────────────────────────────────────

    private fun parseHeaderOrThrow(header: ByteArray): BackupCrypto.Header =
        try {
            crypto.parseHeader(header)
        } catch (e: IllegalArgumentException) {
            throw MigrationException.DecryptionFailed("header invalid: ${e.message ?: "unknown"}", e)
        }

    /**
     * Decrypt + parse ONLY the first (manifest) body frame of the already-authenticated file, then
     * stop. Shares the frame grammar via [ContainerBodyReader] over a [BoundedCiphertextSource]; the
     * rest of the body is never decrypted.
     */
    private fun decryptManifestOnly(
        file: File,
        header: BackupCrypto.Header,
        keys: BackupCrypto.KeyPair,
    ): ContainerManifest {
        val cipher: Cipher = crypto.newAesCtrCipher(Cipher.DECRYPT_MODE, keys.encKey, header.iv)
        val bodyEnd = file.length() - BackupCrypto.HMAC_BYTES
        BufferedInputStream(FileInputStream(file), BackupCrypto.IO_BUFFER_BYTES).use { raw ->
            raw.skipExactly(BackupCrypto.HEADER_SIZE.toLong())
            val body = ContainerBodyReader(
                BoundedCiphertextSource(raw, bodyEnd - BackupCrypto.HEADER_SIZE),
                cipher,
            )
            return readManifestFrame(body)
        }
    }

    /**
     * Second pass: re-open the (now authenticated) file, skip the header, and decrypt + parse frames
     * via the shared [ContainerBodyReader] over a [BoundedCiphertextSource] bounded to the ciphertext
     * region (excludes the trailing HMAC). The HMAC was already verified in [verifyHmac].
     */
    private suspend fun decryptAndParseFile(
        file: File,
        header: BackupCrypto.Header,
        keys: BackupCrypto.KeyPair,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest {
        val cipher: Cipher = crypto.newAesCtrCipher(Cipher.DECRYPT_MODE, keys.encKey, header.iv)
        val bodyEnd = file.length() - BackupCrypto.HMAC_BYTES
        BufferedInputStream(FileInputStream(file), BackupCrypto.IO_BUFFER_BYTES).use { raw ->
            raw.skipExactly(BackupCrypto.HEADER_SIZE.toLong())
            val body = ContainerBodyReader(
                BoundedCiphertextSource(raw, bodyEnd - BackupCrypto.HEADER_SIZE),
                cipher,
            )
            return parseFrames(body, attachmentTarget, onRecord)
        }
    }

    /**
     * First pass: stream the whole file once, feeding `header ‖ ciphertext` through HMAC, and
     * constant-time compare against the trailing [BackupCrypto.HMAC_BYTES]. Throws
     * [MigrationException.HmacMismatch] on any mismatch. Decrypts nothing.
     */
    private suspend fun verifyHmac(file: File, macKey: javax.crypto.SecretKey) {
        val total = file.length()
        val bodyEnd = total - BackupCrypto.HMAC_BYTES
        if (bodyEnd < BackupCrypto.HEADER_SIZE) {
            throw MigrationException.DecryptionFailed("file too short to contain header + HMAC")
        }
        val mac: Mac = crypto.newHmacSha256(macKey)
        val trailing = ByteArray(BackupCrypto.HMAC_BYTES)
        BufferedInputStream(FileInputStream(file), BackupCrypto.IO_BUFFER_BYTES).use { input ->
            val buf = ByteArray(BackupCrypto.IO_BUFFER_BYTES)
            var consumed = 0L
            // Feed header + ciphertext (everything up to bodyEnd) into the MAC.
            while (consumed < bodyEnd) {
                currentCoroutineContext().ensureActive()
                val want = minOf(buf.size.toLong(), bodyEnd - consumed).toInt()
                val read = input.read(buf, 0, want)
                if (read <= 0) throw MigrationException.DecryptionFailed("file truncated during MAC pass")
                mac.update(buf, 0, read)
                consumed += read
            }
            // Read the trailing HMAC.
            val trailRead = input.readExactlyInto(trailing)
            if (!trailRead) throw MigrationException.DecryptionFailed("trailing HMAC truncated")
        }
        val expected = mac.doFinal()
        if (!crypto.constantTimeEquals(expected, trailing)) {
            L.w { "[MigrationV2][Container] HMAC mismatch — wrong passphrase or tampered file" }
            throw MigrationException.HmacMismatch("HMAC verification failed")
        }
    }

    // ── shared frame parsing (used by BOTH file and streaming paths) ────────────────────────────

    /**
     * Parse the manifest frame and the record / attachment frames from [body] until the END sentinel,
     * emitting each record to [onRecord] and each attachment's bytes to [attachmentTarget]. Returns
     * the embedded manifest with the counts tallied from the stream. Shared verbatim by both read
     * paths, so the frame grammar lives in exactly one place.
     *
     * [gateFormatVersion] (streaming path only) checks the manifest's `formatVersion` as soon as it
     * is parsed, so a cross-MAJOR container is rejected before anything lands in staging. The file
     * path leaves it false because `RestoreOrchestrator.validate` gates it externally.
     */
    private suspend fun parseFrames(
        body: ContainerBodyReader,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
        gateFormatVersion: Boolean = false,
        onManifest: suspend (ContainerManifest) -> Unit = {},
    ): ContainerManifest {
        val embeddedManifest = readManifestFrame(body)
        if (gateFormatVersion && !FormatCompat.isCompatible(embeddedManifest.formatVersion)) {
            L.w {
                "[MigrationV2][Container] readStreaming incompatible formatVersion=${embeddedManifest.formatVersion} " +
                    "supported=${FormatCompat.SUPPORTED_FORMAT_VERSION}"
            }
            throw MigrationException.DecryptionFailed(
                "incompatible container formatVersion=${embeddedManifest.formatVersion} (streaming path)"
            )
        }
        // Fail-fast identity gate: fire onManifest before the loop emits anything, so a throwing
        // callback (account mismatch) aborts with no record in staging. After the format gate, so a
        // cross-MAJOR container surfaces as DecryptionFailed first.
        onManifest(embeddedManifest)
        var recordCount = 0
        var attachmentCount = 0
        var lastAttachmentRef: String? = null
        // A skipped attachment record's byte frame is drained, not bound to a stale ref.
        var drainNextBytes = false
        // Unknown record types and records whose value does not decode are skipped, not fatal; frames
        // are length-prefixed, so skipping never desynchronizes the stream. Logged once at END.
        var skippedUnknownType = 0
        var skippedUndecodable = 0
        var drainedFrames = 0
        val skippedTags = LinkedHashSet<String>()
        while (true) {
            currentCoroutineContext().ensureActive()
            val tag = body.readTagByte()
                ?: throw MigrationException.StreamInterrupted("stream ended before END sentinel")
            when (tag) {
                ContainerFormat.TAG_RECORD -> {
                    val payload = body.readFramePayload4()
                    lastAttachmentRef = null
                    drainNextBytes = false
                    when (val decoded = codec.decode(String(payload, Charsets.UTF_8))) {
                        is RecordJsonCodec.Decoded.Record -> {
                            onRecord(decoded.record)
                            recordCount++
                            lastAttachmentRef = (decoded.record as? NeutralRecord.Attachment)?.value?.stableId
                        }
                        is RecordJsonCodec.Decoded.Skipped -> {
                            if (decoded.undecodable) {
                                skippedUndecodable++
                                L.w { "[MigrationV2][Container] record type=${decoded.tag} did not decode — skipped: ${decoded.reason}" }
                            } else {
                                skippedUnknownType++
                                skippedTags.add(decoded.tag)
                            }
                            drainNextBytes = decoded.ownsBytes
                        }
                    }
                }

                ContainerFormat.TAG_ATTACHMENT -> {
                    val ref = lastAttachmentRef
                    if (ref == null && drainNextBytes) {
                        // Bytes of a skipped attachment record: consumed and authenticated, never stored.
                        streamAttachmentToTarget(body, "skipped", DISCARD)
                        drainedFrames++
                    } else {
                        streamAttachmentToTarget(
                            body,
                            ref ?: throw MigrationException.DecryptionFailed("attachment byte frame with no preceding attachment record"),
                            attachmentTarget,
                        )
                        attachmentCount++
                    }
                    lastAttachmentRef = null
                    drainNextBytes = false
                }

                ContainerFormat.TAG_END -> {
                    // END payload length is 0; consume its 4-byte length and stop.
                    body.readFramePayload4()
                    if (skippedUnknownType > 0 || skippedUndecodable > 0) {
                        L.w {
                            "[MigrationV2][Container] skipped records unknownType=$skippedUnknownType tags=$skippedTags " +
                                "undecodable=$skippedUndecodable drainedByteFrames=$drainedFrames"
                        }
                    }
                    return embeddedManifest.copy(
                        recordCount = recordCount,
                        attachmentCount = attachmentCount,
                    )
                }

                else -> throw MigrationException.DecryptionFailed("unknown frame tag=$tag")
            }
        }
    }

    /** Read + decode the first (manifest) frame; throws if the first frame is not a manifest. */
    private fun readManifestFrame(body: ContainerBodyReader): ContainerManifest {
        val tag = body.readTagByte()
            ?: throw MigrationException.StreamInterrupted("stream ended before the manifest frame")
        if (tag != ContainerFormat.TAG_MANIFEST) {
            throw MigrationException.DecryptionFailed("first frame is not manifest (tag=$tag)")
        }
        val payload = body.readFramePayload4()
        return codec.decodeManifest(String(payload, Charsets.UTF_8))
    }

    /**
     * Stream one attachment's bytes from the decrypting [body] into the target stream the
     * [attachmentTarget] provides. The 8-byte length prefix has already been written by the writer;
     * read it, then copy exactly that many decrypted bytes. If the target returns null the bytes are
     * consumed (and stay authenticated) but discarded.
     */
    private fun streamAttachmentToTarget(
        body: ContainerBodyReader,
        contentRef: String,
        attachmentTarget: AttachmentByteTarget,
    ) {
        val length = body.readLong8()
        val out = attachmentTarget.openForRef(contentRef, length)
        try {
            val buf = ByteArray(ContainerFormat.ATTACHMENT_COPY_BUFFER)
            var remaining = length
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val read = body.read(buf, 0, want)
                if (read <= 0) {
                    // A large transfer spends nearly all its time here, so this is where a cancelled
                    // peer or a dropped link is actually observed.
                    throw MigrationException.StreamInterrupted(
                        "stream ended inside attachment bytes contentRef=$contentRef remaining=$remaining"
                    )
                }
                out?.write(buf, 0, read)
                remaining -= read
            }
            out?.flush()
        } finally {
            out?.close()
        }
    }

    /** Read the 42-byte plaintext header. */
    private fun readHeaderBytes(file: File): ByteArray =
        FileInputStream(file).use { input ->
            input.readExactly(BackupCrypto.HEADER_SIZE)
                ?: throw MigrationException.DecryptionFailed("header truncated")
        }

    private companion object {
        /** Consumes and authenticates a byte frame without storing it. */
        val DISCARD = AttachmentByteTarget { _, _ -> null }
    }
}
