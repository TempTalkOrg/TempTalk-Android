package com.difft.android.base.glide

import com.bumptech.glide.load.EncodeStrategy
import com.bumptech.glide.load.Option
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceEncoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.util.ByteBufferUtil
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.nio.ByteBuffer
















class EncryptedByteBufferResourceEncoder<T : Any>(
    private val coder: EncryptedCacheCoder,
    private val label: String,
    private val defaultEncoder: ResourceEncoder<T>,
    private val bufferOf: (T) -> ByteBuffer
) : ResourceEncoder<T> {

    override fun getEncodeStrategy(options: Options): EncodeStrategy =
        if (options.isEncryptedAnimatedCache()) EncodeStrategy.TRANSFORMED
        else defaultEncoder.getEncodeStrategy(options)

    override fun encode(data: Resource<T>, file: File, options: Options): Boolean {
        if (!options.isEncryptedAnimatedCache()) {
            return defaultEncoder.encode(data, file, options)
        }
        return try {
            
            
            
            
            val buffer = bufferOf(data.get()).duplicate().apply {
                position(0)
                limit(capacity())
            }
            coder.encryptedOutput(file).use { out ->
                ByteBufferUtil.toStream(buffer, out)
            }
            L.d { "[EncCache] $label WRITE ${file.name} (${file.length()}B)" }
            true
        } catch (e: Throwable) {
            L.w(e) { "[EncCache] $label WRITE FAILED ${file.name}" }
            false
        }
    }

    private fun Options.isEncryptedAnimatedCache(): Boolean = get(ENCRYPT_ANIMATED_CACHE) == true

    companion object {
        




        @JvmField
        val ENCRYPT_ANIMATED_CACHE: Option<Boolean> =
            Option.memory("com.difft.android.base.glide.EncryptAnimatedCache", false)
    }
}
