package com.difft.android.chat.widget

import com.difft.android.chat.ui.BubblePalette
import androidx.annotation.DrawableRes
import android.content.res.ColorStateList
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
import com.difft.android.chat.attachment.isInConfidentialViewer
import com.difft.android.chat.attachment.mayOpenExternally
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.attachment.videoprobe.VideoProbeCoordinator
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


private val unreadableLogged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

class AttachMessageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs) {

    val binding: LayoutAttachMessageViewBinding by viewbind(this)

    private var progressJob: Job? = null
    private var currentAttachmentId: String? = null
    private var currentMessage: TextChatMessage? = null

    fun setupAttachmentView(message: TextChatMessage) {
        
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
        binding.logo.setImageResource(fileTypeIcon(attachment.fileName))

        val progress = message.getAttachmentProgress()
        
        
        
        
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath, attachment.size)
        if (!isFileValid) {
            
            
            
            
            if (unreadableLogged.add("${message.id}:${attachment.status}")) L.w {
                "[Attachment] file not readable messageId=${message.id} localId=${attachment.localId} " +
                    "fileNameLen=${attachment.fileName?.length} fileNameHash=${attachment.fileName?.hashCode()} " +
                    "status=${attachment.status} size=${attachment.size} mine=${message.isMine} " +
                    "forward=${message.forwardContext != null} quote=${message.quote != null} " +
                    "dirExists=${java.io.File(attachmentPath).parentFile?.exists()}"
            }
        }
        
        
        val isUploading = AttachmentDownloadDecision.isUploadInFlight(isFileValid, progress)
        
        
        if (isFileValid && !isUploading) AttachmentStatusRepair.markSuccessIfStale(attachment)
        
        
        if (VideoProbeCoordinator.needsProbe(attachment, isFileValid)) {
            VideoProbeCoordinator.from(context).request(attachment, attachmentPath)
        }

        if (AttachmentDownloadDecision.needsFetch(isFileValid)) {
            
            
            if (AttachmentDownloadDecision.isExpired(attachment.status, progress)) {
                showStatusInsteadOfSize(context.getString(R.string.file_expired))
                return
            }

            
            if (AttachmentDownloadDecision.isFailed(attachment.status, progress)) {
                showStatusInsteadOfSize(context.getString(R.string.download_failed))
                return
            }

            
            val fileSize = attachment.size
            if (AttachmentDownloadDecision.shouldPromptManualDownload(isFileValid, fileSize, progress)) {
                val fileSizeText = FileUtil.readableFileSize(fileSize.toLong())
                showStatusInsteadOfSize(context.getString(R.string.chat_tap_to_download) + " ($fileSizeText)")
                return
            }
        }

        
        
        if (isFileValid && !isUploading) {
            binding.open.visibility = View.VISIBLE
            binding.open.setOnClickListener {
                val isConfidential = message.isConfidential() || context.isInConfidentialViewer()
                if (attachment.mayOpenExternally(isConfidential)) {
                    context.viewFile(attachmentPath)
                } else {
                    
                    LinkTextUtils.findParentChatMessageItemView(this)?.performClick()
                }
            }
        }

        
        
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

    
    internal fun applyPalette(palette: BubblePalette) {
        binding.logo.backgroundTintList = ColorStateList.valueOf(palette.blockFill)
        binding.logo.imageTintList = ColorStateList.valueOf(palette.icon)
        binding.open.imageTintList = ColorStateList.valueOf(palette.icon)
        binding.attachmentName.setTextColor(palette.text)
        binding.attachmentSize.setTextColor(palette.meta)
        binding.tvDownloadHint.setTextColor(palette.meta)
        binding.progress.setProgressBarColor(palette.text)
        binding.progress.setCircleBackgroundColor(palette.barUnplayed)
    }

    



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
        val key = attachment.key?.takeIf { it.isNotEmpty() } ?: return
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                attachment.localId,
                message.id,
                attachment.id,
                attachmentPath,
                attachment.authorityId,
                key,
                false 
            )
        )
    }

    fun openFile() {
        binding.open.performClick()
    }
}





@DrawableRes
internal fun fileTypeIcon(fileName: String?): Int = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
    "doc", "docx", "pages" -> R.drawable.chat_ic_file_type_doc
    "xls", "xlsx", "numbers" -> R.drawable.chat_ic_file_type_xls
    "ppt", "pptx", "pps", "ppsx", "key" -> R.drawable.chat_ic_file_type_ppt
    "pdf" -> R.drawable.chat_ic_file_type_pdf
    "zip", "7z", "rar", "bz", "tar", "gz", "gzip", "bzip2" -> R.drawable.chat_ic_file_type_zip
    else -> R.drawable.chat_ic_file_type_file
}
