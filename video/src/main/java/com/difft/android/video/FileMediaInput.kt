package com.difft.android.video

import android.media.MediaExtractor
import com.difft.android.video.interfaces.MediaInput
import java.io.File
import java.io.IOException






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
        
    }
}