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


private const val GLIDE_NETWORK_TIMEOUT_MS = 15_000

@GlideModule
class MyAppGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: com.bumptech.glide.GlideBuilder) {
        
        
        builder.setDefaultRequestOptions(
            com.bumptech.glide.request.RequestOptions().timeout(GLIDE_NETWORK_TIMEOUT_MS)
        )
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.register(SVG::class.java, PictureDrawable::class.java, SvgDrawableTranscoder())
        registry.append(InputStream::class.java, SVG::class.java, SvgDecoder())

        registerEncryptedResourceCache(context, glide, registry)
    }

    









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

        
        registry.prepend(Bitmap::class.java, EncryptedBitmapResourceEncoder(coder))
        registry.prepend(
            File::class.java,
            Bitmap::class.java,
            EncryptedCacheResourceDecoder(coder, streamBitmapDecoder)
        )
        
        registry.prepend(
            BitmapDrawable::class.java,
            BitmapDrawableEncoder(glide.bitmapPool, EncryptedBitmapResourceEncoder(coder))
        )

        registerEncryptedAnimatedCache(context, glide, registry, coder)
    }

    










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
