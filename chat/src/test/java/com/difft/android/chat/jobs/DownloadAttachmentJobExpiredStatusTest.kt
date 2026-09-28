package com.difft.android.chat.jobs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `/v1/file/download` statuses that end a download as EXPIRED rather than as a retryable failure.
 *
 * Both are terminal on the server side — 2 once the download authorization lapses (30 days), 12 once
 * the storage object itself is purged (35 days) — so treating 12 as a failure only produced retries
 * that could never succeed and a "load error" the user read as a network problem. Pinned as a set so
 * the mapping cannot drift back to "only 2 means expired".
 */
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
