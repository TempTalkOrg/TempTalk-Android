package com.difft.android.migration.v2.container

import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.v2.model.NeutralRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * A length-known byte stream for one attachment. [length] is the exact number of bytes [stream] will
 * yield, so the writer can emit the frame length prefix without buffering the attachment in memory.
 */
class AttachmentBytes(val length: Long, val stream: InputStream)

/**
 * Resolves the raw bytes for an attachment named by [NeutralRecord.Attachment]'s `contentRef` — the
 * seam that keeps the writer free of any knowledge of platform storage. The writer streams the bytes
 * into the encrypted body and closes the stream. Null means no bytes are available for the ref, and
 * the writer emits a zero-length attachment frame instead.
 */
fun interface AttachmentByteSource {
    /** Open the bytes for [contentRef] with a known length, or null if unavailable. */
    fun open(contentRef: String): AttachmentBytes?
}

/**
 * Frames and encrypts a neutral record stream into the migration container, using [BackupCrypto]'s
 * envelope (PBKDF2 → AES-256-CTR → trailing HMAC-SHA256); the body layout is [ContainerFormat].
 *
 * Constant-memory: records are encoded and encrypted one frame at a time and attachment bytes are
 * copied through a fixed buffer, so nothing is ever materialized whole. The KDF (~1s) runs once.
 *
 * Any throwable mid-write, cancellation included, calls [ArchiveSink.abort] before rethrowing, so no
 * partial archive lingers. Runs on [Dispatchers.IO].
 */
interface ContainerWriter {

    /**
     * Write [records] (+ their attachment bytes) to [sink] as an encrypted container.
     *
     * @param sourceAccount exporting account id, written into the manifest for the importer's
     *   account-match check.
     * @return the [ContainerManifest] describing what was written, with the true tallied counts.
     * @throws MigrationException.EncryptionFailed on cipher / IO failure during the write.
     */
    suspend fun write(
        records: Flow<NeutralRecord>,
        sink: ArchiveSink,
        passphrase: String,
        attachmentBytes: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        /**
         * What the stream will hold, counted by the source before writing, so the receiver can draw a
         * determinate bar from the first frame. Null writes zeros, which readers treat as unknown.
         */
        totals: ContainerTotals? = null,
    ): ContainerManifest
}

@Singleton
class ContainerWriterImpl @Inject constructor(
    private val crypto: BackupCrypto,
) : ContainerWriter {

    private val codec = RecordJsonCodec()

    override suspend fun write(
        records: Flow<NeutralRecord>,
        sink: ArchiveSink,
        passphrase: String,
        attachmentBytes: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        totals: ContainerTotals?,
    ): ContainerManifest = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Container] write start platform=${ContainerFormat.SOURCE_PLATFORM_ANDROID} formatVersion=${ContainerFormat.FORMAT_VERSION}" }
        var committed = false
        try {
            val manifest = BufferedOutputStream(sink.openStream(), BackupCrypto.IO_BUFFER_BYTES).use { out ->
                runWrite(records, out, passphrase, attachmentBytes, sourceAccount, sourceAppVersion, totals)
            }
            committed = true
            L.i { "[MigrationV2][Container] write done records=${manifest.recordCount} attachments=${manifest.attachmentCount}" }
            manifest
        } catch (e: CancellationException) {
            L.w { "[MigrationV2][Container] write cancelled" }
            throw e
        } catch (e: MigrationException) {
            L.e { "[MigrationV2][Container] write failed: ${e.stackTraceToString()}" }
            throw e
        } catch (e: IOException) {
            // The sink went away under the writer. A cancel closes the pipe before the coroutine is
            // resumed with the cancellation, so the write fails first and that failure is the cancel;
            // otherwise the peer hung up. Neither says anything about the data.
            if (!currentCoroutineContext().isActive) {
                L.w { "[MigrationV2][Container] write cancelled (sink closed)" }
                currentCoroutineContext().ensureActive()
            }
            L.w { "[MigrationV2][Container] write interrupted, sink closed: ${e.javaClass.simpleName}: ${e.message}" }
            throw MigrationException.SinkInterrupted("sink closed before the container ended", e)
        } catch (e: Throwable) {
            L.e { "[MigrationV2][Container] write failed: ${e.stackTraceToString()}" }
            throw MigrationException.EncryptionFailed("container write failed", e)
        } finally {
            if (!committed) {
                runCatching { sink.abort() }
                    .onFailure { L.w { "[MigrationV2][Container] sink abort failed: ${it.message}" } }
            }
        }
    }

    /**
     * The core streaming pipeline: plaintext header (authenticated) → encrypted[ manifest, records,
     * attachment bytes, end ] → trailing HMAC over header ‖ ciphertext.
     *
     * The manifest is the first body frame, so its counts come from the caller's pre-[totals] rather
     * than a second pass, which would break constant-memory; zeros mean unknown. The reader tallies
     * the true counts up to the END sentinel, and the returned manifest carries those. The identity
     * fields are authoritative as written.
     */
    private suspend fun runWrite(
        records: Flow<NeutralRecord>,
        out: OutputStream,
        passphrase: String,
        attachmentBytes: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        totals: ContainerTotals?,
    ): ContainerManifest {
        val salt = crypto.randomBytes(BackupCrypto.SALT_BYTES)
        val iv = crypto.randomBytes(BackupCrypto.IV_BYTES)
        val keys = crypto.deriveKeys(passphrase, salt)

        val header = crypto.buildHeader(salt, iv)
        val cipher: Cipher = crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, iv)
        val mac: Mac = crypto.newHmacSha256(keys.macKey)

        // 1. Plaintext header — NOT encrypted, but IS authenticated by the trailing HMAC.
        out.write(header)
        mac.update(header)

        // 2. Manifest frame: identity fields are final; counts come from the caller's pre-count.
        val provisional = ContainerManifest(
            bodyFormatVersion = ContainerFormat.BODY_FORMAT_VERSION,
            encoding = ContainerFormat.ENCODING_JSONL,
            createdAt = System.currentTimeMillis(),
            recordCount = totals?.recordCount ?: 0,
            attachmentCount = totals?.attachmentCount ?: 0,
            attachmentBytes = totals?.attachmentBytes ?: 0L,
            sourceAccount = sourceAccount,
            sourcePlatform = ContainerFormat.SOURCE_PLATFORM_ANDROID,
            sourceAppVersion = sourceAppVersion,
            formatVersion = ContainerFormat.FORMAT_VERSION,
            sourceCapabilities = ContainerFormat.SOURCE_CAPABILITIES_ANDROID,
        )
        writeFrame(out, cipher, mac, ContainerFormat.TAG_MANIFEST, codec.encodeManifest(provisional).toByteArray(Charsets.UTF_8))

        // 3. Record frames; attachment records additionally trigger a byte frame.
        var recordCount = 0
        var attachmentCount = 0
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            val line = codec.encodeRecord(record).toByteArray(Charsets.UTF_8)
            writeFrame(out, cipher, mac, ContainerFormat.TAG_RECORD, line)
            recordCount++

            if (record is NeutralRecord.Attachment) {
                // Bytes travel only for a non-pointer-only attachment. The frame carries no id, so
                // it is paired with its record positionally on read.
                val att = record.value
                if (!att.pointerOnly) {
                    streamAttachmentFrame(out, cipher, mac, att.stableId, attachmentBytes)
                    attachmentCount++
                }
            }
        }

        // 4. End-of-stream sentinel.
        writeFrame(out, cipher, mac, ContainerFormat.TAG_END, ByteArray(0))

        // 5. Finalise cipher + flush trailing HMAC over header ‖ ciphertext.
        val finalCt = cipher.doFinal()
        if (finalCt.isNotEmpty()) {
            out.write(finalCt)
            mac.update(finalCt)
        }
        out.write(mac.doFinal())
        out.flush()

        return provisional.copy(recordCount = recordCount, attachmentCount = attachmentCount)
    }

    /**
     * Stream one attachment's bytes as a single length-prefixed [ContainerFormat.TAG_ATTACHMENT]
     * frame: `[TAG(1)][LEN(8 BE)][bytes…]`. The length is taken from [AttachmentBytes.length]
     * (known up front), so the bytes are copied straight through a fixed
     * [ContainerFormat.ATTACHMENT_COPY_BUFFER] buffer into the cipher, so an arbitrarily large
     * attachment never enters memory whole. The length is 8 bytes, not the 4 used by JSON frames,
     * because attachment payloads can exceed `Int.MAX_VALUE`.
     */
    private fun streamAttachmentFrame(
        out: OutputStream,
        cipher: Cipher,
        mac: Mac,
        contentRef: String,
        attachmentBytes: AttachmentByteSource,
    ) {
        val source = attachmentBytes.open(contentRef)
        if (source == null) {
            L.w { "[MigrationV2][Container] no bytes for contentRef=$contentRef; writing empty frame" }
            writeAttachmentPrefix(out, cipher, mac, 0L)
            return
        }
        source.stream.use { input ->
            writeAttachmentPrefix(out, cipher, mac, source.length)
            val buf = ByteArray(ContainerFormat.ATTACHMENT_COPY_BUFFER)
            var copied = 0L
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                encryptAndWrite(out, cipher, mac, if (read == buf.size) buf else buf.copyOf(read))
                copied += read
            }
            if (copied != source.length) {
                // Declared length and actual bytes diverged, so the frame is malformed and the
                // reader would under- or over-read. Fail rather than ship a bad archive.
                throw MigrationException.EncryptionFailed(
                    "attachment byte length mismatch contentRef=$contentRef declared=${source.length} actual=$copied"
                )
            }
        }
    }

    /** Write the attachment frame header: `[TAG_ATTACHMENT(1)][LEN(8 BE)]`. */
    private fun writeAttachmentPrefix(out: OutputStream, cipher: Cipher, mac: Mac, length: Long) {
        val prefix = ByteBuffer.allocate(1 + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ContainerFormat.TAG_ATTACHMENT)
            .putLong(length)
            .array()
        encryptAndWrite(out, cipher, mac, prefix)
    }

    /**
     * Write one framed payload: `[TAG(1)][LEN(4 BE)][PAYLOAD]`, all encrypted + authenticated.
     * The tag + length prefix are encrypted alongside the payload (they are part of the body).
     * Used for manifest + record (JSONL) frames; attachment frames use [streamAttachmentFrame]
     * with an 8-byte length.
     */
    private fun writeFrame(out: OutputStream, cipher: Cipher, mac: Mac, tag: Byte, payload: ByteArray) {
        val prefix = ByteBuffer.allocate(ContainerFormat.FRAME_PREFIX_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put(tag)
            .putInt(payload.size)
            .array()
        encryptAndWrite(out, cipher, mac, prefix)
        if (payload.isNotEmpty()) {
            encryptAndWrite(out, cipher, mac, payload)
        }
    }

    /** Encrypt [data] through [cipher], write the ciphertext, and feed it to [mac]. */
    private fun encryptAndWrite(out: OutputStream, cipher: Cipher, mac: Mac, data: ByteArray) {
        val ct = cipher.update(data) ?: ByteArray(0)
        if (ct.isNotEmpty()) {
            out.write(ct)
            mac.update(ct)
        }
    }
}
