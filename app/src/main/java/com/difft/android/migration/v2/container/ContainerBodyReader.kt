package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.model.MigrationException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac




internal fun InputStream.readExactly(n: Int): ByteArray? {
    val out = ByteArray(n)
    var off = 0
    while (off < n) {
        val r = read(out, off, n - off)
        if (r <= 0) return null
        off += r
    }
    return out
}


internal fun InputStream.readExactlyInto(dst: ByteArray): Boolean {
    var off = 0
    while (off < dst.size) {
        val r = read(dst, off, dst.size - off)
        if (r <= 0) return false
        off += r
    }
    return true
}


internal fun InputStream.skipExactly(n: Long) {
    var remaining = n
    val sink = ByteArray(minOf(n, BackupCrypto.IO_BUFFER_BYTES.toLong()).toInt().coerceAtLeast(1))
    while (remaining > 0) {
        val want = minOf(sink.size.toLong(), remaining).toInt()
        val r = read(sink, 0, want)
        if (r <= 0) throw MigrationException.StreamInterrupted("unexpected EOF skipping header")
        remaining -= r
    }
}











internal class ContainerBodyReader(
    private val source: CiphertextSource,
    private val cipher: Cipher,
) {

    
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val ctBuf = ByteArray(len)
        val ctRead = source.read(ctBuf, 0, len)
        if (ctRead <= 0) return -1
        val pt = cipher.update(ctBuf, 0, ctRead) ?: ByteArray(0)
        
        System.arraycopy(pt, 0, dst, off, pt.size)
        return pt.size
    }

    
    fun readTagByte(): Byte? {
        val one = ByteArray(1)
        val r = read(one, 0, 1)
        return if (r == 1) one[0] else null
    }

    




    fun readFramePayload4(): ByteArray {
        val lenBytes = readExactlyDecrypted(4)
        val len = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).int
        if (len < 0) throw MigrationException.DecryptionFailed("negative frame length: $len")
        if (len > ContainerFormat.MAX_FRAME_PAYLOAD_BYTES) {
            throw MigrationException.DecryptionFailed(
                "frame length $len exceeds max ${ContainerFormat.MAX_FRAME_PAYLOAD_BYTES} (corrupt/malicious)"
            )
        }
        return if (len == 0) ByteArray(0) else readExactlyDecrypted(len)
    }

    
    fun readLong8(): Long {
        val lenBytes = readExactlyDecrypted(8)
        val v = ByteBuffer.wrap(lenBytes).order(ByteOrder.BIG_ENDIAN).long
        if (v < 0) throw MigrationException.DecryptionFailed("negative attachment length: $v")
        return v
    }

    private fun readExactlyDecrypted(n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(out, off, n - off)
            if (r <= 0) throw MigrationException.StreamInterrupted("frame truncated (wanted $n got $off)")
            off += r
        }
        return out
    }
}





internal interface CiphertextSource {
    





    fun read(dst: ByteArray, off: Int, len: Int): Int
}







internal class BoundedCiphertextSource(
    private val raw: InputStream,
    private val bodyBytes: Long,
) : CiphertextSource {
    private var consumed = 0L

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (consumed >= bodyBytes) return -1
        val want = minOf(len.toLong(), bodyBytes - consumed).toInt()
        if (want <= 0) return -1
        var read = 0
        while (read < want) {
            val r = raw.read(dst, off + read, want - read)
            if (r <= 0) break
            read += r
        }
        if (read <= 0) return -1
        consumed += read
        return read
    }
}















internal class HmacTailCiphertextSource(
    private val raw: InputStream,
    private val mac: Mac,
    private val hmacBytes: Int,
) : CiphertextSource {

    





    private val hold = ByteArray(hmacBytes + FILL_CHUNK)
    private var holdCount = 0
    private var eof = false

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        var produced = 0
        while (produced < len) {
            
            
            
            while (releasable() <= 0 && !eof) topUp()
            val releasable = releasable()
            if (releasable <= 0) break 
            val take = minOf(releasable, len - produced)
            
            mac.update(hold, 0, take)
            System.arraycopy(hold, 0, dst, off + produced, take)
            shiftOut(take)
            produced += take
        }
        return if (produced == 0) -1 else produced
    }

    
    private fun releasable(): Int = (holdCount - hmacBytes).coerceAtLeast(0)

    
    private fun topUp() {
        if (eof || holdCount >= hold.size) return
        val r = raw.read(hold, holdCount, hold.size - holdCount)
        if (r <= 0) eof = true else holdCount += r
    }

    
    private fun shiftOut(n: Int) {
        System.arraycopy(hold, n, hold, 0, holdCount - n)
        holdCount -= n
    }

    







    fun trailingHmac(): ByteArray {
        
        
        while (true) {
            while (releasable() <= 0 && !eof) topUp()
            val releasable = releasable()
            if (releasable <= 0) break 
            mac.update(hold, 0, releasable)
            shiftOut(releasable)
        }
        if (holdCount != hmacBytes) {
            throw MigrationException.StreamInterrupted(
                "stream truncated before trailing HMAC (have $holdCount of $hmacBytes tail bytes)"
            )
        }
        return hold.copyOf(hmacBytes)
    }

    private companion object {
        
        const val FILL_CHUNK = 64 * 1024
    }
}
