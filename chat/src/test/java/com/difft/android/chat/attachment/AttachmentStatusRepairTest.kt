package com.difft.android.chat.attachment

import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import org.junit.Assert.assertEquals
import org.junit.Test











class AttachmentStatusRepairTest {

    private fun attachment(authorityId: Long, status: Int, localId: String = "") = Attachment(
        id = "server-id",
        authorityId = authorityId,
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
        status = status,
        localId = localId
    )

    @Test
    fun `a never-uploaded attachment is never marked SUCCESS`() {
        val uploading = attachment(authorityId = 0L, status = AttachmentStatus.LOADING.code)

        AttachmentStatusRepair.markSuccessIfStale(uploading)

        assertEquals(AttachmentStatus.LOADING.code, uploading.status)
    }

    @Test
    fun `a failed outgoing attachment stays failed`() {
        val failed = attachment(authorityId = 0L, status = AttachmentStatus.FAILED.code)

        AttachmentStatusRepair.markSuccessIfStale(failed)

        assertEquals(AttachmentStatus.FAILED.code, failed.status)
    }

    @Test
    fun `a downloaded attachment with a stale LOADING status is repaired in memory`() {
        
        
        val stale = attachment(authorityId = 777L, status = AttachmentStatus.LOADING.code)

        AttachmentStatusRepair.markSuccessIfStale(stale)

        assertEquals(AttachmentStatus.SUCCESS.code, stale.status)
    }
}
