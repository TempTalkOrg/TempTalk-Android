package com.difft.android.chat.jobs

import org.junit.Assert.assertEquals
import org.junit.Test









class DownloadAttachmentJobExpiredStatusTest {

    @Test
    fun `authorization expired and storage purged both classify as expired`() {
        assertEquals(setOf(2, 12), DownloadAttachmentJob.EXPIRED_STATUS_CODES)
    }

    @Test
    fun `ok and the retryable statuses are not expired`() {
        for (status in listOf(0, 1, 9, 99)) {
            assertEquals("status=$status", false, status in DownloadAttachmentJob.EXPIRED_STATUS_CODES)
        }
    }
}
