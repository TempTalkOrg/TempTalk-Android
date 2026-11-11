package com.difft.android.base.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.difft.android.base.log.lumberjack.L
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

















class EncryptedCacheResourceDecoder<Z>(
    private val coder: EncryptedCacheCoder,
    private val streamDecoder: ResourceDecoder<InputStream, Z>
) : ResourceDecoder<File, Z> {

    override fun handles(source: File, options: Options): Boolean = try {
        BufferedInputStream(coder.encryptedInput(source)).use { input ->
            streamDecoder.handles(input, options)
        }
    } catch (e: Throwable) {
        false 
    }

    override fun decode(source: File, width: Int, height: Int, options: Options): Resource<Z>? {
        return try {
            val decoded = BufferedInputStream(coder.encryptedInput(source)).use { input ->
                streamDecoder.decode(input, width, height, options)
            }
            if (decoded != null) L.d { "[EncCache] HIT ${source.name} (${source.length()}B)" }
            decoded
        } catch (e: Throwable) {
            L.w(e) { "[EncCache] DECODE FAILED ${source.name}" }
            null
        }
    }
}
