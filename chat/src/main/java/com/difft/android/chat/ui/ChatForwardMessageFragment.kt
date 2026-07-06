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
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.databinding.ChatFragmentForwardMessageBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.MessageActionHelper
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.singleForwardableAttachment
import com.difft.android.chat.gif.favorite.collectFavoriteEffects
import com.difft.android.chat.gif.favorite.MAX_FAVORITE_ASSET_BYTES
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
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioMessage
import com.difft.android.chat.media.AttachmentPreview
import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.keepEncryptedAtRest
import difft.android.messageserialization.model.isVideo
import org.difft.app.database.models.ContactorModel
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.util.SaveAttachmentUtil
import util.TimeFormatter
import java.io.File

@AndroidEntryPoint
class ChatForwardMessageFragment : Fragment() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var globalConfigsManager: IGlobalConfigsManager

    @Inject
    lateinit var activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher

    private val messageActionHelper by lazy {
        MessageActionHelper(requireActivity(), viewLifecycleOwner.lifecycleScope, selectChatsUtils)
    }

    // Fragment-scoped: the forward screen has no GIF panel to share it with (unlike the chat).
    private val favoriteViewModel: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels()

    /** Outer combined-forward message context — source attribution for copy/forward notices. */
    private val outerSourceConversation: difft.android.messageserialization.For?
        get() = (activity as? ChatForwardMessageActivity)?.getOuterSourceConversation()
    private val outerSourceAuthorIds: List<String>?
        get() = (activity as? ChatForwardMessageActivity)?.getOuterSourceAuthorId()?.let { listOf(it) }

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

        // Favorites effects (cap dialog / toast) — no GIF panel here, so wire our own collection.
        collectFavoriteEffects(favoriteViewModel)
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
                if (FileUtil.canWriteToMediaStore()) {
                    saveAttachment(message)
                } else {
                    pendingSaveAttachmentMessage = message
                    mediaPermission.launchMultiplePermission(PermissionUtil.picturePermissions)
                }
            }
            MessageAction.Type.COPY -> {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (messageActionHelper.copyMessageContent(message)) {
                        dispatchOuterCopyNotice(message)
                    }
                }
            }
            MessageAction.Type.FORWARD -> {
                // Notice attribution: outer combined-forward sender, not nested authors.
                // PRD §5.3.2: operation inside a CF detail view → SUB_COMBINED_FORWARD.
                messageActionHelper.forwardMessage(
                    message,
                    sourceConversation = outerSourceConversation,
                    sourceAuthorIdsOverride = outerSourceAuthorIds,
                    combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
                    // PRD v2.0 §改动1/§改动2 条件①④: real author of the moved inner = message.authorId.
                    carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(listOf(message), globalServices.myId),
                )
            }
            else -> {}
        }
    }

    private fun dispatchOuterCopyNotice(message: TextChatMessage) {
        val conv = outerSourceConversation ?: return
        val authors = outerSourceAuthorIds ?: return
        // PRD v2.0 §改动1/§改动2 条件①④⑤: trace only when the copied sub-message carries another
        // person's real content. Its real author is the inner sub-author (message.authorId);
        // a nested-CF / contact-card sub-message copies as a placeholder and leaks nothing.
        val myId = globalServices.myId
        if (!NoticeAggregator.copyCarriesForeignContent(listOf(message), myId)) return
        // PRD §5.3.2: copy from inside a CF detail view → SUB_COMBINED_FORWARD.
        activityNoticeDispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = authors,
            myId = myId,
            messageCount = 1,
            combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
        )
    }

    /**
     * Check if the message should show the reaction menu
     */
    private fun shouldShowMenu(data: TextChatMessage): Boolean {
        if (data.isAttachmentMessage()) return true
        if (!data.message.isNullOrEmpty()) return true

        val forwards = data.forwardContext?.forwards
        if (!forwards.isNullOrEmpty()) {
            if (forwards.size == 1) {
                val forward = forwards.firstOrNull()
                if (forward?.attachments?.isNotEmpty() == true) return true
                if (!forward?.text.isNullOrEmpty()) return true
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

    private fun favoriteGif(message: TextChatMessage) {
        // Gif is encrypted at rest: gate on isReadable, then decrypt to a temp when only ciphertext
        // exists. Shares resolveActionAttachment with saveAttachment so the file path can't drift.
        val (attachment, messageId) = resolveActionAttachment(message) ?: return
        // Reject oversized gifs up front using the known attachment size — avoids decrypting a large
        // file just to reject it (the write path also enforces MAX_FAVORITE_ASSET_BYTES as a backstop).
        if (attachment.size > MAX_FAVORITE_ASSET_BYTES) {
            L.w { "[ChatForwardMessageFragment] favorite gif: too large size=${attachment.size} messageId=$messageId" }
            ToastUtil.show(getString(R.string.gif_favorites_add_size_limit))
            return
        }
        val fileName = attachment.fileName ?: return
        val basePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
        if (!EncryptedAttachmentAccess.isReadable(basePath)) {
            L.w { "[ChatForwardMessageFragment] favorite gif: not readable messageId=$messageId" }
            ToastUtil.show(getString(R.string.gif_favorites_failed))
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                com.difft.android.chat.gif.favorite.resolveMessageGifPlaintext(requireContext(), basePath, attachment.key)
            }
            if (resolved == null) {
                L.w { "[ChatForwardMessageFragment] favorite gif: decrypt failed messageId=${message.id}" }
                ToastUtil.show(getString(R.string.gif_favorites_failed))
                return@launch
            }
            val (file, isTemp) = resolved
            favoriteViewModel.dispatch(
                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.Favorite(
                    com.difft.android.chat.gif.favorite.FavoriteSource.FromMessageFile(
                        file, attachment.width, attachment.height, deleteAfterUse = isTemp
                    )
                )
            )
        }
    }

    private fun saveAttachment(data: TextChatMessage) {
        val (attachment, messageId) = resolveActionAttachment(data) ?: return
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName
        val progress = data.getAttachmentProgress()

        // Encrypted-at-rest media keeps only the ciphertext (.encrypt) on disk — the plaintext
        // file is gone, so File(...).exists() is false. Gate on isReadable and feed the save the
        // decrypting content uri so SaveAttachmentUtil can stream the plaintext on demand.
        if (EncryptedAttachmentAccess.isReadable(attachmentPath) && (progress == null || progress == 100)) {
            // Prefer the durable ciphertext (content uri) over the plaintext file — a self-sent
            // attachment's plaintext is deleted right after upload, so a plaintext uri resolved here
            // can ENOENT by the time this async save reads it. See EncryptedAttachmentAccess.exportContentUriIfEncrypted.
            val saveUri = EncryptedAttachmentAccess.exportContentUriIfEncrypted(messageId, attachmentPath)
                ?: File(attachmentPath).toUri()
            val attachmentToSave = SaveAttachmentUtil.Attachment(
                uri = saveUri,
                contentType = attachment.contentType,
                date = System.currentTimeMillis(),
                fileName = attachment.fileName
            )
            viewLifecycleOwner.lifecycleScope.launch {
                SaveAttachmentUtil.saveWithUI(requireContext(), attachmentToSave)
            }
        } else {
            L.w { "[ChatForwardMessageFragment] save attachment error, readable=" + EncryptedAttachmentAccess.isReadable(attachmentPath) + " downloadCompleted=" + (progress == null || progress == 100) }
            ToastUtil.show(resources.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
        }
    }

    /**
     * Resolve the actionable attachment + its on-disk storage messageId — the SINGLE source of truth
     * shared by save AND favorite in the forward detail so their file-path resolution can never
     * diverge. Here both a direct attachment and a single-forward are stored under this message's id.
     */
    private fun resolveActionAttachment(message: TextChatMessage): Pair<Attachment, String>? =
        message.singleForwardableAttachment()?.let { it to message.id }

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
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath)

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
                !attachment.keepEncryptedAtRest(),
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
            // PRD v2.0 §改动5: a confidential combined-forward may be expanded for viewing, but ALL
            // long-press operations (copy / forward / save / text-selection) are blocked — consistent
            // with the main conversation, where a confidential message gets no action menu at all.
            if ((activity as? ChatForwardMessageActivity)?.isConfidentialMessage() == true) return
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
                                _root_ide_package_.com.difft.android.chat.util.Util.copyToClipboard(requireContext(), selectedText)
                                ToastUtil.show(getString(R.string.chat_message_action_copied))
                                dispatchOuterCopyNotice(message)
                            } else {
                                handleActionSelected(MessageAction.Type.COPY, message)
                            }
                        }
                        override fun onTranslate(message: TextChatMessage, selectedText: String?) {}
                        override fun onTranslateOff(message: TextChatMessage) {}
                        override fun onForward(message: TextChatMessage, selectedText: String?) {
                            if (selectedText != null) {
                                // Partial selection - forward as plain text (outer-attributed notice).
                                // PRD §5.3.2: operation inside a CF detail view → SUB_COMBINED_FORWARD.
                                selectChatsUtils.showChatSelectAndSendDialog(
                                    requireActivity(),
                                    selectedText,
                                    sourceConversation = outerSourceConversation,
                                    sourceAuthorIds = outerSourceAuthorIds,
                                    combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
                                    // PRD v2.0 §改动1/§改动2 条件①: real author of the selected inner text = message.authorId.
                                    carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(listOf(message), globalServices.myId),
                                )
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
                        override fun onFavoriteGif(message: TextChatMessage) {
                            favoriteGif(message)
                        }
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

                // PRD §5.3.2 SUB_COMBINED_FORWARD: decorate inner CF messages with the
                // outermost CF's source attribution so any derived surface (large-font /
                // translation / STT) dispatched from inside CF detail emits correctly
                // attributed notices. outerSource* is Activity-scope on
                // ChatForwardMessageActivity and stays pinned across navigateToNestedForward(),
                // so this same decoration is correct at any nesting level.
                val outerActivity = activity as? ChatForwardMessageActivity
                val outerConv = outerActivity?.getOuterSourceConversation()
                val outerAuthorId = outerActivity?.getOuterSourceAuthorId()
                chatMessageAdapter.submitList(
                    newList.map { msg ->
                        msg.apply {
                            forWhat = outerConv ?: forWhat
                            sourceAuthorOverride = outerAuthorId
                            sourceMode = CombinedForwardMode.SUB_COMBINED_FORWARD
                        }
                    }
                )
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
        if (!EncryptedAttachmentAccess.isReadable(filePath)) {
            ToastUtil.showLong(R.string.file_load_error)
            return
        }
        val attachment = message.attachment
        val list = arrayListOf<LocalMedia>().apply {
            if (attachment != null) {
                this.add(AttachmentPreview.localMediaFor(message.id, attachment))
            } else {
                this.add(LocalMedia.generateLocalMedia(requireContext(), filePath))
            }
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