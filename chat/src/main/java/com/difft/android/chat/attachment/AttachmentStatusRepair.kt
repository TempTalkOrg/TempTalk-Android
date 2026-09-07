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

/**
 * Reconciles an attachment row whose status lags behind the file that is actually on disk.
 *
 * Once addressing is per copy, a readable file is the authority on "downloaded" and status is only a
 * cached answer, so a bubble must never re-download because of a stale status. This writes the
 * correct answer back so the staleness does not survive the next rebind — scoped to the single row
 * by [Attachment.localId], never by the shared server-side `id`.
 */
object AttachmentStatusRepair {

    /** localIds with an in-flight write, so scrolling can't queue the same update repeatedly. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * Marks [attachment] SUCCESS when its file is present but its status says otherwise. Updates the
     * in-memory object immediately (so THIS bind is already consistent) and persists off the main
     * thread. A row that cannot be located by localId is left alone — a lagging status only costs a
     * redundant download, so there is nothing worth a riskier write.
     *
     * An attachment with no authorityId has never been uploaded: the file on disk is the staged
     * local original, so its presence proves nothing about the transfer and calling this SUCCESS
     * would freeze an in-flight (or failed) send as delivered. Guarded here rather than at each
     * caller so no bubble can reintroduce it.
     */
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
