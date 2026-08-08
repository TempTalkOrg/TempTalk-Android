package com.difft.android.selector.config

object PermissionEvent {
    const val EVENT_SOURCE_DATA = -1

    @JvmField
    val EVENT_IMAGE_CAMERA = SelectMimeType.ofImage()

    @JvmField
    val EVENT_VIDEO_CAMERA = SelectMimeType.ofVideo()
}
