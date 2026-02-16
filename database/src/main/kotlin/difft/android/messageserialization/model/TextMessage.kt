package difft.android.messageserialization.model

import difft.android.messageserialization.For

open class TextMessage(
    id: String,
    fromWho: For,
    forWhat: For,
    systemShowTimestamp: Long,
    timeStamp: Long,
    receivedTimeStamp: Long,
    sendType: Int,
    expiresInSeconds: Int, ////0 indicate never expire
    notifySequenceId: Long,
    sequenceId: Long,
    mode: Int, // 0: normal, 1: CONFIDENTIAL
    var text: String?,
    var attachments: List<Attachment>? = null,
    var quote: Quote? = null,
    var forwardContext: ForwardContext? = null,
    var recall: Recall? = null,
    var card: Card? = null,
    var mentions: List<Mention>? = null,
    var atPersons: String? = null,
    var reactions: List<Reaction>? = null,
    var screenShot: ScreenShot? = null,
    var sharedContact: List<SharedContact>? = null,
    var translateData: TranslateData? = null,
    var speechToTextData: SpeechToTextData? = null,
    var playStatus: Int = 0,
    var receiverIds: String? = null,
    var criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE,
    var isUnsupported: Boolean = false, // true if message requires newer client version
) : Message(
    id,
    fromWho,
    forWhat,
    systemShowTimestamp,
    timeStamp,
    receivedTimeStamp,
    sendType,
    expiresInSeconds,
    notifySequenceId,
    sequenceId,
    mode,
)

fun TextMessage.isAttachmentMessage(): Boolean {
    return this.attachments?.isNotEmpty() == true
}