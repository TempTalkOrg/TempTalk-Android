package com.difft.android.p2p.engine.io

import okio.HashingSink
import okio.Sink
import okio.Source
import okio.blackholeSink
import okio.buffer
import java.security.MessageDigest





object Sha256 {

    
    fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    
    fun hex(source: Source): String {
        val hashing = HashingSink.sha256(blackholeSink())
        source.use { src -> hashing.buffer().use { it.writeAll(src) } }
        return hashing.hash.hex()
    }

    



    fun hashingSink(delegate: Sink): HashingSink = HashingSink.sha256(delegate)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
