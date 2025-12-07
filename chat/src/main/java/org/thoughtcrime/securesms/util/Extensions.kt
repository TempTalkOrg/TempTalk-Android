package org.thoughtcrime.securesms.util

import android.content.Context
import android.content.Intent
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.difft.android.base.log.lumberjack.L
import util.ScreenLockUtil
import java.io.File

fun Context.viewFile(path: String) {
    ScreenLockUtil.temporarilyDisabled = true
    val context = this
    try {
        val uri = FileProvider.getUriForFile(
            context,
            context.applicationContext.packageName + ".provider",
            File(path)
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setData(uri)
            setDataAndType(uri, MediaUtil.getMimeType(context, uri))
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        L.w { "view File error:" + e.stackTraceToString() }
        e.printStackTrace()
        context.shareFile(path)
    }
}

fun Context.shareFile(path: String) {
    ScreenLockUtil.temporarilyDisabled = true
    try {
        val uri = FileProvider.getUriForFile(
            this,
            this.applicationContext.packageName + ".provider",
            File(path)
        )

        val contentType = MediaUtil.getMimeType(this, uri)
        val mimeType = Intent.normalizeMimeType(contentType)
        val shareIntent = ShareCompat.IntentBuilder(this)
            .setStream(uri)
            .setType(mimeType)
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        startActivity(shareIntent)
    } catch (e: Exception) {
        L.w { "share File error:" + e.stackTraceToString() }
        e.printStackTrace()
    }
}

fun Context.shareText(content: String?) {
    ScreenLockUtil.temporarilyDisabled = true
    val context = this
    try {
        val sendIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, content)
            type = "text/plain"
        }
        context.startActivity(sendIntent)
    } catch (e: Exception) {
        L.w { "share text error:" + e.stackTraceToString() }
        e.printStackTrace()
    }
}