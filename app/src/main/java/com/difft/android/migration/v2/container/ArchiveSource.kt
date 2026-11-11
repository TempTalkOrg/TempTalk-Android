package com.difft.android.migration.v2.container

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream










interface ArchiveSource {
    
    fun openStream(): InputStream

    



    fun asFile(): File? = null
}


class FileArchiveSource(private val file: File) : ArchiveSource {
    override fun openStream(): InputStream = FileInputStream(file)
    override fun asFile(): File = file
}





class SafArchiveSource(
    private val context: Context,
    private val uri: Uri,
) : ArchiveSource {
    override fun openStream(): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException("ContentResolver.openInputStream returned null for $uri")
}
