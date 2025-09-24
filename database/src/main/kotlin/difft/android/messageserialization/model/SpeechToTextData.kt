package difft.android.messageserialization.model

import java.io.Serializable

data class SpeechToTextData(
    var convertStatus: SpeechToTextStatus,
    var speechToTextContent: String?,
) : Serializable

enum class SpeechToTextStatus(val status: Int) {
    Invisible(0),
    Converting(1),
    Show(2);
    companion object {
        fun fromIntOrDefault(status: Int): SpeechToTextStatus {
            return entries.find { it.status == status } ?: Invisible
        }
    }
}