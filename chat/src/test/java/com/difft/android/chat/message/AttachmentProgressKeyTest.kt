package com.difft.android.chat.message

import com.difft.android.base.utils.FileUtil
import com.difft.android.chat.attachment.attachmentRowKey
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import org.difft.app.database.models.AttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.difft.app.database.toAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Emit side and collect side must derive the SAME progress key or progress UI silently stops
 * updating. The emitters (DownloadAttachmentJob / PushTextSendJob) publish under the attachment
 * copy's own `localId`; every collector reads [getAttachmentIdForProgress]. These pin the two to
 * each other, for normal messages as well as forwarded ones.
 */
class AttachmentProgressKeyTest {

    private fun attachment(localId: String, isForwardCopy: Boolean = false) = Attachment(
        id = "server-id",
        authorityId = 777L,
        contentType = "image/jpeg",
        key = null,
        size = 10,
        thumbnail = null,
        digest = null,
        fileName = "photo.jpg",
        flags = 0,
        width = 1,
        height = 1,
        path = null,
        status = AttachmentStatus.LOADING.code,
        localId = localId,
        isForwardCopy = isForwardCopy
    )

    private fun message(id: String, block: TextChatMessage.() -> Unit) = TextChatMessage().apply {
        this.id = id
        authorId = "author"
        block()
    }

    private fun singleForwardMessage(id: String, leaf: Attachment) = message(id) {
        forwardContext = ForwardContext(
            listOf(Forward(1L, 0, false, "author", null, listOf(leaf), null, null)),
            false
        )
    }

    @Test
    fun `a normal attachment message is keyed by its attachment's localId`() {
        val att = attachment("local-normal")
        val msg = message("msg-1") { attachment = att }

        // Exactly the value the upload / download jobs emit under.
        assertEquals(att.localId, msg.getAttachmentIdForProgress())
    }

    @Test
    fun `a single-forward message is keyed by the forwarded copy, not by the message or authority id`() {
        val leaf = attachment("local-forward", isForwardCopy = true)
        val msg = singleForwardMessage("msg-2", leaf)

        assertEquals("local-forward", msg.getAttachmentIdForProgress())
        assertNotEquals("msg-2", msg.getAttachmentIdForProgress())
        assertNotEquals("777", msg.getAttachmentIdForProgress())
    }

    @Test
    fun `a message with no attachment keeps the message id as its key`() {
        assertEquals("msg-3", message("msg-3") { }.getAttachmentIdForProgress())
    }

    @Test
    fun `a row whose localId has not been backfilled falls back to the message id`() {
        val msg = message("msg-4") { attachment = attachment("") }

        assertEquals("msg-4", msg.getAttachmentIdForProgress())
    }

    @Test
    fun `progress emitted for a copy is read back by that copy's bubble`() {
        val att = attachment("local-roundtrip")
        val msg = message("msg-5") { attachment = att }

        FileUtil.emitProgressUpdate(att.localId, 37)

        assertEquals(37, msg.getAttachmentProgress())
    }

    @Test
    fun `a forwarded copy's progress never reaches the message it was forwarded from`() {
        // Both rows carry the same server-side id — the defect this rework closes.
        val original = message("msg-original") { attachment = attachment("local-original") }
        val forwarded = singleForwardMessage("msg-forward", attachment("local-forwarded", isForwardCopy = true))

        FileUtil.emitProgressUpdate("local-forwarded", 55)

        assertEquals(55, forwarded.getAttachmentProgress())
        assertNull(original.getAttachmentProgress())
    }

    /**
     * A persisted attachment row, and the bubble that renders it. Both sides of the pin below are
     * derived from this one row: the emitter through [attachmentRowKey], the collector through the
     * read mapper, exactly as production does.
     */
    private fun row(localId: String?, databaseId: Int = 42) = AttachmentModel().also {
        it.databaseId = databaseId
        it.id = "server-id"
        it.messageId = "msg-owner"
        it.authorityId = 777L
        it.contentType = "image/jpeg"
        it.fileName = "photo.jpg"
        it.status = AttachmentStatus.LOADING.code
        it.localId = localId
    }

    private fun bubbleFor(row: AttachmentModel) = message("msg-owner") { attachment = row.toAttachment() }

    /** What `DownloadAttachmentJob.resolveTarget` derives for [row] — the key it emits under. */
    private fun emitKey(row: AttachmentModel, jobLocalId: String?) =
        attachmentRowKey(row.localId, jobLocalId, row.synthesizedLocalId())

    @Test
    fun `a job persisted before the localId key emits under the key its backfilled row's bubble collects on`() {
        val row = row(localId = "local-backfilled")

        assertEquals(bubbleFor(row).getAttachmentIdForProgress(), emitKey(row, jobLocalId = null))
    }

    @Test
    fun `a job persisted before the localId key emits under the synthesized key of an un-backfilled row`() {
        // The upgrade window before the backfill stage runs. The pre-localId key was the MESSAGE id,
        // which no bubble ever collects on: the read mapper synthesizes an id for a NULL column, so
        // the spinner never advanced and the `progress == null` auto-download guard kept re-firing.
        val row = row(localId = null)

        val key = emitKey(row, jobLocalId = null)
        assertEquals(row.synthesizedLocalId(), key)
        assertEquals(bubbleFor(row).getAttachmentIdForProgress(), key)
        assertNotEquals("msg-owner", key)
    }

    @Test
    fun `a job carrying a localId emits under it, and its bubble collects on the same value`() {
        val row = row(localId = "local-modern")

        assertEquals("local-modern", emitKey(row, jobLocalId = "local-modern"))
        assertEquals(bubbleFor(row).getAttachmentIdForProgress(), emitKey(row, jobLocalId = "local-modern"))
    }

    @Test
    fun `an un-backfilled row this job could not adopt an id into keeps the requesting bubble's key`() {
        // Ambiguous legacy lookup: the id was not written into the row, so the row cannot name itself.
        // The key the requesting bubble used is still a per-copy address — never the shared legacy one.
        assertEquals("local-requested", emitKey(row(localId = null), jobLocalId = "local-requested"))
        assertEquals("local-requested", emitKey(row(localId = ""), jobLocalId = "local-requested"))
    }

    @Test
    fun `a crafted message carrying both an own attachment and a forward wrapper keys on what it renders`() {
        // No client produces this shape, but the wire format allows it. ChatMessageViewHolder binds
        // the FORWARD leaf for every forwards.size == 1 message, so the render-coupled selector must
        // follow the leaf; the action-coupled one deliberately stays own-first (save / favorite act
        // on the attachment the message itself carries).
        val own = attachment("local-own")
        val leaf = attachment("local-leaf", isForwardCopy = true)
        val msg = singleForwardMessage("msg-both", leaf).apply { attachment = own }

        assertEquals(leaf, msg.getRelevantAttachment())
        assertEquals(leaf.localId, msg.getAttachmentIdForProgress())
        assertEquals(own, msg.singleForwardableAttachment())
    }
}
