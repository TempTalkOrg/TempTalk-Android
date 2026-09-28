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

/** Why a tap on an attachment may not proceed. The UI maps each reason to its own toast. */
enum class AttachmentTapBlockedReason {
    /** The server no longer serves the file (see [AttachmentDownloadDecision.isExpired]). */
    EXPIRED,

    /** The bytes are still arriving; the bubble already shows the progress. */
    DOWNLOADING
}

/**
 * What a tap on an attachment bubble must do. Decided in ONE place ([AttachmentDownloadDecision.tapAction])
 * for every surface that opens attachments — chat list, forward detail, confidential forwards — so
 * that no entry can reach [Open] (and, for a confidential message, the view receipt and the burn)
 * without the same checks.
 */
sealed interface AttachmentTapAction {
    /** Nothing may follow; the user is told why. No receipt, no delete. */
    data class Blocked(val reason: AttachmentTapBlockedReason) : AttachmentTapAction

    /** Start or retry the download silently: a failed transfer, or a large file the user asked for. */
    data object Download : AttachmentTapAction

    /**
     * The row says nothing is wrong yet no readable file is here — a transfer that died with the
     * process, or a file that moved or vanished. Re-fetch (the job's first act is the legacy-address
     * rescue) and tell the user the file is being prepared.
     */
    data object Rescue : AttachmentTapAction

    /** A readable file is on disk: open the preview / viewer / detail. */
    data object Open : AttachmentTapAction
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

    /** Progress-map sentinel published by the download job for a terminal, non-retryable expiry. */
    const val PROGRESS_EXPIRED = -2

    /** Progress-map sentinel published by the download job for a retryable failure. */
    const val PROGRESS_FAILED = -1

    /**
     * Whether the server has stopped serving this attachment for good. A live progress entry is the
     * fresher signal and wins over the persisted status, same precedence every bubble uses.
     */
    fun isExpired(status: Int, progress: Int?): Boolean =
        if (progress != null) progress == PROGRESS_EXPIRED else status == AttachmentStatus.EXPIRED.code

    /** Whether the last download attempt failed in a way a retry can fix. */
    fun isFailed(status: Int, progress: Int?): Boolean =
        if (progress != null) progress == PROGRESS_FAILED else status == AttachmentStatus.FAILED.code

    /**
     * The tap policy, in precedence order: expired and in-flight transfers are dead ends; a failed
     * transfer retries; a readable file opens; a large file not yet fetched starts its download on
     * request; anything else is a file that should be here and is not — rescue it. A readable file
     * is the authority for [AttachmentTapAction.Open], never the persisted status.
     */
    fun tapAction(status: Int, progress: Int?, isFileValid: Boolean, sizeBytes: Int): AttachmentTapAction = when {
        isExpired(status, progress) -> AttachmentTapAction.Blocked(AttachmentTapBlockedReason.EXPIRED)
        isTransferInFlight(progress) -> AttachmentTapAction.Blocked(AttachmentTapBlockedReason.DOWNLOADING)
        isFailed(status, progress) -> AttachmentTapAction.Download
        isFileValid -> AttachmentTapAction.Open
        shouldPromptManualDownload(isFileValid, sizeBytes, progress) -> AttachmentTapAction.Download
        else -> AttachmentTapAction.Rescue
    }

    fun downloadAction(isFileValid: Boolean, progress: Int?): AttachmentDownloadAction = when {
        isFileValid -> AttachmentDownloadAction.READY
        progress == null -> AttachmentDownloadAction.AUTO_DOWNLOAD
        else -> AttachmentDownloadAction.SHOW_PROGRESS
    }

    /**
     * Whether the transfer running for this copy is an UPLOAD.
     *
     * The file decides, not the message's direction: an outgoing send stages its file at the address
     * the bubble reads BEFORE the upload starts and keeps it there until the upload lands, while a
     * download never exposes a readable file before it is complete and verified. So a readable file
     * with progress in flight can only be an upload. [downloadAction] cannot answer this and must not
     * try — it sees the staged file and correctly calls the copy READY — which is also why an upload
     * in flight must not let a bubble repair the row's status to SUCCESS.
     *
     * Direction is deliberately not an input. A message's id encodes the SENDING device, and an own
     * message that arrived as a sync from another device (or came across in a data migration) is
     * still a copy this device has to fetch, exactly like anyone else's.
     */
    fun isUploadInFlight(isFileValid: Boolean, progress: Int?): Boolean =
        isFileValid && isTransferInFlight(progress)

    /**
     * Whether ANY transfer (download or upload) is currently running for this copy. While it runs the
     * bubble shows the progress bar and hides its open button, so a tap must not open or "rescue".
     */
    fun isTransferInFlight(progress: Int?): Boolean = progress != null && progress in 0..99

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
     * Whether a bubble with NO readable file should render the download-side states (expired, failed,
     * tap-to-download) at all, as opposed to a bubble whose bytes are here. The one case this keeps
     * apart: a row marked FAILED while its staged file is still present is a failed UPLOAD, which the
     * message-level send state reports; it must not be drawn as a download waiting for a retry.
     */
    fun needsFetch(isFileValid: Boolean): Boolean = !isFileValid
}
