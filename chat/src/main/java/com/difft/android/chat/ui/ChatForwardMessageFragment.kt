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
import kotlinx.coroutines.launch
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
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.opensInAppViewer
import com.difft.android.chat.attachment.videoprobe.VideoProbeCoordinator
import com.difft.android.chat.attachment.videoprobe.containsAttachment
import com.difft.android.chat.attachment.videoprobe.withProbedAttachment
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.singleForwardableAttachment
import com.difft.android.chat.gif.favorite.collectFavoriteEffects
import com.difft.android.chat.gif.favorite.MAX_FAVORITE_ASSET_BYTES
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.ui.messageaction.MessageAction
import com.difft.android.chat.ui.messageaction.MessageActionCoordinator
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.engine.ExoVideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnExternalPreviewEventListener
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.Attachment
import javax.inject.Inject
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.isAudioMessage
import com.difft.android.chat.media.AttachmentPreview
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.applyExportPolicy
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isPlayableVideo
import difft.android.messageserialization.model.isVideoShownAsFile
import org.difft.app.database.models.ContactorModel
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.util.SaveAttachmentUtil
import com.difft.android.chat.util.viewFile
import util.TimeFormatter
import java.io.File

@AndroidEntryPoint
class ChatForwardMessageFragment : Fragment() {

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var globalConfigsManager: IGlobalConfigsManager

    @Inject
    lateinit var translateBackend: TranslateBackend

    @Inject
    lateinit var activityNoticeDispatcher: com.difft.android.chat.message.ActivityNoticeDispatcher

    @Inject
    lateinit var videoProbeCoordinator: VideoProbeCoordinator

    private val messageActionHelper by lazy {
        MessageActionHelper(requireActivity(), viewLifecycleOwner.lifecycleScope, selectChatsUtils)
    }

    
    private val favoriteViewModel: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels()

    
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

        
        mBinding.root.background = ChatBackgroundDrawable(requireContext(), mBinding.root, false)

        
        
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.root) { v, insets ->
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val layoutParams = v.layoutParams as? ViewGroup.MarginLayoutParams
            layoutParams?.bottomMargin = navigationBarHeight
            v.layoutParams = layoutParams
            insets
        }

        initView()

        
        collectFavoriteEffects(favoriteViewModel)
    }

    override fun onDestroyView() {
        
        mBinding.recyclerViewMessage.adapter = null
        
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
                
                messageActionHelper.forwardMessage(
                    message,
                    sourceConversation = outerSourceConversation,
                    sourceAuthorIdsOverride = outerSourceAuthorIds,
                    combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
                    carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(listOf(message), globalServices.myId),
                )
            }
            else -> {}
        }
    }

    private fun dispatchOuterCopyNotice(message: TextChatMessage) {
        val conv = outerSourceConversation ?: return
        val authors = outerSourceAuthorIds ?: return
        
        val myId = globalServices.myId
        if (!NoticeAggregator.copyCarriesForeignContent(listOf(message), myId)) return
        activityNoticeDispatcher.dispatchCopyNotice(
            sourceConversation = conv,
            sourceAuthorIds = authors,
            myId = myId,
            messageCount = 1,
            combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
        )
    }

    


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
        
        
        
        val (attachment, messageId) = resolveActionAttachment(message) ?: return
        
        
        if (attachment.size > MAX_FAVORITE_ASSET_BYTES) {
            L.w { "[ChatForwardMessageFragment] favorite gif: too large size=${attachment.size} messageId=$messageId" }
            ToastUtil.show(getString(R.string.gif_favorites_add_size_limit))
            return
        }
        val ref = com.difft.android.chat.gif.favorite.buildMessageRef(attachment, messageId)
        if (ref == null) {
            L.w { "[ChatForwardMessageFragment] favorite gif: missing key/fileName messageId=$messageId" }
            ToastUtil.show(getString(R.string.gif_favorites_failed))
            return
        }
        favoriteViewModel.dispatch(com.difft.android.chat.gif.favorite.FavoriteContract.Intent.Favorite(ref))
    }

    private fun saveAttachment(data: TextChatMessage) {
        val (attachment, messageId) = resolveActionAttachment(data) ?: return
        val attachmentPath = AttachmentPathResolver.fileFor(attachment)
        val progress = data.getAttachmentProgress()
        val isFileValid = EncryptedAttachmentAccess.isReadable(attachmentPath)

        
        
        
        if (isFileValid && (progress == null || progress == 100)) {
            
            
            
            val saveUri = EncryptedAttachmentAccess.exportContentUriIfEncrypted(messageId, attachmentPath)
                ?: File(attachmentPath).toUri()
            val attachmentToSave = SaveAttachmentUtil.Attachment(
                uri = saveUri,
                contentType = attachment.contentType,
                date = System.currentTimeMillis(),
                fileName = attachment.fileName,
                saveAsFile = attachment.isVideoShownAsFile()
            )
            viewLifecycleOwner.lifecycleScope.launch {
                SaveAttachmentUtil.saveWithUI(requireContext(), attachmentToSave)
            }
        } else {
            L.w { "[ChatForwardMessageFragment] save attachment error, readable=$isFileValid downloadCompleted=" + (progress == null || progress == 100) }
            if (!rescueMissingAttachment(data, attachment, isFileValid)) {
                ToastUtil.show(resources.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
            }
        }
    }

    
    

    






    private fun rescueMissingAttachment(
        message: TextChatMessage,
        attachment: Attachment?,
        isFileValid: Boolean
    ): Boolean {
        val progress = message.getAttachmentProgress()
        if (attachment == null ||
            !AttachmentDownloadDecision.shouldRescueMissingFile(isFileValid, attachment.status, progress)
        ) return false
        L.i { "[ChatForwardMessageFragment] rescuing missing attachment localId=${attachment.localId} messageId=${message.id}" }
        downloadAttachment(message, attachment)
        ToastUtil.showLong(R.string.file_preparing)
        return true
    }

    



    private fun downloadAttachment(message: TextChatMessage, attachment: Attachment) {
        val filePath = AttachmentPathResolver.fileFor(attachment)
        
        val forwardActivity = activity as? ChatForwardMessageActivity
        val shouldSaveToPhotos = forwardActivity?.getShouldSaveToPhotos() == true
        val isConfidential = forwardActivity?.isConfidentialMessage() == true
        val autoSave = shouldSaveToPhotos && !isConfidential && (attachment.isImage() || attachment.isPlayableVideo())
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                attachment.localId,
                message.id,
                attachment.id,
                filePath,
                attachment.authorityId,
                attachment.key ?: byteArrayOf(),
                autoSave
            )
        )
    }

    private val chatMessageAdapter = object : ChatMessageAdapter(forWhat = null, contactorCache = contactorCache) {

        override fun onItemClick(rootView: View, data: ChatMessage) {
            if (data is TextChatMessage) {
                if (data.isAttachmentMessage()) {
                    val attachment = data.attachment ?: return
                    if (handleAttachmentTapBeforeOpen(attachment, data.getAttachmentProgress()) { downloadAttachment(data, attachment) }) return

                    if (attachment.opensInAppViewer(isConfidentialPage())) {
                        openPreview(data)
                    } else if (attachment.isVideoShownAsFile()) {
                        openVideoAsFile(data)
                    }
                } else if (data.forwardContext != null && !data.forwardContext?.forwards.isNullOrEmpty()) {
                    val forwardContext = data.forwardContext ?: return
                    if (forwardContext.forwards?.size == 1) {
                        val forward = forwardContext.forwards?.getOrNull(0) ?: return
                        val attachment = forward.attachments?.getOrNull(0) ?: return
                        if (handleAttachmentTapBeforeOpen(attachment, data.getAttachmentProgress()) { downloadAttachment(data, attachment) }) return

                        if (attachment.opensInAppViewer(isConfidentialPage())) {
                            openPreview(generateMessageFromForward(forward) as TextChatMessage)
                        } else if (attachment.isVideoShownAsFile()) {
                            openVideoAsFile(generateMessageFromForward(forward) as TextChatMessage)
                        }
                    } else {
                        
                        
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
            
            if ((activity as? ChatForwardMessageActivity)?.isConfidentialMessage() == true) return
            if (!shouldShowMenu(data)) return

            
            val bodyBubble = rootView.findViewById<View>(R.id.contentContainer) ?: return
            
            
            val textView: TextView? = if (hasTextContent(data)) {
                rootView.findViewById<View>(R.id.contentFrame)?.findViewById(R.id.textView)
            } else {
                null
            }
            
            
            if (messageActionCoordinator == null) {
                messageActionCoordinator = MessageActionCoordinator(requireActivity(), globalConfigsManager, translateBackend.isSupported).apply {
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
                                
                                selectChatsUtils.showChatSelectAndSendDialog(
                                    requireActivity(),
                                    selectedText,
                                    sourceConversation = outerSourceConversation,
                                    sourceAuthorIds = outerSourceAuthorIds,
                                    combinedForwardMode = CombinedForwardMode.SUB_COMBINED_FORWARD,
                                    carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(listOf(message), globalServices.myId),
                                )
                            } else {
                                
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
                mostUseEmojis = null,  
                isForForward = true,
                isSaved = false,
                touchPoint = Point(bodyBubble.width / 2, 0),
                containerView = mBinding.recyclerViewMessage,
                enableTextSelection = textView != null  
            )
        }
        
        


        private fun hasTextContent(data: TextChatMessage): Boolean {
            
            if (!data.message.isNullOrEmpty()) return true
            
            
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

        
        
        override fun onTranslateRetry(message: TextChatMessage) {}
    }

    private fun initView() {
        mBinding.recyclerViewMessage.apply {
            layoutManager = LinearLayoutManager(context)
            itemAnimator = null
            adapter = chatMessageAdapter
            chatMessageAdapter.followBubbleWidth(this)
        }

        
        
        val forwardActivity = activity as? ChatForwardMessageActivity
        val shouldSaveToPhotos = forwardActivity?.getShouldSaveToPhotos() == true
        val isConfidential = forwardActivity?.isConfidentialMessage() == true
        chatMessageAdapter.shouldSaveToPhotos = shouldSaveToPhotos && !isConfidential

        
        val forwardContext = forwardActivity?.getForwardContext(stackIndex)
        if (forwardContext != null) {
            viewLifecycleOwner.lifecycleScope.launch { submitForwardContext(forwardContext) }
        }
        observeVideoProbeResults()
    }

    




    private fun observeVideoProbeResults() {
        viewLifecycleOwner.lifecycleScope.launch {
            videoProbeCoordinator.results.collect { result ->
                val forwardActivity = activity as? ChatForwardMessageActivity ?: return@collect
                val current = forwardActivity.getForwardContext(stackIndex) ?: return@collect
                if (!current.containsAttachment(result.attachmentKey)) return@collect
                val row = videoProbeCoordinator.readRow(result.databaseId) ?: return@collect
                val updated = current.withProbedAttachment(result.attachmentKey, row)
                forwardActivity.replaceForwardContext(stackIndex, updated)
                submitForwardContext(updated)
            }
        }
    }

    
    private suspend fun submitForwardContext(forwardContext: ForwardContext) {
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

    private fun isConfidentialPage(): Boolean = (activity as? ChatForwardMessageActivity)?.isConfidentialMessage() == true

    
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

    



    private fun openVideoAsFile(message: TextChatMessage) {
        if (AttachmentDownloadDecision.isTransferInFlight(message.getAttachmentProgress())) return
        val attachment = message.attachment
        val filePath = attachment?.let { AttachmentPathResolver.fileFor(it) } ?: ""
        if (!EncryptedAttachmentAccess.isReadable(filePath)) {
            if (!rescueMissingAttachment(message, attachment, isFileValid = false)) {
                ToastUtil.showLong(R.string.file_load_error)
            }
            return
        }
        requireContext().viewFile(filePath)
    }

    private fun openPreview(message: TextChatMessage) {
        val attachment = message.attachment
        val filePath = attachment?.let { AttachmentPathResolver.fileFor(it) } ?: ""
        if (!EncryptedAttachmentAccess.isReadable(filePath)) {
            if (!rescueMissingAttachment(message, attachment, isFileValid = false)) {
                ToastUtil.showLong(R.string.file_load_error)
            }
            return
        }
        val list = arrayListOf<LocalMedia>().apply {
            if (attachment != null) {
                this.add(AttachmentPreview.localMediaFor(attachment))
            } else {
                this.add(LocalMedia.generateLocalMedia(requireContext(), filePath))
            }
        }
        PictureSelector.create(requireActivity())
            .openPreview()
            .applyExportPolicy((activity as? ChatForwardMessageActivity)?.isConfidentialMessage() == true)
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