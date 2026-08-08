package com.difft.android.chat.mediasend

import com.difft.android.selector.entity.LocalMedia

/**
 * Trusted metadata for a selected item.
 *
 * The loader-supplied MediaStore values are the baseline: they are populated for every gallery item
 * (LocalMediaPageLoader writes DURATION/SIZE) and stay correct even when the bytes cannot be opened.
 * Reading them costs nothing and is main-thread safe, unlike a retriever / fd probe, which is a
 * Binder round trip for content URIs.
 *
 * Every accessor returns -1 for "unknown" and never 0, so a caller cannot render a fabricated
 * "0:00" / "0.0MB" — presenting a failed read as a fact is what made the original bug invisible.
 */
object MediaMetadataSource {

    /** Byte size from the MediaStore row; -1 when unknown. */
    fun sizeBytes(media: LocalMedia): Long = media.size.takeIf { it > 0 } ?: UNKNOWN

    /**
     * Duration in ms. A probed value (e.g. the trim range the timeline discovered) wins when it is
     * usable; otherwise fall back to the MediaStore duration. -1 when neither is known.
     */
    fun durationMs(media: LocalMedia, probedMs: Long = 0L): Long =
        probedMs.takeIf { it > 0 } ?: media.duration.takeIf { it > 0 } ?: UNKNOWN

    private const val UNKNOWN = -1L
}
