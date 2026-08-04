package com.difft.android.chat.mediasend

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import com.difft.android.selector.entity.LocalMedia
import kotlinx.parcelize.Parcelize
import util.getParcelableExtraCompat

/**
 * One selected item plus the URI its final bytes must be read from: the transform output when a
 * transform rewrote the path, otherwise the normalized source URI.
 *
 * Resolved once, at the only place that knows whether a transform ran, so no consumer has to
 * re-derive it. Re-deriving with `readableUri()` after a transform would hand back the *pre-edit*
 * source, silently sending the unedited media (see the PRECONDITION on [readableUri]).
 *
 * A per-item wrapper rather than a parallel `List<Uri>`: a parallel list would rest on index
 * alignment, an invariant nothing can check, while this pairing cannot come apart structurally.
 */
@Parcelize
data class SendableMedia(val media: LocalMedia, val sendUri: Uri) : Parcelable

/**
 * A class that lets us nicely format data that we'll send back to [ConversationActivity].
 */
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
