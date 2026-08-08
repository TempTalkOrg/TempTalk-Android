package com.difft.android.selector.basic

import android.content.Context
import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import java.io.InputStream
import java.io.OutputStream

object PictureContentResolver {

    /** ContentResolver openInputStream */
    @JvmStatic
    fun openInputStream(context: Context, uri: Uri): InputStream? {
        try {
            return context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            L.w(e) { "[PictureContentResolver] openInputStream error:" }
        }
        return null
    }

    /** ContentResolver OutputStream */
    @JvmStatic
    fun openOutputStream(context: Context, uri: Uri): OutputStream? {
        try {
            return context.contentResolver.openOutputStream(uri)
        } catch (e: Exception) {
            L.w(e) { "[PictureContentResolver] openOutputStream error:" }
        }
        return null
    }
}
