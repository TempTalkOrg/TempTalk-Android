package com.difft.android.chat.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SharedUriGuard
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.media.EncryptedAttachmentAccess
import util.ScreenLockUtil
import java.io.File

fun Context.viewFile(path: String) {
    
    
    if (EncryptedAttachmentAccess.hasEncrypted(path)) {
        viewUri(EncryptedAttachmentAccess.contentUriFromBasePath(path), File(path).name)
        return
    }
    val uri = try {
        FileProvider.getUriForFile(
            this,
            applicationContext.packageName + ".provider",
            File(path)
        )
    } catch (e: IllegalArgumentException) {
        
        L.w { "[FileOpen] file not under a provider root error=${e.javaClass.simpleName}" }
        ToastUtil.showLong(R.string.file_load_error)
        return
    }
    viewUri(uri, File(path).name)
}






fun Context.viewUri(uri: Uri, fileName: String? = null) {
    val mime = MediaUtil.getMimeType(this, uri)
    val extension = fileName?.substringAfterLast('.', "")?.takeIf { it.isNotEmpty() }
        ?: MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.takeIf { it.isNotEmpty() }
    var receiverRejected = false
    for (type in FileOpenMime.candidates(mime, extension)) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, type)
        }
        try {
            startActivity(intent)
            
            
            ScreenLockUtil.temporarilyDisabled = true
            return
        } catch (_: ActivityNotFoundException) {
            
        } catch (_: SecurityException) {
            
            L.w { "[FileOpen] receiver rejected uri type=$type ext=$extension" }
            receiverRejected = true
        }
    }
    L.w { "[FileOpen] no app for ext=$extension mime=$mime rejected=$receiverRejected" }
    if (receiverRejected) {
        
        ToastUtil.showLong(R.string.file_load_error)
    } else if (extension != null) {
        ToastUtil.showLong(getString(R.string.chat_no_app_to_open_file, ".$extension"))
    } else {
        ToastUtil.showLong(R.string.chat_no_app_to_open_file_generic)
    }
}

fun Context.shareFile(path: String) {
    
    
    
    
    if (EncryptedAttachmentAccess.hasEncrypted(path)) {
        shareUri(EncryptedAttachmentAccess.contentUriFromBasePath(path))
        return
    }
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

        startActivity(SharedUriGuard.excludeOwnTargets(this, shareIntent))
    } catch (e: Exception) {
        L.w(e) { "share File error:" }
    }
}






fun Context.shareUri(uri: Uri) {
    ScreenLockUtil.temporarilyDisabled = true
    try {
        val contentType = MediaUtil.getMimeType(this, uri)
        val mimeType = Intent.normalizeMimeType(contentType)
        val shareIntent = ShareCompat.IntentBuilder(this)
            .setStream(uri)
            .setType(mimeType)
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        startActivity(SharedUriGuard.excludeOwnTargets(this, shareIntent))
    } catch (e: Exception) {
        L.w(e) { "share Uri error:" }
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
        L.w(e) { "share text error:" }
    }
}