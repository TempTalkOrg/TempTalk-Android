package com.difft.android.chat.mediasend.v2

import com.difft.android.selector.entity.LocalMedia
import com.difft.android.chat.mediasend.MediaKey
import com.difft.android.chat.mediasend.v2.videos.VideoTrimData
import com.difft.android.chat.mms.MediaConstraints
import com.difft.android.chat.mms.SentMediaQuality
import com.difft.android.chat.util.MediaUtil
import com.difft.android.video.TranscodingPreset

data class MediaSelectionState(
    val selectedMedia: List<LocalMedia> = listOf(),
    val focusedMedia: LocalMedia? = null,
    val quality: SentMediaQuality = SentMediaQuality.HIGH,
    val message: CharSequence? = null,
    val isTouchEnabled: Boolean = true,
    val isSent: Boolean = false,
    val isPreUploadEnabled: Boolean = false,
    val isMeteredConnection: Boolean = false,
    /**
     * Editor state (crop / drawing / trim) by media identity.
     *
     * Keyed by [MediaKey] rather than a plain `Uri` so that every writer and reader is forced
     * through the single key derivation: a key written from one URI shape and read from another
     * discards the user's edit with no error at all, which the compiler can only catch if the key
     * domain is its own type. Not persisted and not parceled, so narrowing the type is free.
     */
    val editorStateMap: Map<MediaKey, Any> = mapOf(),
    val suppressEmptyError: Boolean = true,
    val confidentialMode: Int = 0,
    val showConfidentialToggle: Boolean = false
) {

    val isVideoTrimmingVisible: Boolean = focusedMedia != null && MediaUtil.isVideoType(focusedMedia.mimeType) && MediaConstraints.isVideoTranscodeAvailable()

    val transcodingPreset: TranscodingPreset = MediaConstraints.getPushMediaConstraints(SentMediaQuality.fromCode(quality.code)).videoTranscodingSettings

    val canSend = !isSent && selectedMedia.isNotEmpty()

    fun getOrCreateVideoTrimData(key: MediaKey): VideoTrimData {
        return editorStateMap[key] as? VideoTrimData ?: VideoTrimData()
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
