package com.difft.android.video

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import java.io.File

/**
 * The single owner of "how does a video source get handed to the platform" inside :video.
 *
 * Two entry points, one per source shape:
 *  - [of] with a [File] keeps the native file path all the way down (`setDataSource(path)`,
 *    `MediaExtractor.setDataSource(path)`, `File.length()`), so sandbox and SAF sources read
 *    byte-for-byte the way they always have.
 *  - [of] with a [Uri] routes a `file://` URI back into the [File] path and everything else
 *    through [ContentResolver]. A gallery item on API 29+ can only be opened that way: the
 *    provider opens the descriptor in its own process and hands it back, which is exactly what
 *    makes it independent of this process's legacy-storage view.
 *
 * This is deliberately the only "Uri to file descriptor / MediaInput" entry point; the
 * "Uri to InputStream" side lives in :chat and the two do not share code — a descriptor and a
 * stream are different platform mechanisms with different failure modes.
 */
class VideoSource private constructor(
    val mediaInput: MediaInput,
    /** Byte size of the source, or [UNKNOWN_SIZE]. Never 0 — see [UNKNOWN_SIZE]. */
    val sizeBytes: Long,
    private val bindMetadata: (MediaMetadataRetriever) -> Unit,
    val scheme: String,
) {

    /** Binds this source onto [retriever]. Throws whatever the platform throws for an unreadable source. */
    fun bindTo(retriever: MediaMetadataRetriever) {
        bindMetadata(retriever)
    }

    companion object {

        /**
         * Size could not be determined.
         *
         * Deliberately -1 and never 0: callers compare the size against thresholds, and a 0 makes
         * every comparison take the "nothing to do" branch, which would send an unreadable source
         * onward untouched instead of failing.
         */
        const val UNKNOWN_SIZE: Long = -1L

        private const val TAG = "VideoSource"

        @JvmStatic
        fun of(file: File): VideoSource = VideoSource(
            mediaInput = FileMediaInput(file),
            sizeBytes = file.length().takeIf { it > 0 } ?: UNKNOWN_SIZE,
            bindMetadata = { it.setDataSource(file.absolutePath) },
            scheme = ContentResolver.SCHEME_FILE,
        )

        @JvmStatic
        fun of(context: Context, uri: Uri): VideoSource {
            nativeFilePathOf(uri)?.let { return of(File(it)) }
            return VideoSource(
                mediaInput = UriMediaInput(context, uri),
                sizeBytes = byteSizeOf(context, uri),
                bindMetadata = { it.setDataSource(context, uri) },
                scheme = uri.scheme.orEmpty(),
            )
        }

        private fun nativeFilePathOf(uri: Uri): String? =
            if (ContentResolver.SCHEME_FILE == uri.scheme) uri.path?.takeIf { it.isNotEmpty() } else null

        /**
         * Size via an actual open — `statSize` on the descriptor the provider hands back.
         * Deliberately not `File.length()` / `exists()`: for a source this process cannot read,
         * those still return success-looking values.
         *
         * A failure is reported as [UNKNOWN_SIZE] rather than thrown so this class stays a pure
         * query; the caller that needs bytes (the transcoder) turns an unknown size into a
         * [com.difft.android.video.exceptions.VideoSourceException].
         */
        private fun byteSizeOf(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: UNKNOWN_SIZE
        }.getOrElse { e ->
            // Type only, no stack trace: FileNotFoundException.message for a denied open is the
            // absolute file path, which must not reach the log.
            L.w { "[MediaAccess] $TAG size probe failed scheme=${uri.scheme} cause=${e.javaClass.simpleName}" }
            UNKNOWN_SIZE
        }.takeIf { it > 0 } ?: UNKNOWN_SIZE
    }
}
