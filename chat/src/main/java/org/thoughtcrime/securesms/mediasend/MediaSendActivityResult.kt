package org.thoughtcrime.securesms.mediasend

import android.content.Intent
import android.os.Parcelable
import com.luck.picture.lib.entity.LocalMedia
import kotlinx.parcelize.Parcelize
import util.getParcelableExtraCompat

/**
 * A class that lets us nicely format data that we'll send back to [ConversationActivity].
 */
@Parcelize
class MediaSendActivityResult(
    val media: List<LocalMedia> = emptyList(),
    val body: String
) : Parcelable {

    companion object {
        const val EXTRA_RESULT = "result"

        @JvmStatic
        fun fromData(data: Intent): MediaSendActivityResult {
            return data.getParcelableExtraCompat(EXTRA_RESULT, MediaSendActivityResult::class.java) ?: throw IllegalArgumentException()
        }
    }
}
