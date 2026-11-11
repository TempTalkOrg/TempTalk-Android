package com.difft.android.video

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.video.interfaces.MediaInput
import java.io.File






class VideoSource private constructor(
    val mediaInput: MediaInput,
    
    val sizeBytes: Long,
    private val bindMetadata: (MediaMetadataRetriever) -> Unit,
    val scheme: String,
) {

    
    fun bindTo(retriever: MediaMetadataRetriever) {
        bindMetadata(retriever)
    }

    companion object {

        






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

        








        private fun byteSizeOf(context: Context, uri: Uri): Long = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: UNKNOWN_SIZE
        }.getOrElse { e ->
            
            
            L.w { "[MediaAccess] $TAG size probe failed scheme=${uri.scheme} cause=${e.javaClass.simpleName}" }
            UNKNOWN_SIZE
        }.takeIf { it > 0 } ?: UNKNOWN_SIZE
    }
}
