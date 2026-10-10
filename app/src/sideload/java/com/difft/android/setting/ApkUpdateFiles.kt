package com.difft.android.setting

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.widget.ToastUtil
import com.difft.android.security.SecurityLib
import com.difft.android.websocket.api.crypto.CryptoUtil
import java.io.File

/** Verification and installer hand-off for downloaded update APKs. */
object ApkUpdateFiles {

    const val MIME_APK = "application/vnd.android.package-archive"

    fun verifyApk(context: Context, apkPath: String, apkHash: String): Boolean {
        if (TextUtils.isEmpty(apkPath)) {
            return false
        }
        try {
            // 1. 完整性校验：流式计算 SHA256，避免一次性读取整个文件到内存
            val hash = sha256(File(apkPath))
            if (!hash.equals(apkHash, ignoreCase = true)) {
                L.w { "[UpdateManager] verifyApk hash mismatch." }
                return false
            }
            // 2. 来源校验：强制校验下载 APK 的签名证书是否在硬编码白名单内。
            if (!SecurityLib.checkApkFileSign(context, apkPath, context.packageName)) {
                L.e { "[UpdateManager] verifyApk signature not in whitelist, reject install." }
                return false
            }
            return true
        } catch (e: Exception) {
            L.w { "[UpdateManager] verifyApk error: ${e.stackTraceToString()}" }
        }
        return false
    }

    /** FileProvider uri of [file]; throws IllegalArgumentException when no configured root covers it. */
    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, context.applicationContext.packageName + ".provider", file)

    fun installApk(context: Context, apkUri: Uri) {
        val intent = Intent()
        intent.action = Intent.ACTION_VIEW
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setDataAndType(apkUri, MIME_APK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            L.i { "UpdateManager installAPK error:${e.message}" }
            ToastUtil.showLong(getString(R.string.status_upgrade_install_failed) + ":" + e.message)
        }
    }

    /** Streamed SHA256, so a large APK is never loaded into memory at once. */
    private fun sha256(file: File): String {
        return file.inputStream().use { inputStream ->
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            CryptoUtil.bytesToHex(digest.digest())
        }
    }
}
