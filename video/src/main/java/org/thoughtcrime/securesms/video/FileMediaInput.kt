package org.thoughtcrime.securesms.video

import android.media.MediaExtractor
import org.thoughtcrime.securesms.video.interfaces.MediaInput
import java.io.File
import java.io.IOException

/**
 * A media input source that the system reads directly from the file.
 * This is more efficient than using MediaDataSource because the system
 * can use native file operations without JNI overhead.
 */
class FileMediaInput(private val file: File) : MediaInput {
    @Throws(IOException::class)
    override fun createExtractor(): MediaExtractor {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        return extractor
    }

    override fun hasSameInput(other: MediaInput): Boolean {
        return other is FileMediaInput && other.file == this.file
    }

    override fun close() {
        // No resources to close - the system handles file access
    }
}