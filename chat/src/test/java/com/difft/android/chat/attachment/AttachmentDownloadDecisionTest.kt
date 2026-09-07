package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.AttachmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The auto-download condition matrix.
 *
 * The defect being pinned: the old expression `status != SUCCESS && progress != 100 || !isFileValid`
 * downloaded whenever status lagged, even with the file on disk — which is exactly the state a
 * forwarded attachment lands in. The matrix below sweeps the full (isFileValid × status × progress)
 * space and asserts status NEVER changes the outcome.
 */
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
    fun `an outgoing transfer in flight is reported as uploading, which READY alone cannot express`() {
        for (progress in listOf(0, 1, 50, 99)) {
            assertTrue(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isCurrentDeviceSend = true, progress = progress)
            )
            // The download rule sees the staged file and correctly calls it READY — which is exactly
            // why the upload needs its own question.
            assertEquals(
                AttachmentDownloadAction.READY,
                AttachmentDownloadDecision.downloadAction(isFileValid = true, progress = progress)
            )
        }
    }

    @Test
    fun `a finished, failed, expired or unstarted outgoing transfer is not in flight`() {
        for (progress in listOf(null, 100, -1, -2)) {
            assertFalse(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isCurrentDeviceSend = true, progress = progress)
            )
        }
    }

    @Test
    fun `an incoming attachment is never an upload in flight`() {
        for (progress in allProgresses) {
            assertFalse(
                "progress=$progress",
                AttachmentDownloadDecision.isUploadInFlight(isCurrentDeviceSend = false, progress = progress)
            )
        }
    }

    // region rescuing a file the row says we have

    @Test
    fun `a missing file the row calls transferred is asked for, not reported as an error`() {
        // The dead end this removes: a display or tap gate finds nothing on disk, shows "load error",
        // and nothing ever fixes it — while the file may be sitting at a pre-per-copy address that
        // only the download job's local rescue can reach.
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
        // The idempotence guard: a job that has started publishes progress under this copy's key, and
        // every gate re-reads that map before asking again. Repeated taps must not pile up jobs.
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
        // SUCCESS is the whole justification: only it says these bytes were completely transferred
        // once, which is what makes their absence a MOVE rather than a download that never happened.
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

    // endregion
}
