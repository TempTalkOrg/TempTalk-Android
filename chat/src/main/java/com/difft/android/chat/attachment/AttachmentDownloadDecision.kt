package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.AttachmentStatus





enum class AttachmentDownloadAction {
    
    AUTO_DOWNLOAD,

    
    SHOW_PROGRESS,

    
    READY
}


enum class AttachmentTapBlockedReason {
    
    EXPIRED,

    
    DOWNLOADING
}







sealed interface AttachmentTapAction {
    
    data class Blocked(val reason: AttachmentTapBlockedReason) : AttachmentTapAction

    
    data object Download : AttachmentTapAction

    




    data object Rescue : AttachmentTapAction

    
    data object Open : AttachmentTapAction
}









object AttachmentDownloadDecision {

    
    const val PROGRESS_EXPIRED = -2

    
    const val PROGRESS_FAILED = -1

    



    fun isExpired(status: Int, progress: Int?): Boolean =
        if (progress != null) progress == PROGRESS_EXPIRED else status == AttachmentStatus.EXPIRED.code

    
    fun isFailed(status: Int, progress: Int?): Boolean =
        if (progress != null) progress == PROGRESS_FAILED else status == AttachmentStatus.FAILED.code

    





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

    













    fun isUploadInFlight(isFileValid: Boolean, progress: Int?): Boolean =
        isFileValid && isTransferInFlight(progress)

    



    fun isTransferInFlight(progress: Int?): Boolean = progress != null && progress in 0..99

    



    fun shouldPromptManualDownload(isFileValid: Boolean, sizeBytes: Int, progress: Int?): Boolean =
        sizeBytes > FileUtil.LARGE_FILE_THRESHOLD &&
            downloadAction(isFileValid, progress) == AttachmentDownloadAction.AUTO_DOWNLOAD

    


















    fun shouldRescueMissingFile(isFileValid: Boolean, status: Int, progress: Int?): Boolean =
        downloadAction(isFileValid, progress) == AttachmentDownloadAction.AUTO_DOWNLOAD &&
            status == AttachmentStatus.SUCCESS.code

    





    fun needsFetch(isFileValid: Boolean): Boolean = !isFileValid
}
