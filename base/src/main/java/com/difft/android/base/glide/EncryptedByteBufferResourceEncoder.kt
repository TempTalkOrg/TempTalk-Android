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

/**
 * RESOURCE-cache encoder for animated `GifDrawable` / `WebpDrawable`, mirroring Signal-Android's
 * `EncryptedGifDrawableResourceEncoder`. The companion read path is [EncryptedCacheResourceDecoder].
 *
 * Registering an encoder for `GifDrawable`/`WebpDrawable` **replaces Glide's default globally**, so we
 * must not silently change behavior for every animated load in the app. We therefore act only when the
 * request opts in via [ENCRYPT_ANIMATED_CACHE]; otherwise we delegate to [defaultEncoder] (Glide's
 * `GifDrawableEncoder` / `WebpDrawableEncoder`), preserving stock behavior everywhere else.
 *
 * When enabled we declare [EncodeStrategy.TRANSFORMED] (not SOURCE like the default) so the entry is
 * keyed and read back through the RESOURCE cache — the only stage we encrypt — and write the
 * *untransformed* source bytes ([bufferOf]). Because the bytes are untransformed, the caller MUST NOT
 * apply any Glide transformation when opting in (see `ImageAndVideoMessageView`), otherwise a cache
 * hit would render the untransformed image and diverge from the first render.
 */
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
            // The source buffer's position has usually been advanced to the end during decode, and
            // ByteBufferUtil.toStream writes only [position, limit). Rewind a duplicate (leaving the
            // live drawable's buffer untouched) so the full source bytes are persisted — mirrors what
            // Glide's own ByteBufferUtil.toFile does via rewind() for the plaintext cache.
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
        /**
         * Set on a request to route its animated RESOURCE cache through this encrypted encoder. A
         * memory-only option so it never fragments the disk cache key. Callers that set this MUST also
         * disable transformations for the load (the cache stores untransformed source bytes).
         */
        @JvmField
        val ENCRYPT_ANIMATED_CACHE: Option<Boolean> =
            Option.memory("com.difft.android.base.glide.EncryptAnimatedCache", false)
    }
}
