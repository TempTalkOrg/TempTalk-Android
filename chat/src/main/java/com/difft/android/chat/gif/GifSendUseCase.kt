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

/**
 * Input describing what to turn into a sendable gif Uri.
 *
 * [FromUrl] = search/trending result (download the original gif).
 * [FromFavorite] = a favorited gif already resolved to a local decrypted file by the caller.
 */
sealed interface GifSendInput {
    /** Search/trending result: download the original webp rendition from a remote URL (v2). */
    data class FromUrl(val webpUrl: String, val width: Int, val height: Int) : GifSendInput

    /**
     * Favorite item. [contentUri] is the decrypting `content://` uri from the favorites loader
     * (encrypted at rest). The send path decrypts it into a transient send-cache file; the outgoing
     * message pipeline then re-encrypts it. No persistent plaintext.
     */
    data class FromFavorite(val contentUri: String, val width: Int, val height: Int) : GifSendInput
}

/**
 * Resolves a picked GIF into a local cache file Uri suitable for
 * `prepareSendAttachmentPush(uri, "image/webp", ...)`. v2 sends the `original.webp` rendition
 * (smaller than gif, animated on Android 9+; display uses webp too).
 *
 * Stateless -> plain @Inject constructor (no @Module needed).
 */
class GifSendUseCase @Inject constructor(
    @param:ApplicationContext private val ctx: Context
) {
    /**
     * Download the webp bytes (blocking Glide get on IO) and copy into a stable cache file,
     * returning its Uri. The caller copies it again into the message attachment dir.
     */
    suspend fun resolveSendable(input: GifSendInput): Uri = withContext(Dispatchers.IO) {
        when (input) {
            is GifSendInput.FromUrl -> {
                val downloaded: File = Glide.with(ctx).asFile().load(input.webpUrl).submit().get()
                newSendCacheFile().also { downloaded.copyTo(it, overwrite = true) }.toUri()
            }
            is GifSendInput.FromFavorite -> {
                // Decrypt the encrypted-at-rest favorite (via its content:// uri) into a transient
                // send-cache webp — the outgoing message pipeline re-encrypts it, so no persistent
                // plaintext. Mirrors how message attachments stage a temp file before the send job.
                val dest = newSendCacheFile()
                try {
                    (ctx.contentResolver.openInputStream(input.contentUri.toUri())
                        ?: throw IllegalStateException("favorite content uri not readable: ${input.contentUri}"))
                        .use { inp -> dest.outputStream().use { out -> inp.copyTo(out) } }
                } catch (e: Exception) {
                    dest.delete() // don't leave a partial/plaintext staging file behind on failure
                    throw e
                }
                dest.toUri()
            }
        }
    }

    /**
     * A fresh, unique file in the gif send-cache dir. The name carries a process-unique counter (not
     * just the timestamp) so two gifs picked within the same millisecond never collide onto one path —
     * a collision would send one message the other's bytes. The send pipeline
     * ([prepareSendAttachmentPush]) deletes it once copied into the encrypted attachment dir.
     */
    private fun newSendCacheFile(): File {
        val dir = File(ctx.cacheDir, "gif_send").apply { mkdirs() }
        return File(dir, "gif_${System.currentTimeMillis()}_${counter.incrementAndGet()}.webp")
    }

    companion object {
        private val counter = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
