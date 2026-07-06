package com.difft.android.base.glide

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.PictureDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.webp.decoder.ByteBufferWebpDecoder
import com.bumptech.glide.integration.webp.decoder.StreamWebpDecoder
import com.bumptech.glide.integration.webp.decoder.WebpDrawable
import com.bumptech.glide.integration.webp.decoder.WebpDrawableEncoder
import com.bumptech.glide.load.resource.bitmap.BitmapDrawableEncoder
import com.bumptech.glide.load.resource.bitmap.Downsampler
import com.bumptech.glide.load.resource.bitmap.StreamBitmapDecoder
import com.bumptech.glide.load.resource.gif.ByteBufferGifDecoder
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.load.resource.gif.GifDrawableEncoder
import com.bumptech.glide.load.resource.gif.StreamGifDecoder
import com.bumptech.glide.module.AppGlideModule
import com.caverock.androidsvg.SVG
import com.difft.android.base.log.lumberjack.L
import java.io.File
import java.io.InputStream

/** Global Glide network timeout (ms). Glide's HttpUrlFetcher default is only 2.5s. */
private const val GLIDE_NETWORK_TIMEOUT_MS = 15_000

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: com.bumptech.glide.GlideBuilder) {
        // Global default network timeout for remote image loads. Glide's built-in HttpUrlFetcher
        // defaults to only 2.5s, which times out constantly on slow/overseas sources (e.g. GIPHY
        // previews in the GIF panel) — and a failed load only retries on the next rebind. Set a
        // uniform, more generous timeout for ALL requests (like Signal, whose Glide OkHttp client
        // uses OkHttp's ~10s defaults); local loads (files / content://) ignore it. See
        // GLIDE_NETWORK_TIMEOUT_MS.
        builder.setDefaultRequestOptions(
            com.bumptech.glide.request.RequestOptions().timeout(GLIDE_NETWORK_TIMEOUT_MS)
        )
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder())
        registry.append(InputStream::class.java, SVG::class.java, SvgDecoder())

        registerEncryptedResourceCache(context, glide, registry)
    }

    /**
     * Encrypted RESOURCE cache (see [EncryptedCacheCoder]). Only wired up when the Keystore-backed
     * key is available; otherwise we skip registration entirely and callers fall back to
     * [com.bumptech.glide.load.engine.DiskCacheStrategy.NONE] — zero regression.
     *
     * We deliberately encrypt only the RESOURCE (decoded + transformed) stage:
     * - The DATA stage for local encrypted-at-rest attachments is meaningless (it would just copy the
     *   already-encrypted `.encrypt` source), and would otherwise persist decrypted plaintext.
     * - We intentionally do NOT register an `InputStream`/DATA encoder (Signal's `EncryptedCacheEncoder`).
     */
    private fun registerEncryptedResourceCache(context: Context, glide: Glide, registry: Registry) {
        val masterKey = GlideCacheKeyManager.getKeyOrNull(context) ?: run {
            L.w { "[GlideModule] encrypted resource cache disabled: key unavailable" }
            return
        }
        val coder = EncryptedCacheCoder(masterKey)

        val downsampler = Downsampler(
            registry.imageHeaderParsers,
            context.resources.displayMetrics,
            glide.bitmapPool,
            glide.arrayPool
        )
        val streamBitmapDecoder = StreamBitmapDecoder(downsampler, glide.arrayPool)

        // RESOURCE channel only: encrypt the Bitmap on write, decrypt File -> Bitmap on read.
        registry.prepend(Bitmap::class.java, EncryptedBitmapResourceEncoder(coder))
        registry.prepend(
            File::class.java,
            Bitmap::class.java,
            EncryptedCacheResourceDecoder(coder, streamBitmapDecoder)
        )
        // Some transformation chains produce a BitmapDrawable resource; encrypt it the same way.
        registry.prepend(
            BitmapDrawable::class.java,
            BitmapDrawableEncoder(glide.bitmapPool, EncryptedBitmapResourceEncoder(coder))
        )

        registerEncryptedAnimatedCache(context, glide, registry, coder)
    }

    /**
     * Encrypted RESOURCE cache for animated GIF/WebP, mirroring Signal-Android. Since prepending an
     * encoder for GifDrawable/WebpDrawable replaces Glide's default globally, the encrypted encoder is
     * opt-in per request via [EncryptedByteBufferResourceEncoder.ENCRYPT_ANIMATED_CACHE] and otherwise
     * delegates to the stock encoder — so only the intended encrypted-at-rest loads are affected.
     *
     * When opted in it writes the untransformed source bytes, so those loads MUST NOT apply any Glide
     * transformation (center-crop comes from the ImageView `scaleType`; rounded corners are dropped).
     * The `File -> Drawable` decoders are safe to register globally: [EncryptedCacheResourceDecoder]
     * only claims files carrying our MAGIC.
     */
    private fun registerEncryptedAnimatedCache(
        context: Context,
        glide: Glide,
        registry: Registry,
        coder: EncryptedCacheCoder
    ) {
        registry.prepend(
            GifDrawable::class.java,
            EncryptedByteBufferResourceEncoder(coder, "GIF", GifDrawableEncoder()) { it.buffer }
        )
        registry.prepend(
            File::class.java,
            GifDrawable::class.java,
            EncryptedCacheResourceDecoder(
                coder,
                // Use the explicit ByteBufferGifDecoder ctor (parsers + pools): the single-arg
                // ctor calls Glide.get(context).getRegistry() internally, which throws
                // "Recursive Registry initialization!" while we're building the registry here.
                StreamGifDecoder(
                    registry.imageHeaderParsers,
                    ByteBufferGifDecoder(context, registry.imageHeaderParsers, glide.bitmapPool, glide.arrayPool),
                    glide.arrayPool
                )
            )
        )

        registry.prepend(
            WebpDrawable::class.java,
            EncryptedByteBufferResourceEncoder(coder, "WEBP", WebpDrawableEncoder()) { it.buffer }
        )
        registry.prepend(
            File::class.java,
            WebpDrawable::class.java,
            EncryptedCacheResourceDecoder(
                coder,
                StreamWebpDecoder(
                    ByteBufferWebpDecoder(context, glide.arrayPool, glide.bitmapPool),
                    glide.arrayPool
                )
            )
        )
    }

    override fun isManifestParsingEnabled(): Boolean {
        return false
    }
}
