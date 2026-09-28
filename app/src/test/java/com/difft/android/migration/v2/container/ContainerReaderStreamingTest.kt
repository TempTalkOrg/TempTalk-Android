package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.model.ContentKind
import com.difft.android.migration.v2.model.MessageDirection
import com.difft.android.migration.v2.model.NeutralAttachment
import com.difft.android.migration.v2.model.NeutralConversationLocal
import com.difft.android.migration.v2.model.NeutralMessage
import com.difft.android.migration.v2.model.NeutralRecord
import difft.android.messageserialization.model.AttachmentStatus
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * I0 — `ContainerReader.readStreaming` engine seam (design §3.4 O2, Test Inventory T-CR1..3).
 *
 * Validates the forward-only (P2P no-disk) read path WITHOUT any devices or P2P wiring: a real
 * [ContainerWriterImpl]-produced container (records + an attachment), keyed by a `transferKey`
 * passed in the passphrase position, is fed to [ContainerReaderImpl.readStreaming] over an in-memory
 * [ByteArrayInputStream] (a faithful forward-only stand-in for the upload body — no random access).
 *
 * The crux this guards (R1): the streaming path is verify-BEFORE-COMMIT — records may be emitted to
 * the (here: test) sink before the trailing HMAC is known, but on tamper / truncation / wrong key
 * `readStreaming` MUST THROW (never report success), so the real caller discards staging and never
 * promotes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class ContainerReaderStreamingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val writer = ContainerWriterImpl(crypto)
    private val reader = ContainerReaderImpl(crypto)

    // P2P keys the envelope with an ephemeral transferKey fed to the KDF in the passphrase position
    // (design §3.3) — exercise exactly that here (no user passphrase ceremony).
    private val transferKey = "ephemeral-transfer-key-256bit-material-b64=="
    private val sourceAccount = "u_stream"
    private val sourceAppVersion = "0.0.0-stream"

    private val imageBytes = ByteArray(7000) { (it * 29 + 11).toByte() }

    private fun seed(): List<NeutralRecord> = listOf(
        NeutralRecord.ConversationLocal(NeutralConversationLocal(conversationRef = "room-1")),
        msg("m-1", "hello stream", attachmentRefs = emptyList()),
        msg("m-img", null, attachmentRefs = listOf("att-1"), kind = ContentKind.FILE),
        NeutralRecord.Attachment(
            NeutralAttachment(
                stableId = "att-1", messageRef = "m-img", contentType = "image/jpeg",
                size = imageBytes.size.toLong(), width = 320, height = 240, durationMs = null,
                flags = 0, fileName = "photo.jpg",
                serverAttachmentId = 12345L, encryptionKey = "a2V5",
                digestBase64 = "ZGln",
            )
        ),
        msg("m-2", "after attachment"),
    )

    private fun msg(
        id: String,
        body: String?,
        attachmentRefs: List<String> = emptyList(),
        kind: ContentKind = ContentKind.TEXT,
    ): NeutralRecord.Message = NeutralRecord.Message(
        NeutralMessage(
            stableId = id, conversationRef = "room-1", sender = "u-self",             direction = MessageDirection.INCOMING, body = body, sentAt = 1000,
            serverAt = 1010, receivedAt = 1020, expireTimer = 0,
            contentKind = kind, systemType = null, confidential = false,
            attachmentRefs = attachmentRefs,
        )
    )

    private fun byteSource(): AttachmentByteSource = AttachmentByteSource { ref ->
        if (ref == "att-1") AttachmentBytes(imageBytes.size.toLong(), ByteArrayInputStream(imageBytes)) else null
    }

    /** Write a valid container to a file and return its raw bytes (what the upload body would carry). */
    private suspend fun writeContainerBytes(): ByteArray {
        val container = File(tmp.root, "c-${System.nanoTime()}.ttmig")
        writer.write(seed().asFlow(), FileArchiveSink(container), transferKey, byteSource(), sourceAccount, sourceAppVersion)
        return container.readBytes()
    }

    /** Collects emitted records + attachment bytes from a read path (file or streaming). */
    private class Sink {
        val records = ArrayList<NeutralRecord>()
        val attachments = HashMap<String, ByteArray>()
        fun target(): AttachmentByteTarget = AttachmentByteTarget { ref, _ ->
            object : ByteArrayOutputStream() {
                override fun close() {
                    attachments[ref] = toByteArray()
                    super.close()
                }
            }
        }
    }

    // ── T-CR1: round-trip — readStreaming emits the same records/attachments as a valid write ──────

    @Test
    fun `readStreaming round-trips a writer container keyed by transferKey`() = runTest {
        val bytes = writeContainerBytes()

        val sink = Sink()
        val manifest = reader.readStreaming(
            source = ByteArrayInputStream(bytes),
            passphrase = transferKey,
            attachmentTarget = sink.target(),
            onRecord = { sink.records.add(it) },
        )

        assertEquals(seed().size, manifest.recordCount, "all records tallied")
        assertEquals(1, manifest.attachmentCount, "one attachment byte frame")
        assertEquals(sourceAccount, manifest.sourceAccount, "identity fields survive")
        assertEquals(ContainerFormat.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(seed().size, sink.records.size, "every record emitted to the sink")
        assertEquals(
            sha256(imageBytes), sha256(sink.attachments.getValue("att-1")),
            "attachment bytes byte-identical end-to-end",
        )
    }

    // ── T-CR3: parity — readStreaming vs read emit identical records + attachments ────────────────

    @Test
    fun `readStreaming and read produce identical emitted records for the same container`() = runTest {
        val bytes = writeContainerBytes()
        val container = File(tmp.root, "parity.ttmig").apply { writeBytes(bytes) }

        // File path (verify-before-trust).
        val fileSink = Sink()
        val fileManifest = reader.read(container, transferKey, fileSink.target()) { fileSink.records.add(it) }

        // Streaming path (verify-before-commit) over the SAME bytes.
        val streamSink = Sink()
        val streamManifest = reader.readStreaming(ByteArrayInputStream(bytes), transferKey, streamSink.target()) {
            streamSink.records.add(it)
        }

        assertEquals(fileManifest.recordCount, streamManifest.recordCount, "record tally parity")
        assertEquals(fileManifest.attachmentCount, streamManifest.attachmentCount, "attachment tally parity")
        assertEquals(fileManifest, streamManifest, "manifest parity (identity + counts)")
        // Same records in the same order — compare their JSONL encodings (stable, value-based).
        val codec = RecordJsonCodec()
        assertEquals(
            fileSink.records.map { codec.encodeRecord(it) },
            streamSink.records.map { codec.encodeRecord(it) },
            "identical record stream (order + content)",
        )
        assertContentEquals(
            fileSink.attachments.getValue("att-1"),
            streamSink.attachments.getValue("att-1"),
            "identical attachment bytes",
        )
    }

    // ── T-CR2: tamper the trailing HMAC → end-of-stream MAC gate rejects, NO success ──────────────
    //
    // Flipping a byte INSIDE the trailing 32-byte HMAC leaves the ciphertext body intact: every frame
    // decrypts + parses cleanly, the END sentinel is reached, and the corruption is caught ONLY by the
    // end-of-stream constant-time MAC compare. This pins the HMAC gate specifically (the commit gate
    // that R1 hangs on) — a clean success here would mean the gate is bypassable.

    @Test
    fun `readStreaming throws HmacMismatch when the trailing HMAC byte is tampered`() = runTest {
        val bytes = writeContainerBytes()
        // Flip a byte inside the trailing HMAC (last 32 bytes) — body stays valid; only the MAC fails.
        val hmacByteIdx = bytes.size - 5
        bytes[hmacByteIdx] = (bytes[hmacByteIdx].toInt() xor 0x7F).toByte()

        var succeeded = false
        assertFailsWith<MigrationException.HmacMismatch> {
            reader.readStreaming(ByteArrayInputStream(bytes), transferKey, discard()) { }
            succeeded = true
        }
        assertTrue(!succeeded, "no success signal may follow an HMAC mismatch (caller must discard staging)")
    }

    // ── T-CR2: tamper a ciphertext body byte → MUST throw (never silent success) ──────────────────
    //
    // A flipped body byte corrupts the decrypted plaintext. Depending on WHERE it lands it surfaces as
    // either a frame-parse failure DURING decrypt (DecryptionFailed) or an HMAC mismatch AT end-of-
    // stream — both are failures that throw and gate out promote. The security invariant is "throws,
    // never reports success", NOT a specific exception subtype.

    @Test
    fun `readStreaming throws on a tampered body byte and never reports success`() = runTest {
        val bytes = writeContainerBytes()
        // Flip a byte well inside the encrypted body (after the 42-byte header, before the trailer).
        val idx = 100.coerceIn(0, bytes.size - 1)
        bytes[idx] = (bytes[idx].toInt() xor 0x7F).toByte()

        var succeeded = false
        assertFailsWith<MigrationException> {
            reader.readStreaming(ByteArrayInputStream(bytes), transferKey, discard()) { }
            succeeded = true
        }
        assertTrue(!succeeded, "a tampered body must surface as a failure, never a silent success")
    }

    // ── T-CR2: wrong key → fails (HMAC/decrypt), no success ───────────────────────────────────────

    @Test
    fun `readStreaming with the wrong transferKey fails`() = runTest {
        val bytes = writeContainerBytes()

        var succeeded = false
        assertFailsWith<MigrationException> {
            reader.readStreaming(ByteArrayInputStream(bytes), "WRONG-transfer-key", discard()) { }
            succeeded = true
        }
        assertTrue(!succeeded, "a wrong key must never produce a success signal")
    }

    // ── T-CR2: truncation — cut before the trailing HMAC → throws (not silent partial success) ─────

    @Test
    fun `readStreaming throws when the stream is truncated before the trailing HMAC`() = runTest {
        val bytes = writeContainerBytes()
        // Drop the last 10 bytes — part of the trailing HMAC is now missing.
        val truncated = bytes.copyOf(bytes.size - 10)

        var succeeded = false
        // A stream that ends early is the peer or the link, never a verdict on the data.
        assertFailsWith<MigrationException.StreamInterrupted> {
            reader.readStreaming(ByteArrayInputStream(truncated), transferKey, discard()) { }
            succeeded = true
        }
        assertTrue(!succeeded, "truncation must surface as a failure, never a silent partial success")
    }

    @Test
    fun `readStreaming throws when the stream is cut mid-body (before END sentinel)`() = runTest {
        val bytes = writeContainerBytes()
        // Cut to just past the header — the body frames + END + HMAC are gone.
        val cut = bytes.copyOf(BackupCrypto.HEADER_SIZE + 16)

        assertFailsWith<MigrationException.StreamInterrupted> {
            reader.readStreaming(ByteArrayInputStream(cut), transferKey, discard()) { }
        }
    }

    @Test
    fun `readStreaming throws StreamInterrupted when the stream is cut inside an attachment body`() = runTest {
        val bytes = ByteArray(20_000) { (it * 11 + 1).toByte() }
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(attachmentRecord("att-a", bytes.size.toLong())))
            attachmentFrame(bytes)
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)
        // Deep inside the attachment bytes — the shape a peer's cancel takes on a real transfer.
        val cut = container.copyOf(container.size - 10_000)

        assertFailsWith<MigrationException.StreamInterrupted> {
            reader.readStreaming(ByteArrayInputStream(cut), transferKey, discard()) { }
        }
    }

    // ── streaming source must tolerate small, fragmented reads (real sockets deliver short chunks) ──

    @Test
    fun `readStreaming round-trips when the stream yields 1 byte at a time`() = runTest {
        val bytes = writeContainerBytes()

        val sink = Sink()
        val manifest = reader.readStreaming(OneByteAtATimeStream(bytes), transferKey, sink.target()) {
            sink.records.add(it)
        }

        assertEquals(seed().size, manifest.recordCount, "fragmented reads still tally every record")
        assertEquals(
            sha256(imageBytes), sha256(sink.attachments.getValue("att-1")),
            "fragmented reads preserve attachment bytes byte-for-byte",
        )
    }

    // ── version gate (the I0 regression guard for the streaming-path gap) ──────────────────────────
    //
    // The file path gates format version externally (RestoreOrchestrator.validate → FormatCompat,
    // after peekManifest). The streaming path has no such external gate before the promote commit, so
    // readStreaming must SELF-gate as soon as the manifest frame is parsed — BEFORE any record lands in
    // staging. A cross-MAJOR container must be rejected fail-fast (DecryptionFailed), never accepted.

    @Test
    fun `readStreaming rejects a cross-MAJOR formatVersion before emitting any record`() = runTest {
        // Manifest declares formatVersion "2.0" (cross-MAJOR vs the supported 1.x). The rest of the body is
        // a perfectly valid record + END, signed with a VALID trailing HMAC, so the ONLY rejection
        // reason is the version gate (not HMAC, not a structural error).
        val manifest = ContainerManifest(
            bodyFormatVersion = ContainerFormat.BODY_FORMAT_VERSION,
            encoding = ContainerFormat.ENCODING_JSONL,
            createdAt = 0L, recordCount = 0, attachmentCount = 0,
            sourceAccount = sourceAccount, sourcePlatform = ContainerFormat.SOURCE_PLATFORM_ANDROID,
            sourceAppVersion = sourceAppVersion, formatVersion = "2.0",
        )
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(manifest))
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-1", "should never be emitted")))
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val ex = assertFailsWith<MigrationException.DecryptionFailed> {
            reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) { sink.records.add(it) }
        }
        assertTrue(
            ex.message?.contains("incompatible container formatVersion") == true,
            "must reject specifically on the version gate; got: ${ex.message}",
        )
        assertEquals(0, sink.records.size, "fail-fast: no record may reach staging on an incompatible container")
    }

    // ── empty container (manifest + END only) → succeeds, zero counts ──────────────────────────────

    @Test
    fun `readStreaming succeeds on an empty container (0 records, manifest + END only)`() = runTest {
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) {
            sink.records.add(it)
        }
        assertEquals(0, manifest.recordCount, "empty container tallies zero records")
        assertEquals(0, manifest.attachmentCount, "empty container tallies zero attachments")
        assertEquals(0, sink.records.size, "no records emitted")
    }

    // ── multiple attachments (≥2) → all stream + verify (lastAttachmentRef lifecycle across frames) ─

    @Test
    fun `readStreaming streams multiple attachments and verifies each`() = runTest {
        val a1 = ByteArray(1500) { (it * 7 + 3).toByte() }
        val a2 = ByteArray(2300) { (it * 13 + 5).toByte() }
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(attachmentRecord("att-a", a1.size.toLong())))
            attachmentFrame(a1)
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(attachmentRecord("att-b", a2.size.toLong())))
            attachmentFrame(a2)
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) {
            sink.records.add(it)
        }
        assertEquals(2, manifest.attachmentCount, "both attachment byte frames tallied")
        assertContentEquals(a1, sink.attachments.getValue("att-a"), "first attachment bytes intact")
        assertContentEquals(a2, sink.attachments.getValue("att-b"), "second attachment bytes intact")
    }

    // ── zero-length attachment frame → while(remaining>0) loop not entered, succeeds ───────────────

    @Test
    fun `readStreaming handles a zero-length attachment frame`() = runTest {
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(attachmentRecord("att-empty", 0L)))
            attachmentFrame(ByteArray(0))
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) {
            sink.records.add(it)
        }
        assertEquals(1, manifest.attachmentCount, "the zero-length attachment frame still counts")
        assertEquals(0, sink.attachments.getValue("att-empty").size, "zero-length attachment yields no bytes")
    }

    // ── unknown record TYPE tolerance (contract §7.7 — MINOR is additive-only) ────────────────────
    //
    // A newer same-MAJOR exporter may add record types this build has never heard of. Since the
    // version gate deliberately accepts any MINOR, such a record MUST be skipped, not fatal — and the
    // skip must not desynchronize the frame stream (frames are length-prefixed, so it cannot). Both
    // read paths share `parseFrames`, so both are asserted here.

    /** Body: manifest, known record, UNKNOWN-type record, known record, END. */
    private fun bodyWithUnknownRecordType(): ByteArray = ByteArrayOutputStream().apply {
        jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
        jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-before", "before the unknown")))
        // A record type from a hypothetical future MINOR. Hand-written (the codec cannot encode a tag
        // it does not know) — exactly what a newer exporter's line would look like on the wire.
        jsonFrame(ContainerFormat.TAG_RECORD, """{"type":"future","value":{"whatever":1}}""")
        jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-after", "after the unknown")))
        endFrame()
    }.toByteArray()

    @Test
    fun `readStreaming skips an unknown record type and keeps the surrounding records intact`() = runTest {
        val container = craftAuthenticatedContainer(bodyWithUnknownRecordType())

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) {
            sink.records.add(it)
        }

        // 3 record frames on the wire, 1 unknown → 2 consumed. The tally counts only what was emitted,
        // so recordCount==2 IS the observable proof that exactly one record was skipped (the skipped
        // count itself is diagnostic-only, logged once at end-of-stream).
        assertEquals(2, manifest.recordCount, "the unknown record is skipped, the known ones are tallied")
        assertEquals(
            listOf("m-before", "m-after"),
            sink.records.map { (it as NeutralRecord.Message).value.stableId },
            "records on BOTH sides of the unknown one survive, in order",
        )
    }

    @Test
    fun `file read path skips an unknown record type the same way`() = runTest {
        val container = File(tmp.root, "unknown-tag.ttmig").apply {
            writeBytes(craftAuthenticatedContainer(bodyWithUnknownRecordType()))
        }

        val sink = Sink()
        val manifest = reader.read(container, transferKey, sink.target()) { sink.records.add(it) }

        assertEquals(2, manifest.recordCount, "file path applies the identical skip rule")
        assertEquals(
            listOf("m-before", "m-after"),
            sink.records.map { (it as NeutralRecord.Message).value.stableId },
        )
    }

    @Test
    fun `codec reports the unknown tag and returns null rather than throwing`() {
        val seenTags = ArrayList<String>()
        val decoded = RecordJsonCodec().decodeRecord(
            """{"type":"future","value":{}}""",
        ) { seenTags.add(it) }

        assertEquals(null, decoded, "an unknown tag decodes to null (skip), not an exception")
        assertEquals(listOf("future"), seenTags, "the unknown tag name is reported for the skip tally")
    }

    @Test
    fun `codec still throws on a malformed line (corruption is not forward compatibility)`() {
        assertFailsWith<Exception> { RecordJsonCodec().decodeRecord("not json at all") }
    }

    // ── shared MAX_FRAME_PAYLOAD_BYTES cap also enforced on the streaming path ─────────────────────

    @Test
    fun `readStreaming throws when a 4-byte frame length exceeds the cap`() = runTest {
        // Manifest frame whose declared 4-byte length is one OVER the cap (no payload follows). The cap
        // check must fire on the length alone, before any allocation — same guard the file path uses.
        val body = ByteArrayOutputStream().apply {
            write(ContainerFormat.TAG_MANIFEST.toInt())
            write(beInt(ContainerFormat.MAX_FRAME_PAYLOAD_BYTES + 1))
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        assertFailsWith<MigrationException.DecryptionFailed> {
            reader.readStreaming(ByteArrayInputStream(container), transferKey, discard()) { }
        }
    }

    // ── undecodable record tolerance: skip the record, drain its byte frame, keep going ─────────────

    @Test
    fun `readStreaming skips an attachment record that does not decode and drains its byte frame`() = runTest {
        val good = ByteArray(900) { (it * 3).toByte() }
        val bad = ByteArray(1200) { (it * 5).toByte() }
        // `size` is a primitive Long on the record, so a non-numeric value is a decode failure.
        val undecodable = RecordJsonCodec().encodeRecord(attachmentRecord("att-bad", bad.size.toLong()))
            .replace("\"size\":${bad.size}", "\"size\":\"not-a-number\"")
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-before", "before")))
            jsonFrame(ContainerFormat.TAG_RECORD, undecodable)
            attachmentFrame(bad)
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(attachmentRecord("att-good", good.size.toLong())))
            attachmentFrame(good)
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-after", "after")))
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) {
            sink.records.add(it)
        }

        assertEquals(3, manifest.recordCount, "the undecodable record is skipped, the other three are tallied")
        assertEquals(1, manifest.attachmentCount, "only the stored attachment is tallied")
        assertEquals(setOf("att-good"), sink.attachments.keys, "the skipped record's bytes never reach the target")
        assertContentEquals(good, sink.attachments.getValue("att-good"), "the stream stayed in sync past the drained frame")
        assertEquals(
            listOf("m-before", "att-good", "m-after"),
            sink.records.map { r -> (r as? NeutralRecord.Message)?.value?.stableId ?: (r as NeutralRecord.Attachment).value.stableId },
        )
    }

    @Test
    fun `readStreaming skips a message record that does not decode and keeps the rest`() = runTest {
        val undecodable = RecordJsonCodec().encodeRecord(msg("m-bad", "x")).replace("\"sentAt\":1000", "\"sentAt\":\"abc\"")
        val body = ByteArrayOutputStream().apply {
            jsonFrame(ContainerFormat.TAG_MANIFEST, RecordJsonCodec().encodeManifest(compatibleManifest()))
            jsonFrame(ContainerFormat.TAG_RECORD, undecodable)
            jsonFrame(ContainerFormat.TAG_RECORD, RecordJsonCodec().encodeRecord(msg("m-ok", "ok")))
            endFrame()
        }.toByteArray()
        val container = craftAuthenticatedContainer(body)

        val sink = Sink()
        val manifest = reader.readStreaming(ByteArrayInputStream(container), transferKey, sink.target()) { sink.records.add(it) }
        assertEquals(1, manifest.recordCount)
        assertEquals(listOf("m-ok"), sink.records.map { (it as NeutralRecord.Message).value.stableId })
    }

    private fun discard(): AttachmentByteTarget = AttachmentByteTarget { _, _ -> null }

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ── hand-crafted container harness (mirrors ContainerReaderFrameLengthTest) ─────────────────────
    //
    // Builds a genuinely authenticated container from arbitrary plaintext BODY frames so a test can
    // craft bodies the writer never would (cross-MAJOR manifest, over-cap length, zero-length
    // attachment): plaintext header ‖ AES-CTR(body) ‖ HMAC(header ‖ ciphertext). The HMAC is valid, so
    // readStreaming's end-of-stream MAC gate passes and the crafted body reaches the frame parser.

    private fun craftAuthenticatedContainer(bodyPlain: ByteArray): ByteArray {
        val salt = crypto.randomBytes(BackupCrypto.SALT_BYTES)
        val iv = crypto.randomBytes(BackupCrypto.IV_BYTES)
        val header = crypto.buildHeader(salt, iv)
        val keys = crypto.deriveKeys(transferKey, salt)

        val cipher = crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, iv)
        val ciphertext = cipher.doFinal(bodyPlain)

        val mac = crypto.newHmacSha256(keys.macKey)
        mac.update(header)
        mac.update(ciphertext)
        val hmac = mac.doFinal()

        return ByteArrayOutputStream().apply {
            write(header); write(ciphertext); write(hmac)
        }.toByteArray()
    }

    private fun compatibleManifest(): ContainerManifest = ContainerManifest(
        bodyFormatVersion = ContainerFormat.BODY_FORMAT_VERSION,
        encoding = ContainerFormat.ENCODING_JSONL,
        createdAt = 0L, recordCount = 0, attachmentCount = 0,
        sourceAccount = sourceAccount, sourcePlatform = ContainerFormat.SOURCE_PLATFORM_ANDROID,
        sourceAppVersion = sourceAppVersion, formatVersion = ContainerFormat.FORMAT_VERSION,
    )

    private fun attachmentRecord(ref: String, size: Long): NeutralRecord.Attachment = NeutralRecord.Attachment(
        NeutralAttachment(
            stableId = ref, messageRef = "m-$ref", contentType = "image/jpeg",
            size = size, width = 320, height = 240, durationMs = null,
            flags = 0, fileName = "$ref.jpg",
            serverAttachmentId = 1L, encryptionKey = "a2V5",
            digestBase64 = "ZGln",
        )
    )

    /** Append a 4-byte-length frame `[TAG][LEN(4 BE)][UTF-8 payload]`. */
    private fun ByteArrayOutputStream.jsonFrame(tag: Byte, json: String) {
        val payload = json.toByteArray(Charsets.UTF_8)
        write(tag.toInt())
        write(beInt(payload.size))
        write(payload)
    }

    /** Append an attachment byte frame `[TAG_ATTACHMENT][LEN(8 BE)][bytes]`. */
    private fun ByteArrayOutputStream.attachmentFrame(bytes: ByteArray) {
        write(ContainerFormat.TAG_ATTACHMENT.toInt())
        write(beLong(bytes.size.toLong()))
        write(bytes)
    }

    /** Append the zero-length END sentinel frame. */
    private fun ByteArrayOutputStream.endFrame() {
        write(ContainerFormat.TAG_END.toInt())
        write(beInt(0))
    }

    private fun beInt(v: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun beLong(v: Long): ByteArray =
        java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.BIG_ENDIAN).putLong(v).array()

    /** A pathological forward-only stream that returns at most ONE byte per read — stresses the
     *  HMAC tail hold-back logic against the worst-case socket fragmentation. */
    private class OneByteAtATimeStream(private val data: ByteArray) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            if (len <= 0) return 0
            b[off] = data[pos++]
            return 1
        }
    }
}
