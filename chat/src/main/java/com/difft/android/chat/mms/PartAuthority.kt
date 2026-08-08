package com.difft.android.chat.mms

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.provider.DocumentsContractCompat
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

object PartAuthority {

    @JvmStatic
    @Throws(IOException::class)
    fun getAttachmentStream(context: Context, uri: Uri): InputStream? =
        openExternalFileStream(context, uri)

    @Throws(IOException::class)
    private fun openExternalFileStream(context: Context, uri: Uri): InputStream? =
        if (isVirtualFile(context, uri)) {
            getInputStreamForVirtualFile(context, uri)
        } else {
            context.contentResolver.openInputStream(uri)
        }

    private fun isVirtualFile(context: Context, uri: Uri): Boolean {
        if (!DocumentsContractCompat.isDocumentUri(context, uri)) {
            return false
        }
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null,
            null
        ).use { cursor ->
            if (cursor == null) {
                return false
            }
            val flags = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            return (flags and DocumentsContractCompat.DocumentCompat.FLAG_VIRTUAL_DOCUMENT) != 0
        }
    }

    @Throws(IOException::class)
    private fun getInputStreamForVirtualFile(context: Context, uri: Uri): InputStream? {
        val openableMimeTypes = context.contentResolver.getStreamTypes(uri, "*/*")
        if (openableMimeTypes.isNullOrEmpty()) {
            throw FileNotFoundException("No openable mime-types for virtual file.")
        }
        val fileDescriptor = context.contentResolver
            .openTypedAssetFileDescriptor(uri, openableMimeTypes[0], null)
            ?: throw FileNotFoundException("Couldn't open file descriptor for virtual file.")
        return fileDescriptor.createInputStream()
    }
}
