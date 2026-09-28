package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.model.MigrationException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac

// ── plaintext stream helpers (header read / skip), shared by both read paths ────────────────────

/** Read exactly [n] bytes from a plaintext stream, or null on early EOF. */
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

/** Fill [dst] fully from a plaintext stream; false on early EOF. */
internal fun InputStream.readExactlyInto(dst: ByteArray): Boolean {
    var off = 0
    while (off < dst.size) {
        val r = read(dst, off, dst.size - off)
        if (r <= 0) return false
        off += r
    }
    return true
}

/** Skip exactly [n] bytes from a plaintext stream. */
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

/**
 * Sole owner of frame parsing and AES-CTR decryption for both container read paths — the file
 * two-pass read and the forward-only streaming read. They differ only in their [CiphertextSource],
 * so the crypto never forks.
 *
 * AES-CTR is byte-wise streaming, so each ciphertext byte maps to exactly one plaintext byte and
 * `pt.size == ct.size` always. This class knows nothing about where the body ends or how the HMAC is
 * verified; that is the [CiphertextSource]'s job, which keeps the verify-strategy difference in the
 * callers while the byte-level frame grammar stays in one place.
 */
internal class ContainerBodyReader(
    private val source: CiphertextSource,
    private val cipher: Cipher,
) {

    /** Read up to [len] plaintext bytes into [dst] at [off]; returns count or -1 at body end. */
    fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val ctBuf = ByteArray(len)
        val ctRead = source.read(ctBuf, 0, len)
        if (ctRead <= 0) return -1
        val pt = cipher.update(ctBuf, 0, ctRead) ?: ByteArray(0)
        // AES-CTR: 1 ciphertext byte → 1 plaintext byte, so pt.size == ctRead.
        System.arraycopy(pt, 0, dst, off, pt.size)
        return pt.size
    }

    /** Read exactly one decrypted tag byte, or null at body end. */
    fun readTagByte(): Byte? {
        val one = ByteArray(1)
        val r = read(one, 0, 1)
        return if (r == 1) one[0] else null
    }

    /**
     * Read a 4-byte big-endian length, then exactly that many decrypted payload bytes. Bounded by
     * [ContainerFormat.MAX_FRAME_PAYLOAD_BYTES] (OOM guard — a 4-byte-length frame is fully allocated
     * to be JSON-decoded, so a crafted ≈2 GiB length would OOM before any content is read).
     */
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

    /** Read an 8-byte big-endian length value (attachment frame length). */
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

/**
 * Source of raw ciphertext bytes for [ContainerBodyReader]. The two implementations are the ONLY
 * thing that differs between the file read path and the forward-only streaming read path.
 */
internal interface CiphertextSource {
    /**
     * Read up to [len] raw (still-encrypted) body bytes into [dst] at [off]. Returns the count read,
     * or -1 once the ciphertext body is exhausted (NOT counting any trailing HMAC). Implementations
     * that own the HMAC (streaming) feed every byte they yield here into the running MAC, so the MAC
     * covers exactly the ciphertext the body reader decrypts.
     */
    fun read(dst: ByteArray, off: Int, len: Int): Int
}

/**
 * File-path [CiphertextSource]: a bounded view over the ciphertext region of a random-access
 * container, reading at most [bodyBytes] bytes from [raw] so it never reads into the trailing MAC.
 * The file path verifies the HMAC in a separate first pass, so this source only bounds the
 * ciphertext.
 */
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

/**
 * Streaming-path [CiphertextSource]: reads the container body from a forward-only [raw] stream (no
 * seek, no known length) and separates the trailing [hmacBytes]-byte HMAC from the ciphertext as it
 * goes.
 *
 * A byte cannot be known to be among the last [hmacBytes] until EOF, so this source holds back a
 * rolling buffer and releases a byte as ciphertext only once [hmacBytes] further bytes arrive behind
 * it. Every released byte is fed into [mac], which the caller pre-seeded with the plaintext header,
 * so at EOF [mac] covers exactly `header ‖ ciphertext`. Whatever remains held back is the trailing
 * HMAC, available via [trailingHmac].
 *
 * This is verify-before-commit: plaintext reaches the discardable staging DB before the HMAC is
 * known, so the caller must call [finishAndVerify] at end-of-stream and promote only on success.
 */
internal class HmacTailCiphertextSource(
    private val raw: InputStream,
    private val mac: Mac,
    private val hmacBytes: Int,
) : CiphertextSource {

    /**
     * Hold-back FIFO of the most-recent bytes not yet PROVEN to be ciphertext. Sized
     * [hmacBytes] + a fill chunk: at any moment the OLDEST `holdCount - hmacBytes` bytes are
     * guaranteed ciphertext (something arrived behind them), and the NEWEST [hmacBytes] bytes might
     * still turn out to be the trailing HMAC. `hold[0 until holdCount]` is the live FIFO content.
     */
    private val hold = ByteArray(hmacBytes + FILL_CHUNK)
    private var holdCount = 0
    private var eof = false

    override fun read(dst: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        var produced = 0
        while (produced < len) {
            // Top up until at least one byte is releasable as ciphertext, or the stream ends. A
            // single read may deliver fewer than the held-back tail's worth, so loop (don't break
            // early on a short read) — only true EOF stops us.
            while (releasable() <= 0 && !eof) topUp()
            val releasable = releasable()
            if (releasable <= 0) break // EOF: only the HMAC tail remains
            val take = minOf(releasable, len - produced)
            // The oldest `take` bytes are proven ciphertext: feed to MAC + hand to the caller.
            mac.update(hold, 0, take)
            System.arraycopy(hold, 0, dst, off + produced, take)
            shiftOut(take)
            produced += take
        }
        return if (produced == 0) -1 else produced
    }

    /** Bytes safe to release as ciphertext right now (everything but the held-back HMAC tail). */
    private fun releasable(): Int = (holdCount - hmacBytes).coerceAtLeast(0)

    /** Pull one raw chunk into the tail of the FIFO; sets [eof] when the stream ends. */
    private fun topUp() {
        if (eof || holdCount >= hold.size) return
        val r = raw.read(hold, holdCount, hold.size - holdCount)
        if (r <= 0) eof = true else holdCount += r
    }

    /** Drop the oldest [n] bytes of the FIFO (shift left). */
    private fun shiftOut(n: Int) {
        System.arraycopy(hold, n, hold, 0, holdCount - n)
        holdCount -= n
    }

    /**
     * At end-of-stream: drain any remaining ciphertext still in the FIFO into the MAC, then assert
     * exactly [hmacBytes] bytes remain — those are the trailing HMAC. Returns them for the caller's
     * constant-time compare.
     *
     * @throws MigrationException.StreamInterrupted if fewer than [hmacBytes] tail bytes remain (the
     *   stream ended before a complete trailing HMAC arrived).
     */
    fun trailingHmac(): ByteArray {
        // Exhaust the stream: release every remaining ciphertext byte into the MAC. After this loop,
        // eof is set and only the HMAC tail can remain in the FIFO.
        while (true) {
            while (releasable() <= 0 && !eof) topUp()
            val releasable = releasable()
            if (releasable <= 0) break // eof and only the HMAC tail remains
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
        /** Raw read chunk on top of the held-back HMAC tail. 64 KiB matches the IO buffer. */
        const val FILL_CHUNK = 64 * 1024
    }
}
