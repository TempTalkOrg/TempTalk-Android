package com.difft.android.chat.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
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
import difft.android.messageserialization.model.AttachmentStatus
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

        binding.attachmentName.text = attachment.fileName
        binding.attachmentSize.text = FileUtil.readableFileSize(attachment.size.toLong())

        val progress = message.getAttachmentProgress()
        // Encrypted-at-rest types (e.g. long text) keep only "<path>.encrypt" on disk, so a
        // plaintext-only isFileValid() would report "not downloaded" and trigger an endless
        // re-download loop (each re-download resets status to LOADING), which in turn keeps the
        // long-text Read-more gate closed → only the 2KB preview shows. Gate on isReadable instead.
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath, attachment.size)
        val isCurrentDeviceSend = message.isMine && message.id.last().digitToIntOrNull() == DEFAULT_DEVICE_ID
        // An outgoing upload stages its file at this exact address before it starts, so file presence
        // says nothing about the transfer while it runs.
        val isUploading = AttachmentDownloadDecision.isUploadInFlight(isCurrentDeviceSend, progress)
        // A readable file is the authority on "downloaded"; repair a lagging status before the
        // branches below read it, so no branch can order a download for a file we have.
        if (isFileValid && !isUploading) AttachmentStatusRepair.markSuccessIfStale(attachment)

        if (!isCurrentDeviceSend) {
            // Priority 1: Show expired view if file has expired
            val isExpired = if (progress != null) {
                progress == -2
            } else {
                attachment.status == AttachmentStatus.EXPIRED.code
            }

            if (isExpired) {
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.file_expired)
                return
            }

            // Priority 2: Show fail view if download failed
            val isFailed = if (progress != null) {
                progress == -1
            } else {
                attachment.status == AttachmentStatus.FAILED.code
            }

            if (isFailed) {
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.download_failed)
                return
            }

            // Priority 2: Show download prompt for files > 10M
            val fileSize = attachment.size
            if (AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid, fileSize, progress)) {
                // Show download prompt (reuse fail view with different text)
                binding.tvDownloadHint.visibility = View.VISIBLE
                binding.tvDownloadHint.text = context.getString(R.string.chat_tap_to_download)
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
            AttachmentDownloadAction.AUTO_DOWNLOAD -> if (
                AttachmentDownloadDecision.shouldAutoDownload(isCurrentDeviceSend, isFileValid, attachment.status, progress)
            ) {
                downloadAttachment(message, attachmentPath)
            }

            AttachmentDownloadAction.SHOW_PROGRESS -> {
                binding.progress.visibility = View.VISIBLE
                binding.progress.setProgress(progress ?: 0)
            }

            AttachmentDownloadAction.READY -> Unit
        }

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
                                // Must match AttachContentBinder's isLongTextDownloaded check: isFileValid alone is not
                                // sufficient (partial files pass), so also require status=SUCCESS or progress=100.
                                val attachment = it.attachment
                                if (attachment?.isLongText() == true) {
                                    val path = AttachmentPathResolver.fileFor(attachment)
                                    // Mirror AttachContentBinder.isLongTextDownloaded exactly: a valid
                                    // ".encrypt" alone means done (see isLongTextReady), while the
                                    // status/progress signal only gates legacy plaintext. Keeping these two
                                    // in lockstep is what lets forwarded long text hide the card AND expose
                                    // full Read-more instead of the 2KB preview.
                                    val progress = it.getAttachmentProgress()
                                    val plaintextStatusReady = attachment.status == AttachmentStatus.SUCCESS.code || progress == 100
                                    val isComplete = EncryptedAttachmentAccess.isLongTextReady(path, plaintextStatusReady)
                                    if (isComplete) {
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

