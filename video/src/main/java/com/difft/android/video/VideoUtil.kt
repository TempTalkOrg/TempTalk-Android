package com.difft.android.video

import android.media.MediaMetadataRetriever
import com.difft.android.base.log.lumberjack.L

/**
 * Utility class for video-related operations.
 */
object VideoUtil {

    /** Bitrate could not be computed. Never 0 — see [inputBitRate]. */
    const val UNKNOWN_BIT_RATE: Int = -1

    /**
     * Input bitrate in bits per second, derived from a size and a duration that are already known.
     *
     * Pure arithmetic with no IO: both inputs come from the MediaStore row the picker already read,
     * so nothing is opened here — which also keeps this callable from the main thread.
     *
     * Returns [UNKNOWN_BIT_RATE] when either input is unknown, never 0: callers compare the result
     * against a target bitrate, and a 0 reads as "far below target" and silently takes the
     * "no compression needed" branch instead of the conservative one.
     */
    @JvmStatic
    fun inputBitRate(sizeBytes: Long, durationMs: Long): Int {
        if (sizeBytes <= 0 || durationMs <= 0) return UNKNOWN_BIT_RATE
        return (sizeBytes * 8 / (durationMs / 1000.0)).toInt()
    }

    /**
     * Get video rotation from metadata.
     *
     * Takes a [VideoSource] rather than a path so there is no way to feed a bare path past the
     * single dispatch owner; a content URI has to be bound through its provider.
     *
     * @return Rotation in degrees (0, 90, 180, 270), or 0 if unable to determine. 0 is a safe
     *   default here rather than a fabricated fact: an unrotated output renders correctly for the
     *   common case, and no caller branches on "rotation unknown".
     */
    @JvmStatic
    fun getVideoRotation(source: VideoSource): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            source.bindTo(retriever)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            L.w { "[MediaAccess] VideoUtil rotation probe failed scheme=${source.scheme} cause=${e.javaClass.simpleName}" }
            0
        } finally {
            runCatching { retriever.release() }
        }
    }
}