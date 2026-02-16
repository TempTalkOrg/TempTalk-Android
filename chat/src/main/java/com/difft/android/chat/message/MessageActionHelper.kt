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
import org.thoughtcrime.securesms.util.ClearClipboardAlarmReceiver
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.Util
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

    /**
     * Copy message content to clipboard
     * Handles text, long text attachments, and file attachments
     */
    fun copyMessageContent(data: TextChatMessage) {
        // Check if it's a long text attachment - copy full text content from file
        if (data.isLongTextAttachment()) {
            copyLongTextContent(data)
            return
        }

        // Check if it's a file attachment that can be copied as file URI
        if (data.canDownloadFile()) {
            copyFileToClipboard(data)
            return
        }

        // Copy text content using extension function
        data.getCopyableTextContent()?.let { Util.copyToClipboard(activity, it) }
    }

    /**
     * Copy long text content from file
     */
    private fun copyLongTextContent(data: TextChatMessage) {
        val fileInfo = data.getLongTextFileInfo()
        if (fileInfo == null) {
            // Fallback to copying message content
            data.message?.let { Util.copyToClipboard(activity, it) }
            return
        }

        val filePath = fileInfo.filePath

        // Read file content asynchronously and copy to clipboard
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    File(filePath).takeIf { it.exists() }?.readText() ?: ""
                } catch (e: Exception) {
                    L.e { "Failed to read long text file: ${e.message}" }
                    ""
                }
            }

            withContext(Dispatchers.Main) {
                if (content.isNotEmpty()) {
                    Util.copyToClipboard(activity, content)
                } else {
                    // Fallback to message content if file read fails
                    data.message?.let { Util.copyToClipboard(activity, it) }
                }
            }
        }
    }

    /**
     * Copy file to clipboard as URI
     */
    private fun copyFileToClipboard(data: TextChatMessage) {
        val fileInfo = data.getFileInfoForCopy() ?: return
        val file = File(fileInfo.filePath)

        if (file.exists()) {
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
        }
    }

    /**
     * Forward message to other chats
     * Requires selectChatsUtils to be provided in constructor
     */
    fun forwardMessage(data: TextChatMessage) {
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
            listOf(forwardContext)
        )
    }
}