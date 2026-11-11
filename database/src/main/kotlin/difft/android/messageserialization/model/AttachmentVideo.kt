package difft.android.messageserialization.model







object VideoProbeState {
    const val NONE = 0
    const val IN_PROGRESS_1 = 1
    const val IN_PROGRESS_2 = 2
    const val FAILED = 3
}


private val WHITELISTED_VIDEO_TYPES = setOf(
    "video/3gpp",
    "video/3gpp2",
    "video/mp4",
    "video/quicktime",
    "video/x-m4v",
)


fun normalizeContentType(contentType: String?): String =
    contentType.orEmpty().substringBefore(';').trim().lowercase()





fun isWhitelistedVideoType(contentType: String?): Boolean =
    normalizeContentType(contentType) in WHITELISTED_VIDEO_TYPES






fun Attachment.isPlayableVideo(): Boolean =
    isWhitelistedVideoType(contentType) && width > 0 && height > 0 && videoProbeState != VideoProbeState.FAILED


fun Attachment.isVideoShownAsFile(): Boolean =
    normalizeContentType(contentType).startsWith("video/") && !isPlayableVideo()
