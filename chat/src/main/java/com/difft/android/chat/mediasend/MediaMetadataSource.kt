package com.difft.android.chat.mediasend

import com.difft.android.selector.entity.LocalMedia












object MediaMetadataSource {

    
    fun sizeBytes(media: LocalMedia): Long = media.size.takeIf { it > 0 } ?: UNKNOWN

    



    fun durationMs(media: LocalMedia, probedMs: Long = 0L): Long =
        probedMs.takeIf { it > 0 } ?: media.duration.takeIf { it > 0 } ?: UNKNOWN

    private const val UNKNOWN = -1L
}
