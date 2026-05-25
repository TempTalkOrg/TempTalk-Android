package com.difft.android.chat.message

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.ui.SelectChatsUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.util.ClearClipboardAlarmReceiver
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.chat.util.Util
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardNoticeData
import java.io.File

/**
 * Helper class for message copy and forward operations
 * Encapsulates common logic used by ChatMessageListFragment and ChatForwardMessageActivity
 *
 * @param activity Android activity (needed for forward dialog)
 * @param lifecycleScope Lifecycle coroutine scope for async operations
 * @param selectChatsUtils Optional SelectChatsUtils for forward operations (only needed for forward)
 */
class MessageActionHelper(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val selectChatsUtils: SelectChatsUtils? = null
) {

    /** Returns true iff the clipboard write succeeded; PRD §4.1 gates the trace notice on this. */
    suspend fun copyMessageContent(data: TextChatMessage): Boolean {
        if (data.isLongTextAttachment()) return copyLongTextContent(data)
        if (data.canDownloadFile()) return copyFileToClipboard(data)
        val text = data.getCopyableTextContent() ?: return false
        Util.copyToClipboard(activity, text)
        return true
    }

    /** Reads the backing file off-main-thread; falls back to data.message on read failure. */
    private suspend fun copyLongTextContent(data: TextChatMessage): Boolean {
        val fileInfo = data.getLongTextFileInfo()
        if (fileInfo == null) {
            val fallback = data.message ?: return false
            Util.copyToClipboard(activity, fallback)
            return true
        }

        val content = withContext(Dispatchers.IO) {
            try {
                File(fileInfo.filePath).takeIf { it.exists() }?.readText() ?: ""
            } catch (e: Exception) {
                L.e { "Failed to read long text file: ${e.message}" }
                ""
            }
        }

        if (content.isNotEmpty()) {
            Util.copyToClipboard(activity, content)
            return true
        }
        val fallback = data.message ?: return false
        Util.copyToClipboard(activity, fallback)
        return true
    }

    /** Returns false if the underlying file is missing on disk. */
    private fun copyFileToClipboard(data: TextChatMessage): Boolean {
        val fileInfo = data.getFileInfoForCopy() ?: return false
        val file = File(fileInfo.filePath)
        if (!file.exists()) return false

        // Use FileProvider to generate a secure URI
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.provider",
            file
        )
        // Copy file to clipboard with special label for identification
        val clipboard = ServiceUtil.getClipboardManager(activity)
        val clipData = ClipData.newUri(
            activity.contentResolver,
            ClearClipboardAlarmReceiver.CLIPBOARD_LABEL,
            uri
        )
        // Mark as sensitive to prevent clipboard preview and cross-device sync (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clipData.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clipData)
        ToastUtil.show(activity.getString(R.string.chat_message_action_copied))
        // Schedule clipboard clear after 5 minutes
        Util.scheduleClipboardClear(activity, 5 * 60)
        return true
    }

    /**
     * Forward message to other chats
     * Requires selectChatsUtils to be provided in constructor
     *
     * @param data The message to forward.
     * @param sourceConversation Source conversation of the forwarded message (optional). The
     *                            forwardNotice is posted here to tell original participants that
     *                            their messages were forwarded away. Callers that can't determine
     *                            the source conversation (e.g. ChatForwardMessageFragment) pass
     *                            null; the notice is skipped in that case.
     */
    fun forwardMessage(
        data: TextChatMessage,
        sourceConversation: For? = null,
        /** Override authors when the source attribution differs from data.authorId
         *  (e.g. forwarding from inside a combined-forward preview → outer sender). */
        sourceAuthorIdsOverride: List<String>? = null,
        /** PRD v1.0 §5.3 combined-forward mode. Default UNKNOWN; pass SUB_COMBINED_FORWARD when
         *  forwarding from inside a CF detail view (caller decides). */
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        val utils = selectChatsUtils ?: run {
            L.w { "forwardMessage called but selectChatsUtils is null" }
            return
        }

        val forwardData = data.buildForwardData() ?: return
        val (content, forwardContext) = forwardData

        utils.showChatSelectAndSendDialog(
            activity,
            content,
            null,
            null,
            listOf(forwardContext),
            scene = ForwardNoticeData.Scene.SINGLE,
            sourceConversation = sourceConversation,
            sourceAuthorIds = sourceAuthorIdsOverride ?: listOf(data.authorId),
            combinedForwardMode = combinedForwardMode,
        )
    }
}