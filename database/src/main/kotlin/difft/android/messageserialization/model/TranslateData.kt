package difft.android.messageserialization.model

import java.io.Serializable

data class TranslateData(
    var translateStatus: TranslateStatus,
    var translatedContentCN: String?,
    var translatedContentEN: String?
) : Serializable

enum class TranslateStatus(val status: Int) {
    Invisible(0),
    Translating(1),
    ShowCN(2),
    ShowEN(3);

    companion object {
        fun fromIntOrDefault(status: Int): TranslateStatus {
            return entries.find { it.status == status } ?: Invisible
        }
    }
}

enum class TranslateTargetLanguage(val language: String) {
    ZH("zh-cn"),
    EN("en")
}

enum class TranslateMsgType(val type: Int) {
    DTTranslateMsgSourceUnknown(0),
    DTTranslateMsgSource1On1(1),
    DTTranslateMsgSourceGroup(2),
    DTTranslateMsgSourceAnnouncement(3),
    DTTranslateMsgSourceNormalBot(4)
}
