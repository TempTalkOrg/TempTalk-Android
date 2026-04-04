package difft.android.messageserialization.model

import java.io.Serializable

/**
 * Content type for long text messages converted to file attachments.
 * This type indicates that the attachment contains plain text content
 * that should be displayed as text rather than as a file attachment.
 */
const val CONTENT_TYPE_LONG_TEXT = "text/x-signal-plain"

data class Attachment(
    val id: String,
    var authorityId: Long, //authorityId
    var contentType: String,
    var key: ByteArray?,
    var size: Int,
    var thumbnail: ByteArray?,
    var digest: ByteArray?,
    var fileName: String?,
    var flags: Int, //0: normal, 1: audio
    var width: Int,
    var height: Int,
    var path: String?,
    var status: Int,
    var playProgress: Int = 0,
    var isPlaying: Boolean = false,
    var fileHash: String? = null,
    var totalTime: Long? = 0,
    var amplitudes: List<Float>? = null
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Attachment

        if (authorityId != other.authorityId) return false
        if (size != other.size) return false
        if (flags != other.flags) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (status != other.status) return false
        if (playProgress != other.playProgress) return false
        if (isPlaying != other.isPlaying) return false
        if (totalTime != other.totalTime) return false
        if (id != other.id) return false
        if (contentType != other.contentType) return false
        if (key != null) {
            if (other.key == null) return false
            if (!key.contentEquals(other.key)) return false
        } else if (other.key != null) return false
        if (thumbnail != null) {
            if (other.thumbnail == null) return false
            if (!thumbnail.contentEquals(other.thumbnail)) return false
        } else if (other.thumbnail != null) return false
        if (digest != null) {
            if (other.digest == null) return false
            if (!digest.contentEquals(other.digest)) return false
        } else if (other.digest != null) return false
        if (fileName != other.fileName) return false
        if (path != other.path) return false
        if (fileHash != other.fileHash) return false
        if (amplitudes != other.amplitudes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = authorityId.hashCode()
        result = 31 * result + size
        result = 31 * result + flags
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + status
        result = 31 * result + playProgress
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + (totalTime?.hashCode() ?: 0)
        result = 31 * result + id.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (key?.contentHashCode() ?: 0)
        result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
        result = 31 * result + (digest?.contentHashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (path?.hashCode() ?: 0)
        result = 31 * result + (fileHash?.hashCode() ?: 0)
        result = 31 * result + (amplitudes?.hashCode() ?: 0)
        return result
    }
}

fun Attachment.isImage(): Boolean {
    return this.contentType.contains("image")
}

fun Attachment.isVideo(): Boolean {
    return this.contentType.contains("video")
}

//是否是音频消息
fun Attachment.isAudioMessage(): Boolean {
    return flags == 1 && this.contentType.contains("audio")
}
//是否是音频文件
fun Attachment.isAudioFile(): Boolean {
    return flags == 0 && this.contentType.contains("audio")
}

/**
 * Check if the attachment is a long text file (oversized text converted to file)
 */
fun Attachment.isLongText(): Boolean {
    return this.contentType == CONTENT_TYPE_LONG_TEXT
}

enum class AttachmentStatus(val code: Int) {
    LOADING(2),
    SUCCESS(3),
    FAILED(4),
    EXPIRED(5)
}
