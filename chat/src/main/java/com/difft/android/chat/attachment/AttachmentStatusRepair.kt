package com.difft.android.chat.attachment

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.wcdb
import java.util.concurrent.ConcurrentHashMap









object AttachmentStatusRepair {

    
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    










    fun markSuccessIfStale(attachment: Attachment) {
        if (attachment.authorityId == 0L) return
        if (attachment.status == AttachmentStatus.SUCCESS.code) return
        attachment.status = AttachmentStatus.SUCCESS.code
        val localId = attachment.localId.takeIf { it.isNotEmpty() } ?: return
        if (!inFlight.add(localId)) return
        appScope.launch(Dispatchers.IO) {
            try {
                wcdb.attachment.updateValue(
                    AttachmentStatus.SUCCESS.code,
                    DBAttachmentModel.status,
                    DBAttachmentModel.localId.eq(localId)
                )
            } catch (e: Exception) {
                L.w { "[AttachStatusRepair] update failed localId=$localId: ${e.stackTraceToString()}" }
            } finally {
                inFlight.remove(localId)
            }
        }
    }
}
