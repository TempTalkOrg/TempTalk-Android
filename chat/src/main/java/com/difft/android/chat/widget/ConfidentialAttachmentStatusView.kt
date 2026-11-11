package com.difft.android.chat.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.dp
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.getAttachmentIdForProgress
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isConfidential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext















class ConfidentialAttachmentStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var message: TextChatMessage? = null
    private var stacked: Boolean = false
    private var progressKey: String? = null
    private var progressJob: Job? = null

    



    fun bind(message: TextChatMessage, stacked: Boolean) {
        this.message = message
        this.stacked = stacked
        progressKey = message.getAttachmentIdForProgress()
        render()
    }

    



    fun hide() {
        message = null
        progressKey = null
        visibility = View.GONE
    }

    private fun render() {
        val message = message
        val attachment = message?.attachment
        if (message == null || attachment == null || !message.isConfidential()) {
            visibility = View.GONE
            return
        }
        
        
        
        
        val isFileValid = EncryptedAttachmentAccess.isReadable(AttachmentPathResolver.fileFor(attachment), attachment.size)
        if (!AttachmentDownloadDecision.needsFetch(isFileValid)) {
            visibility = View.GONE
            return
        }
        val progress = message.getAttachmentProgress()
        val expired = AttachmentDownloadDecision.isExpired(attachment.status, progress)
        val failed = !expired && AttachmentDownloadDecision.isFailed(attachment.status, progress)
        if (!expired && !failed) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        findViewById<View>(R.id.confidential_status_retry).visibility = if (failed) View.VISIBLE else View.GONE
        val expiredGroup = findViewById<LinearLayout>(R.id.confidential_status_expired)
        expiredGroup.visibility = if (expired) View.VISIBLE else View.GONE
        if (!expired) return

        expiredGroup.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val iconDp = if (stacked) ICON_MEDIA_DP else ICON_INLINE_DP
        findViewById<View>(R.id.confidential_status_icon).updateLayoutParams<LinearLayout.LayoutParams> {
            width = iconDp.dp
            height = iconDp.dp
        }
        findViewById<View>(R.id.confidential_status_text).updateLayoutParams<LinearLayout.LayoutParams> {
            topMargin = if (stacked) TEXT_GAP_STACKED_DP.dp else 0
            marginStart = if (stacked) 0 else TEXT_GAP_INLINE_DP.dp
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (progressJob == null) {
            progressJob = findViewTreeLifecycleOwner()?.lifecycleScope?.launch {
                FileUtil.progressUpdate
                    .filter { it == progressKey }
                    .collect { withContext(Dispatchers.Main) { render() } }
            }
        }
    }

    override fun onDetachedFromWindow() {
        progressJob?.cancel()
        progressJob = null
        super.onDetachedFromWindow()
    }

    private companion object {
        
        const val ICON_MEDIA_DP = 24

        
        const val ICON_INLINE_DP = 16
        const val TEXT_GAP_STACKED_DP = 8
        const val TEXT_GAP_INLINE_DP = 6
    }
}
