package com.difft.android.chat.attachment

import android.content.Context
import android.content.ContextWrapper
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isPlayableVideo
import difft.android.messageserialization.model.isVideoShownAsFile






fun Attachment.opensInAppViewer(isConfidential: Boolean): Boolean =
    isImage() || isPlayableVideo() || (isConfidential && isVideoShownAsFile())


fun Attachment.mayOpenExternally(isConfidential: Boolean): Boolean =
    !(isConfidential && isVideoShownAsFile())


interface ConfidentialViewerHost {
    fun isConfidentialViewer(): Boolean
}


fun Context.isInConfidentialViewer(): Boolean {
    var current: Context? = this
    while (current != null) {
        if (current is ConfidentialViewerHost) return current.isConfidentialViewer()
        current = (current as? ContextWrapper)?.baseContext
    }
    return false
}
