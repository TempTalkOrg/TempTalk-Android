package difft.android.messageserialization.attachment

import difft.android.messageserialization.model.Attachment
import io.reactivex.rxjava3.core.Completable

interface AttachmentStore {
    fun putAttachment(messageId: String, vararg attachments: Attachment): Completable
}