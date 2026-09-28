package com.difft.android.chat.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentDownloadAction
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentStatusRepair
import com.difft.android.chat.databinding.LayoutAttachMessageViewBinding
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentIdForProgress
import com.difft.android.chat.message.getAttachmentProgress
import com.hi.dhl.binding.viewbind
import difft.android.messageserialization.model.isLongText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.util.viewFile

class AttachMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    val binding: LayoutAttachMessageViewBinding by viewbind(this)

    private var progressJob: Job? = null
    private var currentAttachmentId: String? = null
    private var currentMessage: TextChatMessage? = null

    fun setupAttachmentView(message: TextChatMessage) {
        // Progress key authority — must match what the emit side publishes.
        currentAttachmentId = message.getAttachmentIdForProgress()
        currentMessage = message

        val attachment = message.attachment ?: return

        val attachmentPath = AttachmentPathResolver.fileFor(attachment)

        binding.open.visibility = View.INVISIBLE
        binding.progress.visibility = View.INVISIBLE
        binding.tvDownloadHint.visibility = View.INVISIBLE
        binding.attachmentSize.visibility = View.VISIBLE

        binding.attachmentName.text = attachment.fileName
        binding.attachmentSize.text = FileUtil.readableFileSize(attachment.size.toLong())

        val progress = message.getAttachmentProgress()
        // Encrypted-at-rest types (e.g. long text) keep only "<path>.encrypt" on disk, so a
        // plaintext-only isFileValid() would report "not downloaded" and trigger an endless
        // re-download loop (each re-download resets status to LOADING), which in turn keeps the
        // long-text Read-more gate closed → only the 2KB preview shows. Gate on isReadable instead.
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath, attachment.size)
        if (!isFileValid) {
            // A bubble that offers a download for a file this device should already hold is only
            // diagnosable from the row it rendered: which directory it named, and what it believed.
            L.w {
                "[Attachment] file not readable messageId=${message.id} localId=${attachment.localId} " +
                    "fileNameLen=${attachment.fileName?.length} fileNameHash=${attachment.fileName?.hashCode()} " +
                    "status=${attachment.status} size=${attachment.size} mine=${message.isMine} " +
                    "forward=${message.forwardContext != null} quote=${message.quote != null} " +
                    "dirExists=${java.io.File(attachmentPath).parentFile?.exists()}"
            }
        }
        // The file decides the state, never the message's direction (see AttachmentDownloadDecision):
        // a readable file with progress running is an upload; anything else is a copy to fetch.
        val isUploading = AttachmentDownloadDecision.isUploadInFlight(isFileValid, progress)
        // A readable file is the authority on "downloaded"; repair a lagging status before the
        // branches below read it, so no branch can order a download for a file we have.
        if (isFileValid && !isUploading) AttachmentStatusRepair.markSuccessIfStale(attachment)

        if (AttachmentDownloadDecision.needsFetch(isFileValid)) {
            // Priority 1: Show expired view if file has expired. The status takes the size line:
            // there is no file whose size would mean anything any more.
            if (AttachmentDownloadDecision.isExpired(attachment.status, progress)) {
                showStatusInsteadOfSize(context.getString(R.string.file_expired))
                return
            }

            // Priority 2: Show fail view if download failed
            if (AttachmentDownloadDecision.isFailed(attachment.status, progress)) {
                showStatusInsteadOfSize(context.getString(R.string.download_failed))
                return
            }

            // Priority 2: Show download prompt for files > 10M
            val fileSize = attachment.size
            if (AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid, fileSize, progress)) {
                val fileSizeText = FileUtil.readableFileSize(fileSize.toLong())
                showStatusInsteadOfSize(context.getString(R.string.chat_tap_to_download) + " ($fileSizeText)")
                return
            }
        }

        // `open` and `progress` share one FrameLayout cell (both layout_gravity=center), so the ready
        // affordance must not be raised while the upload branch below claims that cell.
        if (isFileValid && !isUploading) {
            binding.open.visibility = View.VISIBLE
            binding.open.setOnClickListener {
                context.viewFile(attachmentPath)
            }
        }

        // Priority 3: Show progress or auto download (for files <= 10M). An outgoing upload owns the
        // progress bar — the download decision sees its staged file and would call it READY.
        if (isUploading) {
            binding.progress.visibility = View.VISIBLE
            binding.progress.setProgress(progress ?: 0)
        } else when (AttachmentDownloadDecision.downloadAction(isFileValid, progress)) {
            AttachmentDownloadAction.AUTO_DOWNLOAD -> downloadAttachment(message, attachmentPath)

            AttachmentDownloadAction.SHOW_PROGRESS -> {
                binding.progress.visibility = View.VISIBLE
                binding.progress.setProgress(progress ?: 0)
            }

            AttachmentDownloadAction.READY -> Unit
        }

    }

    /**
     * The status takes the size line's slot. The size stays INVISIBLE rather than GONE so the row
     * keeps its height and the bubble does not resize between states.
     */
    private fun showStatusInsteadOfSize(status: String) {
        binding.attachmentSize.visibility = View.INVISIBLE
        binding.tvDownloadHint.visibility = View.VISIBLE
        binding.tvDownloadHint.text = status
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (progressJob == null) {
            findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                FileUtil.progressUpdate
                    .filter { it == currentAttachmentId }
                    .collect {
                        withContext(Dispatchers.Main) {
                            currentMessage?.let {
                                setupAttachmentView(it)
                                // Long-text: hide self once download is complete — body text is shown by the parent binder.
                                // Mirror AttachContentBinder.longTextReady exactly (same file-decides gate), which is
                                // what lets forwarded long text hide the card AND expose full Read-more instead of
                                // the 2KB preview.
                                val attachment = it.attachment
                                if (attachment?.isLongText() == true) {
                                    val path = AttachmentPathResolver.fileFor(attachment)
                                    if (EncryptedAttachmentAccess.isLongTextReady(path, attachment.size)) {
                                        visibility = GONE
                                    }
                                }
                            }
                        }
                    }
            }?.also { progressJob = it }
        }
    }

    override fun onDetachedFromWindow() {
        progressJob?.cancel()
        progressJob = null
        super.onDetachedFromWindow()
    }

    private fun downloadAttachment(message: TextChatMessage, attachmentPath: String) {
        val attachment = message.attachment ?: return
        val key = attachment.key ?: return
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                attachment.localId,
                message.id,
                attachment.id,
                attachmentPath,
                attachment.authorityId,
                key,
                false // Attachment files should never auto-save to photos
            )
        )
    }

    fun openFile() {
        binding.open.performClick()
    }
}

