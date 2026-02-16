package com.difft.android.chat.ui

import android.content.Context
import android.graphics.Point
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.databinding.ChatFragmentForwardMessageBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.MessageActionHelper
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.ui.messageaction.MessageAction
import com.difft.android.chat.ui.messageaction.MessageActionCoordinator
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.engine.ExoVideoPlayerEngine
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.Attachment
import javax.inject.Inject
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.models.ContactorModel
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.StorageUtil
import util.TimeFormatter
import util.concurrent.TTExecutors
import java.io.File

@AndroidEntryPoint
class ChatForwardMessageFragment : Fragment() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils
    
    @Inject
    lateinit var globalConfigsManager: IGlobalConfigsManager

    private val messageActionHelper by lazy {
        MessageActionHelper(requireActivity(), viewLifecycleOwner.lifecycleScope, selectChatsUtils)
    }

    companion object {
        const val ARG_TITLE = "arg_title"
        private const val ARG_STACK_INDEX = "arg_stack_index"

        fun newInstance(title: String, stackIndex: Int): ChatForwardMessageFragment {
            return ChatForwardMessageFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putInt(ARG_STACK_INDEX, stackIndex)
                }
            }
        }
    }

    private var _binding: ChatFragmentForwardMessageBinding? = null
    private val mBinding get() = _binding!!

    private val title: String by lazy { arguments?.getString(ARG_TITLE) ?: "" }
    private val stackIndex: Int by lazy { arguments?.getInt(ARG_STACK_INDEX, 0) ?: 0 }

    /**
     * Page-level contact cache
     * Released automatically when Fragment lifecycle ends
     */
    private val contactorCache = MessageContactsCacheUtil()
    
    private var messageActionCoordinator: MessageActionCoordinator? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChatFragmentForwardMessageBinding.inflate(inflater, container, false)
        return mBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set chat background for content area
        mBinding.root.background = ChatBackgroundDrawable(requireContext(), mBinding.root, false)

        // Apply navigation bar inset as bottom margin to root layout
        // This ensures both RecyclerView and reaction overlay have correct height
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { v, insets ->
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val layoutParams = v.layoutParams as? ViewGroup.MarginLayoutParams
            layoutParams?.bottomMargin = navigationBarHeight
            v.layoutParams = layoutParams
            insets
        }

        initView()
    }

    override fun onDestroyView() {
        // Clear adapter to prevent memory leak
        mBinding.recyclerViewMessage.adapter = null
        // Dismiss any showing popup
        messageActionCoordinator?.dismiss()
        messageActionCoordinator = null

        super.onDestroyView()
        _binding = null
    }

    private fun handleActionSelected(action: MessageAction.Type, message: TextChatMessage) {
        when (action) {
            MessageAction.Type.SAVE -> {
                if (StorageUtil.canWriteToMediaStore()) {
                    saveAttachment(message)
                } else {
                    pendingSaveAttachmentMessage = message
                    mediaPermission.launchMultiplePermission(PermissionUtil.picturePermissions)
                }
            }
            MessageAction.Type.COPY -> {
                messageActionHelper.copyMessageContent(message)
            }
            MessageAction.Type.FORWARD -> {
                messageActionHelper.forwardMessage(message)
            }
            else -> {}
        }
    }

    /**
     * Check if the message should show the reaction menu
     */
    private fun shouldShowMenu(data: TextChatMessage): Boolean {
        if (data.isAttachmentMessage()) return true
        if (!data.message.isNullOrEmpty()) return true
        if (!data.card?.content.isNullOrEmpty()) return true

        val forwards = data.forwardContext?.forwards
        if (!forwards.isNullOrEmpty()) {
            if (forwards.size == 1) {
                val forward = forwards.firstOrNull()
                if (forward?.attachments?.isNotEmpty() == true) return true
                if (!forward?.text.isNullOrEmpty()) return true
                if (!forward?.card?.content.isNullOrEmpty()) return true
            }
            return true
        }

        return false
    }

    private var pendingSaveAttachmentMessage: TextChatMessage? = null

    private val mediaPermission = registerPermission {
        onMediaPermissionResult(it)
    }

    private fun onMediaPermissionResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onMediaPermissionForMessageResult: Denied" }
                ToastUtil.show(getString(R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onMediaPermissionForMessageResult: Granted" }
                pendingSaveAttachmentMessage?.let {
                    saveAttachment(it)
                }
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onMediaPermissionForMessageResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = requireContext(),
                    title = getString(R.string.tip),
                    message = getString(R.string.no_permission_picture_tip),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    onConfirm = {
                        PermissionUtil.launchSettings(requireContext())
                    },
                    onCancel = {
                        ToastUtil.show(getString(R.string.not_granted_necessary_permissions))
                    }
                )
            }
        }
        pendingSaveAttachmentMessage = null
    }

    private fun saveAttachment(data: TextChatMessage) {
        val attachment = when {
            data.isAttachmentMessage() -> data.attachment
            data.forwardContext?.forwards?.size == 1 -> data.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
            else -> null
        }

        attachment?.let {
            val attachmentPath = FileUtil.getMessageAttachmentFilePath(data.id) + it.fileName
            val progress = data.getAttachmentProgress()

            if (File(attachmentPath).exists() && (progress == null || progress == 100)) {
                val saveAttachment = SaveAttachmentTask.Attachment(
                    File(attachmentPath).toUri(),
                    it.contentType,
                    System.currentTimeMillis(),
                    it.fileName,
                    false,
                    true
                )

                SaveAttachmentTask(requireContext()).executeOnExecutor(TTExecutors.BOUNDED, saveAttachment)
            } else {
                L.i { "save attachment error,exists:" + File(attachmentPath).exists() + " download completed:" + (progress == null || progress == 100) }
                ToastUtil.show(resources.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
            }
        }
    }

    /**
     * Check if attachment needs manual download
     */
    private fun shouldTriggerManualDownload(
        attachment: Attachment,
        progress: Int?,
        messageId: String
    ): Boolean {
        val isFailedOrExpired = if (progress != null) {
            progress == -1 || progress == -2
        } else {
            attachment.status == AttachmentStatus.FAILED.code || attachment.status == AttachmentStatus.EXPIRED.code
        }
        if (isFailedOrExpired) return true

        val fileSize = attachment.size
        val isLargeFile = fileSize > FileUtil.LARGE_FILE_THRESHOLD
        val fileName = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
        val isFileValid = FileUtil.isFileValid(attachmentPath)

        return isLargeFile && (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) && progress == null
    }

    private fun downloadAttachment(messageId: String, attachment: Attachment) {
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName
        // Auto-save only for non-confidential images/videos when conversation setting allows
        val forwardActivity = activity as? ChatForwardMessageActivity
        val shouldSaveToPhotos = forwardActivity?.getShouldSaveToPhotos() == true
        val isConfidential = forwardActivity?.isConfidentialMessage() == true
        val autoSave = shouldSaveToPhotos && !isConfidential && (attachment.isImage() || attachment.isVideo())
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                messageId,
                attachment.id,
                filePath,
                attachment.authorityId,
                attachment.key ?: byteArrayOf(),
                !attachment.isAudioMessage(),
                autoSave
            )
        )
    }

    private val chatMessageAdapter = object : ChatMessageAdapter(forWhat = null, contactorCache = contactorCache) {

        override fun onItemClick(rootView: View, data: ChatMessage) {
            if (data is TextChatMessage) {
                if (data.isAttachmentMessage()) {
                    val attachment = data.attachment ?: return
                    val progress = data.getAttachmentProgress()

                    if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                        downloadAttachment(data.id, attachment)
                        return
                    }

                    if (data.attachment?.isImage() == true || data.attachment?.isVideo() == true) {
                        openPreview(data)
                    }
                } else if (data.forwardContext != null && !data.forwardContext?.forwards.isNullOrEmpty()) {
                    val forwardContext = data.forwardContext ?: return
                    if (forwardContext.forwards?.size == 1) {
                        val forward = forwardContext.forwards?.getOrNull(0) ?: return
                        val attachment = forward.attachments?.getOrNull(0) ?: return
                        val progress = data.getAttachmentProgress()

                        // data.id is already set to authorityId.toString() by generateMessageFromForward
                        if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                            downloadAttachment(data.id, attachment)
                            return
                        }

                        if (attachment.isImage() || attachment.isVideo()) {
                            openPreview(generateMessageFromForward(forward) as TextChatMessage)
                        }
                    } else {
                        // Navigate to nested forward - use Fragment navigation
                        // Generate dynamic title matching the message bubble display
                        val nestedTitle = getForwardTitle(forwardContext)
                        (activity as? ChatForwardMessageActivity)?.navigateToNestedForward(
                            nestedTitle,
                            forwardContext
                        )
                    }
                }
            }
        }

        override fun onItemLongClick(rootView: View, data: ChatMessage) {
            if (data !is TextChatMessage) return
            if (!shouldShowMenu(data)) return

            // Get bubble view (ChatMessageContainerView with id contentContainer)
            val bodyBubble = rootView.findViewById<View>(R.id.contentContainer) ?: return
            
            // Get text view for text selection - check if message has text content
            val textView: TextView? = if (hasTextContent(data)) {
                rootView.findViewById<View>(R.id.contentFrame)?.findViewById(R.id.textView)
            } else {
                null
            }
            
            // Initialize coordinator if needed
            if (messageActionCoordinator == null) {
                messageActionCoordinator = MessageActionCoordinator(requireActivity(), globalConfigsManager).apply {
                    setActionListener(object : MessageActionCoordinator.ActionListener {
                        override fun onReactionSelected(message: TextChatMessage, emoji: String, isRemove: Boolean) {}
                        override fun onMoreEmojiClick(message: TextChatMessage) {}
                        override fun onQuote(message: TextChatMessage) {}
                        override fun onCopy(message: TextChatMessage, selectedText: String?) {
                            if (selectedText != null) {
                                // Partial selection - copy selected text only
                                org.thoughtcrime.securesms.util.Util.copyToClipboard(requireContext(), selectedText)
                                ToastUtil.show(getString(R.string.chat_message_action_copied))
                            } else {
                                // Full selection - copy entire message
                                handleActionSelected(MessageAction.Type.COPY, message)
                            }
                        }
                        override fun onTranslate(message: TextChatMessage, selectedText: String?) {}
                        override fun onTranslateOff(message: TextChatMessage) {}
                        override fun onForward(message: TextChatMessage, selectedText: String?) {
                            if (selectedText != null) {
                                // Partial selection - forward as plain text
                                selectChatsUtils.showChatSelectAndSendDialog(requireActivity(), selectedText)
                            } else {
                                // Full selection - forward as original message
                                handleActionSelected(MessageAction.Type.FORWARD, message)
                            }
                        }
                        override fun onSpeechToText(message: TextChatMessage) {}
                        override fun onSpeechToTextOff(message: TextChatMessage) {}
                        override fun onSave(message: TextChatMessage) {
                            handleActionSelected(MessageAction.Type.SAVE, message)
                        }
                        override fun onMultiSelect(message: TextChatMessage) {}
                        override fun onSaveToNote(message: TextChatMessage) {}
                        override fun onDeleteSaved(message: TextChatMessage) {}
                        override fun onRecall(message: TextChatMessage) {}
                        override fun onMoreInfo(message: TextChatMessage) {}
                        override fun onDismiss() {}
                    })
                }
            }
            
            messageActionCoordinator?.show(
                message = data,
                messageView = bodyBubble,
                textView = textView,
                mostUseEmojis = null,  // No emoji reactions in forward mode
                isForForward = true,
                isSaved = false,
                touchPoint = Point(bodyBubble.width / 2, 0),
                containerView = mBinding.recyclerViewMessage,
                enableTextSelection = textView != null  // Enable text selection only when there's a text view
            )
        }
        
        /**
         * Check if message has text content that can be selected
         */
        private fun hasTextContent(data: TextChatMessage): Boolean {
            // Direct text message
            if (!data.message.isNullOrEmpty()) return true
            
            // Single forward with text
            val forwards = data.forwardContext?.forwards
            if (forwards?.size == 1) {
                val forward = forwards.firstOrNull()
                if (!forward?.text.isNullOrEmpty()) return true
            }
            
            return false
        }

        override fun onAvatarClicked(contactor: ContactorModel?) {}
        override fun onAvatarLongClicked(contactor: ContactorModel?) {}
        override fun onQuoteClicked(quote: Quote) {}
        override fun onReactionClick(message: ChatMessage, emoji: String, remove: Boolean, originTimeStamp: Long) {}
        override fun onReactionLongClick(message: ChatMessage, emoji: String) {}
    }

    private fun initView() {
        mBinding.recyclerViewMessage.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            adapter = chatMessageAdapter
        }

        // Set shouldSaveToPhotos from Activity (original conversation setting)
        // Note: This is combined with isConfidentialMessage check in downloadAttachment
        val forwardActivity = activity as? ChatForwardMessageActivity
        val shouldSaveToPhotos = forwardActivity?.getShouldSaveToPhotos() == true
        val isConfidential = forwardActivity?.isConfidentialMessage() == true
        chatMessageAdapter.shouldSaveToPhotos = shouldSaveToPhotos && !isConfidential

        // Get ForwardContext from Activity using stackIndex (avoids JSON serialization on main thread)
        val forwardContext = forwardActivity?.getForwardContext(stackIndex)
        if (forwardContext != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                val list = mutableListOf<ChatMessage>()
                forwardContext.forwards?.forEach { forward ->
                    list.add(generateMessageFromForward(forward))
                }

                val contactIds = MessageContactsCacheUtil.collectContactIds(list)
                contactorCache.loadContactors(contactIds)

                val newList = list.sortedBy { message -> message.systemShowTimestamp }
                    .mapIndexed { index, message ->
                        val previousMessage = if (index > 0) list[index - 1] else null
                        val isSameDayWithPreviousMessage = TimeFormatter.isSameDay(message.timeStamp, previousMessage?.timeStamp ?: 0L)

                        val nextMessage = if (index < list.size - 1) list[index + 1] else null
                        val isSameDayWithNextMessage = TimeFormatter.isSameDay(message.timeStamp, nextMessage?.timeStamp ?: 0L)

                        message.showName = !isSameDayWithPreviousMessage || message.authorId != previousMessage?.authorId
                        message.showDayTime = !isSameDayWithPreviousMessage
                        message.showTime = !isSameDayWithNextMessage || message.authorId != nextMessage?.authorId
                        message
                    }

                chatMessageAdapter.submitList(newList)
            }
        }
    }

    /**
     * Generate forward title matching the message bubble display
     * Same logic as ContentBinders.kt line 558-567
     */
    private fun getForwardTitle(forwardContext: ForwardContext): String {
        val forwards = forwardContext.forwards
        return if (forwards?.firstOrNull()?.isFromGroup == true) {
            getString(R.string.group_chat_history)
        } else {
            val authorId = forwards?.firstOrNull()?.author ?: ""
            val author = contactorCache.getContactor(authorId)
            if (author != null) {
                getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
            } else {
                getString(R.string.chat_history_for, authorId.formatBase58Id())
            }
        }
    }

    private fun openPreview(message: TextChatMessage) {
        val filePath = FileUtil.getMessageAttachmentFilePath(message.id) + message.attachment?.fileName
        if (!FileUtil.isFileValid(filePath)) {
            ToastUtil.showLong(R.string.file_load_error)
            return
        }
        val list = arrayListOf<LocalMedia>().apply {
            this.add(LocalMedia.generateLocalMedia(requireContext(), filePath))
        }
        PictureSelector.create(requireActivity())
            .openPreview()
            .isHidePreviewDownload(false)
            .isAutoVideoPlay(true)
            .isVideoPauseResumePlay(true)
            .setVideoPlayerEngine(ExoVideoPlayerEngine())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
            .setImageEngine(GlideEngine.createGlideEngine())
            .setExternalPreviewEventListener(object : OnExternalPreviewEventListener {
                override fun onPreviewDelete(position: Int) {}
                override fun onLongPressDownload(context: Context?, media: LocalMedia?): Boolean {
                    return false
                }
            }).startActivityPreview(0, false, list)
    }
}