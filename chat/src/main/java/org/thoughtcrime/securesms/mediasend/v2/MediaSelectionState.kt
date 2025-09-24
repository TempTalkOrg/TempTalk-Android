package org.thoughtcrime.securesms.mediasend.v2

import android.net.Uri
import com.luck.picture.lib.entity.LocalMedia
import org.thoughtcrime.securesms.mediasend.v2.videos.VideoTrimData
import org.thoughtcrime.securesms.mms.MediaConstraints
import org.thoughtcrime.securesms.mms.SentMediaQuality
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.video.TranscodingPreset

data class MediaSelectionState(
    val selectedMedia: List<LocalMedia> = listOf(),
    val focusedMedia: LocalMedia? = null,
    val quality: SentMediaQuality = SentMediaQuality.HIGH,
    val message: CharSequence? = null,
    val isTouchEnabled: Boolean = true,
    val isSent: Boolean = false,
    val isPreUploadEnabled: Boolean = false,
    val isMeteredConnection: Boolean = false,
    val editorStateMap: Map<Uri, Any> = mapOf(),
    val suppressEmptyError: Boolean = true
) {

    val isVideoTrimmingVisible: Boolean = focusedMedia != null && MediaUtil.isVideoType(focusedMedia.mimeType) && MediaConstraints.isVideoTranscodeAvailable()

    val transcodingPreset: TranscodingPreset = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(quality.code)).videoTranscodingSettings

    val canSend = !isSent && selectedMedia.isNotEmpty()

    fun getOrCreateVideoTrimData(uri: Uri): VideoTrimData {
        return editorStateMap[uri] as? VideoTrimData ?: VideoTrimData()
    }

    enum class ViewOnceToggleState(val code: Int) {
        INFINITE(0),
        ONCE(1);

        fun next(): ViewOnceToggleState {
            return when (this) {
                INFINITE -> ONCE
                ONCE -> INFINITE
            }
        }

        companion object {
            val default = INFINITE

            fun fromCode(code: Int): ViewOnceToggleState {
                return when (code) {
                    1 -> ONCE
                    else -> INFINITE
                }
            }
        }
    }
}
