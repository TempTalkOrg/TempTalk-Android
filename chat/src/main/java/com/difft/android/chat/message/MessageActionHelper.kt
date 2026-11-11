package com.difft.android.chat.message

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.media.EncryptedAttachmentAccess
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









class MessageActionHelper(
    private val activity: Activity,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val selectChatsUtils: SelectChatsUtils? = null
) {

    
    suspend fun copyMessageContent(data: TextChatMessage): Boolean {
        if (data.isLongTextAttachment()) return copyLongTextContent(data)
        if (data.canDownloadFile()) return copyFileToClipboard(data)
        
        val text = data.getCopyableTextContent()?.normalizeNewlines() ?: return false
        Util.copyToClipboard(activity, text)
        return true
    }

    
    private suspend fun copyLongTextContent(data: TextChatMessage): Boolean {
        val content = withContext(Dispatchers.IO) {
            
            
            
            val fileInfo = data.getLongTextFileInfo() ?: return@withContext ""
            try {
                
                EncryptedAttachmentAccess.readDecryptedText(activity, fileInfo.filePath) ?: ""
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

    
    private suspend fun copyFileToClipboard(data: TextChatMessage): Boolean {
        
        val fileInfo = withContext(Dispatchers.IO) {
            data.getFileInfoForCopy()?.takeIf { EncryptedAttachmentAccess.isReadable(it.filePath) }
        } ?: return false
        val basePath = fileInfo.filePath

        
        
        
        val uri: Uri = EncryptedAttachmentAccess.exportContentUriIfEncrypted(fileInfo.messageId, basePath)
            ?: FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.provider",
                File(basePath)
            )
        
        val clipboard = ServiceUtil.getClipboardManager(activity)
        val clipData = ClipData.newUri(
            activity.contentResolver,
            ClearClipboardAlarmReceiver.CLIPBOARD_LABEL,
            uri
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clipData.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clipData)
        ToastUtil.show(activity.getString(R.string.chat_message_action_copied))
        
        Util.scheduleClipboardClear(activity, 5 * 60)
        return true
    }

    










    fun forwardMessage(
        data: TextChatMessage,
        sourceConversation: For? = null,
        

        sourceAuthorIdsOverride: List<String>? = null,
        
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
        
        carriesForeignContent: Boolean = true,
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
            carriesForeignContent = carriesForeignContent,
        )
    }
}