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





class AttachmentBytes(val length: Long, val stream: InputStream)







fun interface AttachmentByteSource {
    
    fun open(contentRef: String): AttachmentBytes?
}











interface ContainerWriter {

    







    suspend fun write(
        records: Flow<NeutralRecord>,
        sink: ArchiveSink,
        passphrase: String,
        attachmentBytes: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        



        totals: ContainerTotals? = null,
        




        beforeFinalize: (suspend () -> Unit)? = null,
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
        beforeFinalize: (suspend () -> Unit)?,
    ): ContainerManifest = withContext(Dispatchers.IO) {
        L.i { "[MigrationV2][Container] write start platform=${ContainerFormat.SOURCE_PLATFORM_ANDROID} formatVersion=${ContainerFormat.FORMAT_VERSION}" }
        var committed = false
        try {
            val manifest = BufferedOutputStream(sink.openStream(), BackupCrypto.IO_BUFFER_BYTES).use { out ->
                runWrite(records, out, passphrase, attachmentBytes, sourceAccount, sourceAppVersion, totals, beforeFinalize)
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

    








    private suspend fun runWrite(
        records: Flow<NeutralRecord>,
        out: OutputStream,
        passphrase: String,
        attachmentBytes: AttachmentByteSource,
        sourceAccount: String,
        sourceAppVersion: String,
        totals: ContainerTotals?,
        beforeFinalize: (suspend () -> Unit)?,
    ): ContainerManifest {
        val salt = crypto.randomBytes(BackupCrypto.SALT_BYTES)
        val iv = crypto.randomBytes(BackupCrypto.IV_BYTES)
        val keys = crypto.deriveKeys(passphrase, salt)

        val header = crypto.buildHeader(salt, iv)
        val cipher: Cipher = crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, iv)
        val mac: Mac = crypto.newHmacSha256(keys.macKey)

        
        out.write(header)
        mac.update(header)

        
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

        
        var recordCount = 0
        var attachmentCount = 0
        records.collect { record ->
            currentCoroutineContext().ensureActive()
            val line = codec.encodeRecord(record).toByteArray(Charsets.UTF_8)
            writeFrame(out, cipher, mac, ContainerFormat.TAG_RECORD, line)
            recordCount++

            if (record is NeutralRecord.Attachment) {
                
                
                val att = record.value
                if (!att.pointerOnly) {
                    streamAttachmentFrame(out, cipher, mac, att.stableId, attachmentBytes)
                    attachmentCount++
                }
            }
        }

        
        if (beforeFinalize != null) {
            beforeFinalize()
            currentCoroutineContext().ensureActive()
        }
        writeFrame(out, cipher, mac, ContainerFormat.TAG_END, ByteArray(0))

        
        val finalCt = cipher.doFinal()
        if (finalCt.isNotEmpty()) {
            out.write(finalCt)
            mac.update(finalCt)
        }
        out.write(mac.doFinal())
        out.flush()

        return provisional.copy(recordCount = recordCount, attachmentCount = attachmentCount)
    }

    







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
                
                
                throw MigrationException.EncryptionFailed(
                    "attachment byte length mismatch contentRef=$contentRef declared=${source.length} actual=$copied"
                )
            }
        }
    }

    
    private fun writeAttachmentPrefix(out: OutputStream, cipher: Cipher, mac: Mac, length: Long) {
        val prefix = ByteBuffer.allocate(1 + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .put(ContainerFormat.TAG_ATTACHMENT)
            .putLong(length)
            .array()
        encryptAndWrite(out, cipher, mac, prefix)
    }

    





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

    
    private fun encryptAndWrite(out: OutputStream, cipher: Cipher, mac: Mac, data: ByteArray) {
        val ct = cipher.update(data) ?: ByteArray(0)
        if (ct.isNotEmpty()) {
            out.write(ct)
            mac.update(ct)
        }
    }
}
