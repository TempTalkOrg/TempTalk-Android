package com.difft.android.chat.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.media.EncryptedAttachmentAccess
import util.ScreenLockUtil
import java.io.File

fun Context.viewFile(path: String) {
    // Encrypted-at-rest attachment (image/audio): view via the decrypting content provider. The
    // plaintext file is transient/absent, so a FileProvider uri to it would ENOENT.
    if (EncryptedAttachmentAccess.hasEncrypted(path)) {
        viewUri(EncryptedAttachmentAccess.contentUriFromBasePath(path))
        return
    }
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
        L.w(e) { "view File error:" }
        context.shareFile(path)
    }
}

/** ACTION_VIEW for a content [uri] (e.g. the decrypting provider uri). */
fun Context.viewUri(uri: Uri) {
    ScreenLockUtil.temporarilyDisabled = true
    val context = this
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, MediaUtil.getMimeType(context, uri))
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        L.w(e) { "view Uri error:" }
        context.shareUri(uri)
    }
}

fun Context.shareFile(path: String) {
    // Prefer the encrypted-at-rest source via the decrypting content provider when present. For
    // images/audio the plaintext file is transient — deleted after send / kept only as `.encrypt` —
    // so handing out a FileProvider uri to it risks ENOENT by the time the receiver reads it. The
    // content uri decrypts on demand from the persistent `.encrypt` copy and never goes missing.
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

        startActivity(shareIntent)
    } catch (e: Exception) {
        L.w(e) { "share File error:" }
    }
}

/**
 * Share an arbitrary [uri] (e.g. the decrypting [com.difft.android.chat.media.EncryptedAttachmentProvider]
 * content uri for an encrypted-at-rest attachment). The receiving app reads decrypted bytes through
 * the ContentResolver under a temporary read grant — no plaintext file is exposed.
 */
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

        startActivity(shareIntent)
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