package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.AttachmentStatus

/**
 * What a bubble should do about an attachment's bytes. Shared by the three attachment widgets so the
 * download-readiness rule lives in exactly one place.
 */
enum class AttachmentDownloadAction {
    /** Nothing is on disk and nothing is running: start the download. */
    AUTO_DOWNLOAD,

    /** A download is running: show its progress. */
    SHOW_PROGRESS,

    /** The file is on disk: render it. */
    READY
}

/**
 * The single rule for "does this bubble need bytes?".
 *
 * A readable file is the authority: whether the row's `status` still says LOADING is irrelevant, and
 * deliberately not an input here. A file already on disk (a forward copy, or a row whose status write
 * was lost) must render immediately rather than being downloaded again on every bind.
 * Reconciling the lagging status is [AttachmentStatusRepair]'s job, not this decision's.
 */
object AttachmentDownloadDecision {

    fun downloadAction(isFileValid: Boolean, progress: Int?): AttachmentDownloadAction = when {
        isFileValid -> AttachmentDownloadAction.READY
        progress == null -> AttachmentDownloadAction.AUTO_DOWNLOAD
        else -> AttachmentDownloadAction.SHOW_PROGRESS
    }

    /**
     * Whether this bubble's own OUTGOING transfer is still running.
     *
     * [downloadAction] cannot answer this and must not try: for a message sent from this device the
     * file is staged at the address the bubble reads BEFORE the upload starts, so `isFileValid` is
     * true for the whole transfer and the download rule correctly calls it READY. Only the progress
     * emitted under this copy's key distinguishes "uploading" from "uploaded" — which is also why an
     * upload in flight must not let a bubble repair the row's status to SUCCESS.
     */
    fun isUploadInFlight(isCurrentDeviceSend: Boolean, progress: Int?): Boolean =
        isCurrentDeviceSend && progress != null && progress in 0..99

    /**
     * Whether to replace the automatic download with a "tap to download" prompt. Same authority: a
     * large file already on disk is ready, not something to prompt for.
     */
    fun shouldPromptManualDownload(isFileValid: Boolean, sizeBytes: Int, progress: Int?): Boolean =
        sizeBytes > FileUtil.LARGE_FILE_THRESHOLD &&
            downloadAction(isFileValid, progress) == AttachmentDownloadAction.AUTO_DOWNLOAD

    /**
     * Whether a path that found NO readable file should ASK for the bytes instead of dead-ending in
     * an error toast or a blank bubble.
     *
     * `status == SUCCESS` carries the whole argument: the row states these bytes were completely
     * transferred at some point, so their absence from the current address means the file MOVED —
     * an attachment written before per-copy addressing that the migration has not reached yet — or
     * was removed. Never that it was never fetched. Enqueuing the download job is the ONE recovery
     * that works for both: its first act is the migration's local rescue, so a file still sitting at
     * a legacy address is brought across from disk with no network at all, and only a genuinely
     * missing file goes on to be re-fetched.
     *
     * This is also why the read gates themselves stay migration-free: they run on the main thread,
     * where the rescue's blocking IO must never happen, and the job is where that IO belongs.
     *
     * `progress == null` is what keeps repeated taps and rebinds from piling up jobs: a job that has
     * started publishes progress under this copy's key, and every caller re-reads that map first —
     * the same guard the automatic download path has always used.
     */
    fun shouldRescueMissingFile(isFileValid: Boolean, status: Int, progress: Int?): Boolean =
        downloadAction(isFileValid, progress) == AttachmentDownloadAction.AUTO_DOWNLOAD &&
            status == AttachmentStatus.SUCCESS.code

    /**
     * Whether an [AttachmentDownloadAction.AUTO_DOWNLOAD] verdict should actually enqueue the job for
     * THIS bubble.
     *
     * Own sends normally have nothing to fetch — except when [shouldRescueMissingFile] applies: the
     * row says the bytes were transferred and they are not at this address, so the file moved
     * (pre-per-copy addressing) or is gone, and the job's local rescue is what brings it back.
     */
    fun shouldAutoDownload(isCurrentDeviceSend: Boolean, isFileValid: Boolean, status: Int, progress: Int?): Boolean =
        !isCurrentDeviceSend || shouldRescueMissingFile(isFileValid, status, progress)
}
