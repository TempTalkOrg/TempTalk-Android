package com.difft.android.base.glide

import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.difft.android.base.log.lumberjack.L
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Reads a Glide RESOURCE cache entry written by an encrypted encoder ([EncryptedBitmapResourceEncoder]
 * or [EncryptedByteBufferResourceEncoder]): decrypts the file via [EncryptedCacheCoder] and delegates
 * the resulting plaintext [InputStream] to [streamDecoder] (e.g. `StreamBitmapDecoder`,
 * `StreamGifDecoder`, `StreamWebpDecoder`) to rebuild the resource.
 *
 * [handles] is a two-stage ownership gate:
 *  1. the file must carry our MAGIC (so non-encrypted File sources fall through to Glide's defaults);
 *  2. the *decrypted* content must be one [streamDecoder] actually handles.
 *
 * Stage 2 is essential: every encrypted cache file shares the same MAGIC regardless of media type, so
 * without checking the decoded content the Bitmap / GIF / WebP variants (all registered for `File`)
 * would each claim *every* encrypted file and route it to the wrong stream decoder. Delegating to
 * `streamDecoder.handles` lets the type-specific decoders (GIF/WebP inspect the header) reject foreign
 * files; the permissive Bitmap decoder stays the fallback via registration order.
 */
class EncryptedCacheResourceDecoder<Z>(
    private val coder: EncryptedCacheCoder,
    private val streamDecoder: ResourceDecoder<InputStream, Z>
) : ResourceDecoder<File, Z> {

    override fun handles(source: File, options: Options): Boolean = try {
        BufferedInputStream(coder.encryptedInput(source)).use { input ->
            streamDecoder.handles(input, options)
        }
    } catch (e: Throwable) {
        false // not our file / wrong key / corrupt / wrong media type → let other decoders try
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
