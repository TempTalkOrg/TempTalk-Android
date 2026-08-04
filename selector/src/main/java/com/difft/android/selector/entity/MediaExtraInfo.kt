package com.difft.android.selector.entity

class MediaExtraInfo {

    var videoThumbnail: String? = null

    var width: Int = 0

    var height: Int = 0

    var duration: Long = 0

    var orientation: String? = null

    override fun toString(): String {
        return "MediaExtraInfo{" +
                "videoThumbnail='" + videoThumbnail + '\'' +
                ", width=" + width +
                ", height=" + height +
                ", duration=" + duration +
                ", orientation='" + orientation + '\'' +
                '}'
    }
}
