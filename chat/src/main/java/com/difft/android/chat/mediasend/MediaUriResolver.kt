package com.difft.android.chat.mediasend

import android.net.Uri
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.config.PictureMimeType
import com.difft.android.selector.entity.LocalMedia
import java.io.File



















fun LocalMedia.readableUri(): Uri {
    val contentPath = path
    if (contentPath.isNotEmpty() && PictureMimeType.isContent(contentPath)) {
        return Uri.parse(contentPath)
    }
    if (realPath.isEmpty()) {
        
        
        
        L.w { "[MediaAccess] blank source id=$id mime=$mimeType" }
        return Uri.EMPTY
    }
    
    
    return Uri.fromFile(File(realPath))
}














@JvmInline
value class MediaKey(val uri: Uri)

fun LocalMedia.mediaKey(): MediaKey = MediaKey(readableUri())
