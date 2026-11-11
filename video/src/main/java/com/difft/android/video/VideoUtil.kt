package com.difft.android.video

import android.media.MediaMetadataRetriever
import com.difft.android.base.log.lumberjack.L




object VideoUtil {

    
    const val UNKNOWN_BIT_RATE: Int = -1

    









    @JvmStatic
    fun inputBitRate(sizeBytes: Long, durationMs: Long): Int {
        if (sizeBytes <= 0 || durationMs <= 0) return UNKNOWN_BIT_RATE
        return (sizeBytes * 8 / (durationMs / 1000.0)).toInt()
    }

    









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