package com.difft.android.chat.mediasend

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import com.difft.android.selector.entity.LocalMedia
import kotlinx.parcelize.Parcelize
import util.getParcelableExtraCompat












@Parcelize
data class SendableMedia(val media: LocalMedia, val sendUri: Uri) : Parcelable




@Parcelize
class MediaSendActivityResult(
    val media: List<SendableMedia> = emptyList(),
    val body: String,
    val confidentialMode: Int = 0
) : Parcelable {

    companion object {
        const val EXTRA_RESULT = "result"

        @JvmStatic
        fun fromData(data: Intent): MediaSendActivityResult {
            return data.getParcelableExtraCompat(EXTRA_RESULT, MediaSendActivityResult::class.java) ?: throw IllegalArgumentException()
        }
    }
}
