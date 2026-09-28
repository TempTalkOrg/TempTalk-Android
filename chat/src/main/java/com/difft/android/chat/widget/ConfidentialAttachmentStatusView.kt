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

/**
 * Status layer of a CONFIDENTIAL attachment bubble (`layout_confidential_attachment_status`).
 *
 * The blur under the cover keeps the content hidden, but it also hides the widget's own expired /
 * failed indicator. This layer repeats that status ABOVE the cover, reversed out on one dark scrim,
 * so the user learns the file is gone without anything of the content showing. Shown only while
 * no readable file is here and the attachment is expired or failed — the same file-decides rule
 * the bubble underneath applies, whoever sent the message; hidden otherwise and for
 * non-confidential messages, where the widget's indicator is visible on its own.
 *
 * Like the bubble widgets it sits over, it listens to the download progress stream for its own
 * progress key, so a failure that lands after bind surfaces here at the same moment the widget
 * underneath switches to its retry state — not only on the next list rebind.
 */
class ConfidentialAttachmentStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private var message: TextChatMessage? = null
    private var stacked: Boolean = false
    private var progressKey: String? = null
    private var progressJob: Job? = null

    /**
     * @param stacked media bubbles stack a 24dp glyph over the copy; voice / file put a 16dp glyph
     *   inline with it, sized to the 12sp text.
     */
    fun bind(message: TextChatMessage, stacked: Boolean) {
        this.message = message
        this.stacked = stacked
        progressKey = message.getAttachmentIdForProgress()
        render()
    }

    /**
     * Hide the layer and forget the bound message. The progress collector stays with the view's
     * attach lifecycle (like the bubble widgets); with no progress key it simply matches nothing.
     */
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
        // Same authority as the bubble underneath: the file decides, not the message's direction. A
        // readable file means the download-side states do not apply — a FAILED row with its staged
        // file present is a failed upload, reported by the message send state — while an own message
        // synced from another device with no file here is a copy to fetch like anyone else's.
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
        /** Expired glyph on media: 24dp stacked over the copy. */
        const val ICON_MEDIA_DP = 24

        /** Expired glyph on voice / file: 16dp inline with the copy. */
        const val ICON_INLINE_DP = 16
        const val TEXT_GAP_STACKED_DP = 8
        const val TEXT_GAP_INLINE_DP = 6
    }
}
