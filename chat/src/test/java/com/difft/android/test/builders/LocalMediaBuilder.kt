package com.difft.android.test.builders

import com.difft.android.selector.entity.LocalMedia














object LocalMediaBuilder {

    



    @Suppress("LongParameterList")
    fun gallery(
        id: Long = 12345L,
        mime: String = "image/jpeg",
        contentUri: String = "content://media/external/images/media/$id",
        realPath: String = "/storage/emulated/0/DCIM/Camera/img_$id.jpg",
        size: Long = 1_500_000L,
        durationMs: Long = 0L,
    ): LocalMedia = LocalMedia(
        id = id,
        path = contentUri,
        realPath = realPath,
        mimeType = mime,
        size = size,
        duration = durationMs,
        fileName = realPath.substringAfterLast('/'),
    )

    



    fun sandbox(
        realPath: String = "/data/user/0/com.difft.android/files/attachment/x.jpg",
        mime: String = "image/jpeg",
    ): LocalMedia = LocalMedia(
        path = "",
        realPath = realPath,
        mimeType = mime,
        fileName = realPath.substringAfterLast('/'),
    )

    
    fun legacyBarePath(
        barePath: String = "/storage/emulated/0/DCIM/a.jpg",
        mime: String = "image/jpeg",
    ): LocalMedia = LocalMedia(
        path = barePath,
        realPath = barePath,
        mimeType = mime,
        fileName = barePath.substringAfterLast('/'),
    )
}
