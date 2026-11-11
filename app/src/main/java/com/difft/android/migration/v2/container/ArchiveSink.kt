package com.difft.android.migration.v2.container

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream









interface ArchiveSink {

    
    fun openStream(): OutputStream

    
    fun abort()
}






class FileArchiveSink(private val target: File) : ArchiveSink {

    override fun openStream(): OutputStream {
        target.parentFile?.mkdirs()
        return FileOutputStream(target)
    }

    override fun abort() {
        runCatching { if (target.exists()) target.delete() }
    }
}
