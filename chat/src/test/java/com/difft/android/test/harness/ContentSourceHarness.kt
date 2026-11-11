package com.difft.android.test.harness

import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream














class ContentSourceHarness(context: Context) {

    private val shadowResolver = shadowOf(context.contentResolver)

    
    val opens: MutableList<Uri> = mutableListOf()

    
    fun registerReadable(uri: Uri, bytes: ByteArray): ContentSourceHarness = apply {
        shadowResolver.registerInputStreamSupplier(uri) {
            opens += uri
            ByteArrayInputStream(bytes) as InputStream
        }
    }

    










    fun registerUnreadable(uri: Uri, fakeAbsPath: String): ContentSourceHarness = apply {
        shadowResolver.registerInputStreamSupplier(uri) {
            opens += uri
            throw FileNotFoundException("$fakeAbsPath: open failed: EACCES (Permission denied)")
                .apply { initCause(ErrnoException("open", OsConstants.EACCES)) }
        }
    }
}
