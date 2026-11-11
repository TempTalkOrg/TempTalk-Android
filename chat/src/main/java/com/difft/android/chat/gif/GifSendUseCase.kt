package com.difft.android.chat.gif

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject


sealed interface GifSendInput {
    
    data class FromUrl(val webpUrl: String, val width: Int, val height: Int) : GifSendInput

    




    data class FromFavorite(val contentUri: String, val width: Int, val height: Int) : GifSendInput
}


class GifSendUseCase @Inject constructor(
    @param:ApplicationContext private val ctx: Context
) {
    



    suspend fun resolveSendable(input: GifSendInput): Uri = withContext(Dispatchers.IO) {
        when (input) {
            is GifSendInput.FromUrl -> {
                val downloaded: File = Glide.with(ctx).asFile().load(input.webpUrl).submit().get()
                newSendCacheFile().also { downloaded.copyTo(it, overwrite = true) }.toUri()
            }
            is GifSendInput.FromFavorite -> {
                
                
                
                val dest = newSendCacheFile()
                try {
                    (ctx.contentResolver.openInputStream(input.contentUri.toUri())
                        ?: throw IllegalStateException("favorite content uri not readable: ${input.contentUri}"))
                        .use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                } catch (e: Exception) {
                    dest.delete() 
                    throw e
                }
                dest.toUri()
            }
        }
    }

    





    private fun newSendCacheFile(): File {
        val dir = File(ctx.cacheDir, "gif_send").apply { mkdirs() }
        return File(dir, "gif_${System.currentTimeMillis()}_${counter.incrementAndGet()}.webp")
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
