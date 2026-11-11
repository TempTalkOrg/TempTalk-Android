package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.AttachmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class AttachmentDownloadDecisionTest {

    private val allStatuses = AttachmentStatus.entries.map { it.code }
    private val allProgresses = listOf(null, 0, 50, 100, -1, -2)

    @Test
    fun `a readable file is always READY, whatever the status or progress says`() {
        for (status in allStatuses) {
            for (progress in allProgresses) {
                assertEquals(
                    "isFileValid=true status=$status progress=$progress",
                    AttachmentDownloadAction.READY,
                    AttachmentDownloadDecision.downloadAction(isFileValid = true, progress = progress)
                )
            }
        }
    }

    @Test
    fun `no file and no running download auto-downloads, whatever the status says`() {
        for (status in allStatuses) {
            assertEquals(
                "status=$status",
                AttachmentDownloadAction.AUTO_DOWNLOAD,
                AttachmentDownloadDecision.downloadAction(isFileValid = false, progress = null)
            )
        }
    }

    @Test
    fun `no file with a live progress value shows progress instead of re-downloading`() {
        for (progress in allProgresses.filterNotNull()) {
            assertEquals(
                "progress=$progress",
                AttachmentDownloadAction.SHOW_PROGRESS,
                AttachmentDownloadDecision.downloadAction(isFileValid = false, progress = progress)
            )
        }
    }

    @Test
    fun `a large file already on disk is never prompted for`() {
        val large = FileUtil.LARGE_FILE_THRESHOLD + 1

        assertFalse(AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid = true, sizeBytes = large, progress = null))
        assertTrue(AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid = false, sizeBytes = large, progress = null))
    }

    @Test
    fun `a large file being downloaded is not prompted for, and a small missing file is not either`() {
        val large = FileUtil.LARGE_FILE_THRESHOLD + 1
        val small = 1024

        assertFalse(AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid = false, sizeBytes = large, progress = 30))
        assertFalse(AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid = false, sizeBytes = small, progress = null))
    }

    @Test
    fun `a readable file with progress running is an upload, which READY alone cannot express`() {
        for (progress in listOf(0, 1, 50, 99)) {
            assertTrue(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isFileValid = true, progress = progress)
            )
            
            
            assertEquals(
                AttachmentDownloadAction.READY,
                AttachmentDownloadDecision.downloadAction(isFileValid = true, progress = progress)
            )
        }
    }

    @Test
    fun `a finished, failed, expired or unstarted transfer is not an upload in flight`() {
        for (progress in listOf(null, 100, -1, -2)) {
            assertFalse(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isFileValid = true, progress = progress)
            )
        }
    }

    @Test
    fun `progress without a readable file is a download, never an upload, whoever sent the message`() {
        
        
        for (progress in allProgresses) {
            assertFalse(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isFileValid = false, progress = progress)
            )
        }
    }

    @Test
    fun `the download-side states apply exactly when no readable file is here`() {
        assertTrue(AttachmentDownloadDecision.needsFetch(isFileValid = false))
        
        
        assertFalse(AttachmentDownloadDecision.needsFetch(isFileValid = true))
    }

    @Test
    fun `a transfer is in flight only while progress is between 0 and 99`() {
        for (progress in listOf(0, 1, 50, 99)) {
            assertTrue("progress=$progress", AttachmentDownloadDecision.isTransferInFlight(progress))
        }
        for (progress in listOf(null, 100, -1, -2)) {
            assertFalse("progress=$progress", AttachmentDownloadDecision.isTransferInFlight(progress))
        }
    }

    @Test
    fun `a live progress entry decides expired and failed, whatever the persisted status says`() {
        for (status in allStatuses) {
            assertTrue("status=$status", AttachmentDownloadDecision.isExpired(status, progress = -2))
            assertFalse("status=$status", AttachmentDownloadDecision.isExpired(status, progress = -1))
            assertTrue("status=$status", AttachmentDownloadDecision.isFailed(status, progress = -1))
            assertFalse("status=$status", AttachmentDownloadDecision.isFailed(status, progress = -2))
            for (progress in listOf(0, 50, 100)) {
                assertFalse("status=$status progress=$progress", AttachmentDownloadDecision.isExpired(status, progress))
                assertFalse("status=$status progress=$progress", AttachmentDownloadDecision.isFailed(status, progress))
            }
        }
    }

    @Test
    fun `without a progress entry the persisted status decides, and expired and failed never overlap`() {
        for (status in allStatuses) {
            val expired = AttachmentDownloadDecision.isExpired(status, progress = null)
            val failed = AttachmentDownloadDecision.isFailed(status, progress = null)
            assertEquals("status=$status", status == AttachmentStatus.EXPIRED.code, expired)
            assertEquals("status=$status", status == AttachmentStatus.FAILED.code, failed)
            assertFalse("status=$status", expired && failed)
        }
    }

    @Test
    fun `tap policy - expired is a dead end before anything else`() {
        val expired = AttachmentTapAction.Blocked(AttachmentTapBlockedReason.EXPIRED)
        for (isFileValid in listOf(true, false)) {
            for (size in listOf(1024, FileUtil.LARGE_FILE_THRESHOLD + 1)) {
                assertEquals(expired, AttachmentDownloadDecision.tapAction(AttachmentStatus.EXPIRED.code, progress = null, isFileValid, size))
                
                assertEquals(expired, AttachmentDownloadDecision.tapAction(AttachmentStatus.LOADING.code, progress = -2, isFileValid, size))
            }
        }
    }

    @Test
    fun `tap policy - a transfer in flight is a dead end, a failed one retries`() {
        val downloading = AttachmentTapAction.Blocked(AttachmentTapBlockedReason.DOWNLOADING)
        for (progress in listOf(0, 1, 50, 99)) {
            assertEquals("progress=$progress", downloading, AttachmentDownloadDecision.tapAction(AttachmentStatus.LOADING.code, progress, isFileValid = false, sizeBytes = 1024))
        }
        assertEquals(AttachmentTapAction.Download, AttachmentDownloadDecision.tapAction(AttachmentStatus.FAILED.code, progress = null, isFileValid = false, sizeBytes = 1024))
        assertEquals(AttachmentTapAction.Download, AttachmentDownloadDecision.tapAction(AttachmentStatus.LOADING.code, progress = -1, isFileValid = false, sizeBytes = 1024))
    }

    @Test
    fun `tap policy - a readable file opens, whatever the status says`() {
        for (status in listOf(AttachmentStatus.SUCCESS.code, AttachmentStatus.LOADING.code)) {
            for (progress in listOf(null, 100)) {
                assertEquals(
                    "status=$status progress=$progress",
                    AttachmentTapAction.Open,
                    AttachmentDownloadDecision.tapAction(status, progress, isFileValid = true, sizeBytes = FileUtil.LARGE_FILE_THRESHOLD + 1)
                )
            }
        }
    }

    @Test
    fun `tap policy - without a readable file a large file downloads on request and anything else is rescued`() {
        val large = FileUtil.LARGE_FILE_THRESHOLD + 1
        assertEquals(AttachmentTapAction.Download, AttachmentDownloadDecision.tapAction(AttachmentStatus.LOADING.code, progress = null, isFileValid = false, sizeBytes = large))
        
        assertEquals(AttachmentTapAction.Rescue, AttachmentDownloadDecision.tapAction(AttachmentStatus.LOADING.code, progress = null, isFileValid = false, sizeBytes = 1024))
        assertEquals(AttachmentTapAction.Rescue, AttachmentDownloadDecision.tapAction(AttachmentStatus.SUCCESS.code, progress = null, isFileValid = false, sizeBytes = 1024))
        assertEquals(AttachmentTapAction.Rescue, AttachmentDownloadDecision.tapAction(AttachmentStatus.SUCCESS.code, progress = 100, isFileValid = false, sizeBytes = large))
    }

    

    @Test
    fun `a missing file the row calls transferred is asked for, not reported as an error`() {
        
        assertTrue(
            AttachmentDownloadDecision.shouldRescueMissingFile(
                isFileValid = false,
                status = AttachmentStatus.SUCCESS.code,
                progress = null
            )
        )
    }

    @Test
    fun `a file that is on disk is never rescued`() {
        assertFalse(
            AttachmentDownloadDecision.shouldRescueMissingFile(
                isFileValid = true,
                status = AttachmentStatus.SUCCESS.code,
                progress = null
            )
        )
    }

    @Test
    fun `a rescue already running is not started again`() {
        
        
        for (progress in allProgresses.filterNotNull()) {
            assertFalse(
                "progress=$progress",
                AttachmentDownloadDecision.shouldRescueMissingFile(
                    isFileValid = false,
                    status = AttachmentStatus.SUCCESS.code,
                    progress = progress
                )
            )
        }
    }

    @Test
    fun `a row that never completed a transfer is left to the existing download and error paths`() {
        
        
        for (status in allStatuses.filter { it != AttachmentStatus.SUCCESS.code }) {
            assertFalse(
                "status=$status",
                AttachmentDownloadDecision.shouldRescueMissingFile(
                    isFileValid = false,
                    status = status,
                    progress = null
                )
            )
        }
    }

    
}
