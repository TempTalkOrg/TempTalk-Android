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






fun interface AttachmentByteTarget {
    
    fun openForRef(contentRef: String, declaredLength: Long): OutputStream?
}

















interface ContainerReader {

    








    suspend fun read(
        file: File,
        passphrase: String,
        attachmentTarget: AttachmentByteTarget,
        onRecord: suspend (NeutralRecord) -> Unit,
    ): ContainerManifest

    











    suspend fun peekManifest(file: File, passphrase: String): ContainerManifest

    






























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
            
            val header = readHeaderBytes(file)
            val parsed = parseHeaderOrThrow(header)
            val keys = crypto.deriveKeys(passphrase, parsed.salt)

            
            
            
            verifyHmac(file, keys.macKey)

            
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
                
                verifyHmac(file, keys.macKey)
                
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

            
            val header = input.readExactly(BackupCrypto.HEADER_SIZE)
                ?: throw MigrationException.StreamInterrupted("stream ended before the header")
            val parsed = parseHeaderOrThrow(header)
            val keys = crypto.deriveKeys(passphrase, parsed.salt)

            
            val mac: Mac = crypto.newHmacSha256(keys.macKey)
            mac.update(header)

            
            
            
            val cipher: Cipher = crypto.newAesCtrCipher(Cipher.DECRYPT_MODE, keys.encKey, parsed.iv)
            val ctSource = HmacTailCiphertextSource(input, mac, BackupCrypto.HMAC_BYTES)
            val body = ContainerBodyReader(ctSource, cipher)
            
            
            
            val manifest = parseFrames(body, attachmentTarget, onRecord, gateFormatVersion = true, onManifest = onManifest)

            
            
            
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

    

    private fun parseHeaderOrThrow(header: ByteArray): BackupCrypto.Header =
        try {
            crypto.parseHeader(header)
        } catch (e: IllegalArgumentException) {
            throw MigrationException.DecryptionFailed("header invalid: ${e.message ?: "unknown"}", e)
        }

    




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
            
            while (consumed < bodyEnd) {
                currentCoroutineContext().ensureActive()
                val want = minOf(buf.size.toLong(), bodyEnd - consumed).toInt()
                val read = input.read(buf, 0, want)
                if (read <= 0) throw MigrationException.DecryptionFailed("file truncated during MAC pass")
                mac.update(buf, 0, read)
                consumed += read
            }
            
            val trailRead = input.readExactlyInto(trailing)
            if (!trailRead) throw MigrationException.DecryptionFailed("trailing HMAC truncated")
        }
        val expected = mac.doFinal()
        if (!crypto.constantTimeEquals(expected, trailing)) {
            L.w { "[MigrationV2][Container] HMAC mismatch — wrong passphrase or tampered file" }
            throw MigrationException.HmacMismatch("HMAC verification failed")
        }
    }

    

    









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
        
        
        
        onManifest(embeddedManifest)
        var recordCount = 0
        var attachmentCount = 0
        var lastAttachmentRef: String? = null
        
        var drainNextBytes = false
        
        
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
                            if (decoded.mustNotSkip) {
                                
                                
                                L.e { "[MigrationV2][Container] event=required_record_undecodable type=${decoded.tag}" }
                                throw MigrationException.DecryptionFailed("record ${decoded.tag} did not decode")
                            }
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

    
    private fun readManifestFrame(body: ContainerBodyReader): ContainerManifest {
        val tag = body.readTagByte()
            ?: throw MigrationException.StreamInterrupted("stream ended before the manifest frame")
        if (tag != ContainerFormat.TAG_MANIFEST) {
            throw MigrationException.DecryptionFailed("first frame is not manifest (tag=$tag)")
        }
        val payload = body.readFramePayload4()
        return codec.decodeManifest(String(payload, Charsets.UTF_8))
    }

    





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

    
    private fun readHeaderBytes(file: File): ByteArray =
        FileInputStream(file).use { input ->
            input.readExactly(BackupCrypto.HEADER_SIZE)
                ?: throw MigrationException.DecryptionFailed("header truncated")
        }

    private companion object {
        
        val DISCARD = AttachmentByteTarget { _, _ -> null }
    }
}
