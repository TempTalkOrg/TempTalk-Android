package com.difft.android.selector.utils

import android.content.Context
import android.content.Intent
import android.net.Uri

import androidx.core.content.FileProvider
import com.difft.android.selector.config.PictureMimeType

import java.io.File

object IntentUtils {

    @JvmStatic
    fun startSystemPlayerVideo(context: Context, path: String) {
        val intent = Intent(Intent.ACTION_VIEW)
        val isParseUri = PictureMimeType.isContent(path) || PictureMimeType.isHasHttp(path)
        val data: Uri = if (SdkVersionUtils.isQ()) {
            if (isParseUri) Uri.parse(path) else Uri.fromFile(File(path))
        } else {
            // isMaxN() (SDK_INT >= N/24) is always true at minSdk 26, so the final
            // raw-Uri.fromFile else branch was dead; below is the API 26-28 path.
            if (isParseUri) {
                Uri.parse(path)
            } else {
                FileProvider.getUriForFile(
                    context, context.packageName + ".luckProvider", File(path)
                )
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setDataAndType(data, "video/*")
        context.startActivity(intent)
    }
}
