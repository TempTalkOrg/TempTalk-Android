package com.difft.android.p2p.engine.io

import okio.HashingSink
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer
import java.security.MessageDigest

/**
 * SHA-256 helpers for transfer integrity. Pure JVM (Okio + java.security) — no Android imports, so
 * the engine and unit tests share one hashing implementation.
 */
object Sha256 {

    /** Lowercase hex of the SHA-256 over [bytes]. */
    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Lowercase hex of the SHA-256 over the whole [source]; fully consumes and closes it. */
    fun hex(source: Source): String {
        val hashing = HashingSink.sha256(blackholeSink())
        source.use { src -> hashing.buffer().use { it.writeAll(src) } }
        return hashing.hash.hex()
    }

    /**
     * Wraps [delegate] so every byte written through the returned [HashingSink] is hashed.
     * Read [HashingSink.hash] after the stream is fully written and closed.
     */
    fun hashingSink(delegate: Sink): HashingSink = HashingSink.sha256(delegate)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
