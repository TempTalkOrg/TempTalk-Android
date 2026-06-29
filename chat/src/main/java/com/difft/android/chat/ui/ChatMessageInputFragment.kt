package com.difft.android.chat.ui

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.core.text.getSpans
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.difft.android.PushReactionSendJobFactory
import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RecallResultTracker
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.common.OVERSIZED_TEXT_BODY_LENGTH
import com.difft.android.chat.common.OVERSIZED_TEXT_THRESHOLD
import com.difft.android.chat.common.SendType
import com.difft.android.chat.compose.CombineForwardBar
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.presend.FilePreSendActivity
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.jobs.ReactionSendCoordinator
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.CONTENT_TYPE_LONG_TEXT
import difft.android.messageserialization.model.Draft
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.QuotedAttachment
import difft.android.messageserialization.model.Reaction
import difft.android.messageserialization.model.ReadPosition
import difft.android.messageserialization.model.RealSource
import difft.android.messageserialization.model.Recall
import difft.android.messageserialization.model.ScreenShot
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.SharedContactPhone
import difft.android.messageserialization.model.TextMessage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import difft.android.messageserialization.model.mapToMessageId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.base.widget.InsetAwareConstraintLayout
import org.difft.app.database.convertToContactorModels
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.forwardContext
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.members
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.sharedContacts
import org.difft.app.database.wcdb
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.create
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.v2.MediaSelectionActivity
import com.difft.android.chat.message.getRelevantAttachment
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.util.QuoteThumbnailBinder
import com.difft.android.chat.util.isHostActivityAlive
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.chat.util.ViewUtil
import com.difft.android.chat.util.visible
import util.FileUtils
import util.ScreenLockUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class ChatMessageInputFragment : Fragment() {

    // Use parent fragment as ViewModel owner when nested (in ChatFragment/GroupChatFragment),
    // otherwise use activity (when directly in Activity).
    // Parent fragment initializes ViewModels in onCreateView before child fragments are created.
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    private val draftViewModel: DraftViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    private lateinit var binding: ChatFragmentInputBinding

    private var isGroup: Boolean = false
    private var chatUIData: ChatUIData? = null

    private var sharedContacts: MutableList<SharedContact> = mutableListOf()
    private var quote: Quote? = null
    private var forwardContext: ForwardContext? = null
    private var recall: Recall? = null
    private var reactions: MutableList<Reaction> = mutableListOf()
    private var mentionsSelectedContacts: HashSet<ContactorModel> = hashSetOf()
    private var mentions: MutableList<Mention> = mutableListOf()

    private var mentionsSearchKeyStartPos = -1
    private var mentionsSearchKey: String? = null
    private var prevInputTextLength = 0

    private var currentDraft = Draft()   // hold in-memory copy

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var pushTextSendJobFactory: PushTextSendJobFactory

    @Inject
    lateinit var pushReactionSendJobFactory: PushReactionSendJobFactory

    @Inject
    lateinit var reactionSendCoordinator: ReactionSendCoordinator

    @Inject
    lateinit var pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var localMessageCreator: LocalMessageCreator

    private var keyboardStateListener: InsetAwareConstraintLayout.KeyboardStateListener? = null

    private var screenshotDetector: ScreenshotDetector? = null

    private var inputLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var inputLayoutViewTreeObserver: ViewTreeObserver? = null
    private var inputLayoutAttachListener: View.OnAttachStateChangeListener? = null
    private var updateConfidentialJob: Job? = null
    private var lastInputContentLength = 0
    private var lastInputHeight = 0

    /** Cached flag: whether to hide confidential toggle (group member limit or bot chat) */
    private var shouldHideConfidential = false

    /** Whether confidential button is currently collapsed to the right side */
    private var isConfidentialCollapsed = false

    private val onPicturePermissionForMessage = registerPermission {
        onPicturePermissionForMessageResult(it)
    }

    companion object {
        // If focus was lost more than this before the screenshot callback, the notification
        // panel was likely open. The callback has ~944ms system delay (Pixel Android 14+),
        // so 2000ms safely covers ROM variation while staying below the notification-panel minimum.
        private const val SCREENSHOT_NOTIFICATION_PANEL_THRESHOLD_MS = 2000L
        private const val PANEL_ANIM_DURATION = 250L
    }

    /**
     * Attachment information for sending messages with attachments
     */
    private data class AttachmentInfo(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        val isAudioMessage: Boolean = false
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentInputBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isGroup = chatViewModel.forWhat is For.Group

        initView()

        chatViewModel.chatUIData.filterNotNull().onEach { data ->
            chatUIData = data
            data.group?.let {
                if (it.status == 0) {
                    binding.root.visibility = View.VISIBLE
                } else {
                    binding.root.visibility = View.GONE
                }
                // Refresh confidential toggle when group info changes (member count may have changed)
                updateConfidential()
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateConfidential()
                updateBottomView()
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewLifecycleOwner.lifecycleScope.launch {
            ContactorUtil.contactsUpdate.collect {
                if (!isAdded || view == null) return@collect
                if (it.contains(chatViewModel.forWhat.id)) {
                    updateViewByFriendCheck()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageQuoted.collect {
                if (!isAdded || view == null) return@collect
                val message = it as? TextChatMessage ?: return@collect
                val text = createQuoteContent(message)

                // Synchronous assignment: the quote (with its type-entry attachments, NO bytes) is
                // built and persisted in one pass. The preview is rendered from the replied message's
                // own local attachment, so no async generation is needed.
                quote = Quote(it.timeStamp, it.authorId, text, buildQuotedAttachments(message))
                binding.quoteZone.visibility = View.VISIBLE

                // 显示时：如果是引用自己的消息，显示 "你"；否则从缓存获取作者名称
                binding.author.text = if (it.authorId == globalServices.myId) {
                    getString(R.string.you)
                } else {
                    chatViewModel.contactorCache.getContactor(it.authorId)
                        ?.getDisplayNameWithoutRemarkForUI()
                        ?: it.authorId.formatBase58Id()
                }
                binding.quoteText.text = text
                binding.edittextInput.requestFocus()
                ServiceUtil.getInputMethodManager(activity)
                    .showSoftInput(binding.edittextInput, InputMethodManager.SHOW_IMPLICIT)
                bindQuoteThumbnailPreview(message)
                currentDraft = currentDraft.copy(quote = quote)
                draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageForward.collect {
                if (!isAdded || view == null) return@collect
                val messageToForward = it.first as? TextChatMessage ?: return@collect
                val saveToNote = it.second
                try {
                    val messages = withContext(Dispatchers.IO) {
                        wcdb.message.getFirstObject(DBMessageModel.id.eq(messageToForward.id))?.let { listOf(it) }.orEmpty()
                    }
                    if (!isAdded || view == null) return@collect
                    if (messages.isNotEmpty()) {
                        val message = messages.first()
                        if (message.type == 0 || message.type == 1) {
                            // sharedContacts() + forwardContext() each issue WCDB reads — resolve
                            // off the main thread. Branch on the resolved forwardContext, not the
                            // raw FK id: a non-null forwardContextDatabaseId can resolve to null
                            // (orphaned id / WCDB-KSP NULL→0, #901), which would otherwise forward
                            // the bare "chat history" placeholder instead of the message.
                            val (sharedContactList, resolvedForwardContext) = withContext(Dispatchers.IO) {
                                message.sharedContacts() to message.forwardContext()
                            }
                            if (!isAdded || view == null) return@collect
                            val content: String?
                            if (sharedContactList.isNotEmpty()) {
                                content = ResUtils.getString(R.string.chat_contact_card)

                                val sharedContactId = sharedContactList.getOrNull(0)?.phone?.getOrNull(0)?.value
                                val sharedContactName = sharedContactList.getOrNull(0)?.name?.displayName
                                forwardContext = ForwardContext(emptyList(), false, sharedContactId, sharedContactName)
                            } else if (resolvedForwardContext != null) {
                                content = ResUtils.getString(R.string.chat_history)
                                forwardContext = resolvedForwardContext.apply {
                                    forwards?.forEach { forward ->
                                        changeAttachmentStatus(forward)
                                    }
                                }
                            } else {
                                content = if (messageToForward.isAttachmentMessage()) {
                                    ResUtils.getString(R.string.chat_message_attachment)
                                } else {
                                    messageToForward.message.toString()
                                }
                                forwardContext = ForwardContext(mutableListOf<Forward>().apply {
                                    this.add(
                                        Forward(
                                            messageToForward.timeStamp,
                                            0,
                                            isGroup,
                                            messageToForward.authorId,
                                            message.messageText,
                                            messageToForward.attachment?.let { attach ->
                                                attach.status = AttachmentStatus.LOADING.code
                                                listOf(attach)
                                            },
                                            null,
                                            messageToForward.mentions,
                                            messageToForward.systemShowTimestamp
                                        )
                                    )
                                }, isGroup)
                            }

                            // PRD §5.3: derive mode from the single main-conv source message.
                            val singleMode = NoticeAggregator.computeCombinedForwardMode(
                                listOf(messageToForward),
                                isSubContext = false,
                            )
                            // PRD v2.0 §改动1/§改动2 条件①④: trace only if the message is someone
                            // else's (CF judged by inner authors). The forward/save proceeds either way.
                            val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(
                                listOf(messageToForward),
                                globalServices.myId,
                            )
                            if (saveToNote) {
                                selectChatsUtils.saveToNotes(
                                    requireActivity(),
                                    content,
                                    forwardContext?.let { listOf(it) },
                                    // Source conversation = the currently opened chat.
                                    sourceConversation = chatViewModel.forWhat,
                                    // Single selected message → exactly one outer author.
                                    sourceAuthorIds = listOf(messageToForward.authorId),
                                    combinedForwardMode = singleMode,
                                    carriesForeignContent = carriesForeignContent,
                                )
                            } else {
                                selectChatsUtils.showChatSelectAndSendDialog(
                                    requireActivity(),
                                    content,
                                    null,
                                    null,
                                    forwardContext?.let { listOf(it) },
                                    // Single-message forward: long-press → Forward on ONE message
                                    scene = ForwardNoticeData.Scene.SINGLE,
                                    // Source conversation = the currently opened chat.
                                    sourceConversation = chatViewModel.forWhat,
                                    // Single selected message → exactly one outer author.
                                    sourceAuthorIds = listOf(messageToForward.authorId),
                                    combinedForwardMode = singleMode,
                                    carriesForeignContent = carriesForeignContent,
                                )
                            }
                            forwardContext = null
                        }
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[ChatMessageInputFragment] forward message error: ${e.stackTraceToString()}" }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageRecall.collect {
                if (!isAdded || view == null) return@collect
                showRecallDialog(it)
            }
        }

        // Subscribe to batch recall messages using coroutines
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.batchRecallMessages.collect { messageIds ->
                showBatchRecallDialog(messageIds)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageEmojiReaction.collect {
                if (!isAdded || view == null) return@collect
                sendEmojiReaction(it)
                if (!it.remove && it.actionFrom == EmojiReactionFrom.EMOJI_DIALOG) {
                    globalConfigsManager.updateMostUseEmoji(it.emoji)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageResend.collect { message ->
                if (!isAdded || view == null) return@collect
                resendMessage(message.id)
            }
        }

        // Collect text size changes at Fragment level using StateFlow
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TextSizeUtil.textSizeState.collect { textSize ->
                    updateInputViewSize(textSize == TextSizeUtil.TEXT_SIZE_LAGER)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.confidentialViewReceipt.collect {
                if (!isAdded || view == null) return@collect
                val readPosition = ReadPosition(
                    chatViewModel.forWhat.id.takeIf { chatViewModel.forWhat is For.Group },
                    it.timeStamp,
                    it.systemShowTimestamp,
                    it.notifySequenceId,
                    it.readMaxSId
                )
                ApplicationDependencies.getJobManager().add(
                    pushReadReceiptSendJobFactory.create(it.authorId, chatViewModel.forWhat, listOf(it.timeStamp), readPosition, 1)
                )
            }
        }

        chatViewModel.selectMessagesState.onEach {
            binding.combineForward.isVisible = it.editModel
            binding.clSendMessage.visible = !it.editModel
            if (it.editModel && isVoiceMode) {
                // Voice recorder lives in the parent ChatFragment and is anchored to the
                // bottom of the screen. It would cover the multi-select action bar, so we
                // exit voice mode when entering selection mode.
                isVoiceMode = false
                chatViewModel.setVoiceVisibility(false)
                updateSubmitButtonView()
            }
            if (binding.combineForward.isVisible) {
                binding.combineForward.setContent {
                    val state = chatViewModel.selectMessagesState.collectAsState()
                    CombineForwardBar(
                        stateData = state.value,
                        onForwardClick = {
                            chatViewModel.onForwardClick()
                        },
                        onCopyClick = {
                            chatViewModel.onCopyClick()
                        },
                        onSaveClick = {
                            chatViewModel.onSaveSelectedMessages()
                        },
                        onRecallClick = {
                            chatViewModel.onBatchRecallClick()
                        })
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.forwardMultiMessage.filterNotNull().onEach {
            selectChatsUtils.showChatSelectAndSendDialog(
                requireActivity(),
                it.content,
                null,
                null,
                it.forwardContexts,
                scene = it.scene,
                // Source conversation = the user's current chat, plumbed through ForwardContextData from the ViewModel.
                sourceConversation = it.sourceConversation,
                sourceAuthorIds = it.sourceAuthorIds,
                messageCount = it.messageCount,
                combinedForwardMode = it.combinedForwardMode,
                carriesForeignContent = it.carriesForeignContent,
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.saveMultiMessageToNote.filterNotNull().onEach {
            // Pass the WHOLE list — not `.first()`. Previous bug dropped N-1 attachments
            // + produced wrong notice text on multi-select save-to-notes.
            selectChatsUtils.saveToNotes(
                requireActivity(),
                it.content,
                it.forwardContexts,
                sourceConversation = it.sourceConversation,
                sourceAuthorIds = it.sourceAuthorIds,
                messageCount = it.messageCount,
                combinedForwardMode = it.combinedForwardMode,
                carriesForeignContent = it.carriesForeignContent,
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        registerKeyboardStateListener()

        chatViewModel.listClick
            .onEach {
                if (binding.llChatActions.isVisible) {
                    // Tap list while panel open → close panel (same as "×" button)
                    hidePanel {
                        (parentFragment?.view as? InsetAwareConstraintLayout)?.releaseKeyboardPaddingFreeze()
                    }
                }

                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE

                updateSubmitButtonView()
            }
            .catch { e -> L.w { "[ChatMessageInputFragment] observe recordState error: ${e.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.voiceMessageSend
            .onEach { path ->
                // VoiceRecorderView always produces MPEG-4/AAC. Pin the MIME explicitly because
                // MimeTypeMap.getMimeTypeFromExtension("m4a") returns "audio/mpeg" on some vendor
                // ROMs, which breaks cross-platform receiver rendering (decoded as MP3 / shown as
                // generic file).
                //
                // Override the wire-format filename to a neutral "<timestamp>.m4a" (the format
                // the old MediaRecorder path used) instead of letting it default to the local
                // basename. The dual-candidate recorder names local files
                // `voice-<recipe.id>-<timestamp>.m4a` (e.g. `voice-denoised+higher-...m4a`)
                // for internal lookup, but that internal naming should not leak into the
                // recipient-visible attachment metadata.
                val neutralFileName = "${System.currentTimeMillis()}.m4a"
                prepareSendAttachmentPush(
                    attachmentUri = path.toUri(),
                    mimeType = MediaUtil.AUDIO_MP4,
                    originalFileName = neutralFileName,
                    isAudioMessage = true,
                )
            }
            .catch { L.w { "[ChatMessageInputFragment] observe voiceMessageSend error: ${it.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.showOrHideFullInput
            .onEach {
                if (!it.first) {
                    binding.edittextInput.apply {
                        requestFocus()
                        setText(it.second)
                        setSelection(it.second.length)
                        ViewUtil.focusAndShowKeyboard(this)
                    }
                }
            }
            .catch { L.w { "[ChatMessageInputFragment] observe showOrHideFullInput error: ${it.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        loadDraftOneTime(chatViewModel.forWhat.id)

        chatViewModel.avatarLongClicked
            .onEach { contact ->
                mentionsSelectedContacts.add(contact)
                insertTextToEdittext("@${contact.getDisplayNameWithoutRemarkForUI()} ")
                updateMentions(false)
                ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            }
            .catch { L.w { "[ChatMessageInputFragment] observe selectedMention error: ${it.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun updateInputViewSize(isLarger: Boolean? = null) {
        val textSizeLarger = isLarger ?: TextSizeUtil.isLarger
        binding.edittextInput.textSize = if (textSizeLarger) 21f else 16f
    }

    private var isVoiceMode: Boolean = false

    private fun updateSubmitButtonView() {
        val message: String = binding.edittextInput.text.toString().trim()
        if (!isGroup && !isFriend) {
            binding.clRightActions.visibility = View.GONE
            binding.buttonVoice.visibility = View.GONE
            binding.buttonKeyboard.visibility = View.GONE
            binding.buttonMedia.visibility = View.GONE
            if (!TextUtils.isEmpty(message)) {
                binding.clRightActions.visibility = View.VISIBLE
                binding.buttonSubmit.visibility = View.VISIBLE
            } else {
                binding.clRightActions.visibility = View.GONE
                binding.buttonSubmit.visibility = View.GONE
            }
        } else {
            binding.clRightActions.visibility = View.VISIBLE
            if (isVoiceMode) {
                binding.buttonSubmit.visibility = View.GONE
                binding.buttonVoice.visibility = View.GONE
                binding.buttonKeyboard.visibility = View.VISIBLE
            } else {
                if (!TextUtils.isEmpty(message)) {
                    binding.buttonSubmit.visibility = View.VISIBLE
                    binding.buttonVoice.visibility = View.GONE
                    binding.buttonKeyboard.visibility = View.GONE
                    binding.buttonMedia.visibility = View.GONE
                } else {
                    binding.buttonSubmit.visibility = View.GONE
                    binding.buttonVoice.visibility = View.VISIBLE
                    binding.buttonKeyboard.visibility = View.GONE
                    binding.buttonMedia.visibility = View.VISIBLE
                }
            }
        }
        updateInputContentGoneMargin()
    }

    private fun updateInputContentGoneMargin() {
        val params = binding.clInputContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val needExtraMargin = !isGroup && !isFriend
        params.goneStartMargin = if (needExtraMargin) (12 * resources.displayMetrics.density).toInt() else 0
        binding.clInputContent.layoutParams = params
    }

    private fun initView() {
        binding.quoteDelete.setOnClickListener {
            clearQuoteThumbnail()               // clear Glide load + hide the thumbnail ImageView

            // Remove from memory
            quote = null

            currentDraft = currentDraft.copy(quote = null)

            // Persist removal in DB
            draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)

            // Hide UI
            binding.quoteZone.visibility = View.GONE
        }

        // 设置文件粘贴监听器
        binding.edittextInput.setOnFilePasteListener { uri, mimeType ->
            handleFilePaste(uri, mimeType)
        }

        binding.edittextInput.setOnStickerCommitListener { info, mimeType ->
            handleStickerCommit(info, mimeType)
        }

        binding.edittextInput.doOnTextChanged { text, start, before, count ->
            currentDraft = currentDraft.copy(
                content = text?.toString(),
                quote = quote,
                mentions = mentions.toList(),
            )
            draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
//            L.i { "======doOnTextChanged======" + text + "===" + start + "===" + before + "===" + count }
            if (!TextUtils.isEmpty(text)) {
                if (isGroup) { //处理@相关的逻辑
                    if (count == 1 && text!!.substring(start, start + 1) == "@") {
                        mentionsSearchKeyStartPos = start
                        updateAtView(null)
                    } else {
                        var key: String? = null
                        if (mentionsSearchKeyStartPos != -1) {
                            val keyStart = mentionsSearchKeyStartPos + 1
                            val keyEnd = mentionsSearchKeyStartPos + (start - mentionsSearchKeyStartPos) + count
                            if (text!!.length >= keyStart && text.length >= keyEnd && keyStart < keyEnd) {
                                key = text.substring(keyStart, keyEnd)
                            }
                            if (mentionsSearchKeyStartPos >= text.length) {
                                mentionsSearchKeyStartPos = -1
                                mentionsSearchKey = null
                            }
//                        L.i { "======mentionsSearchKeyStartPos======" + keyStart + "====" + keyEnd + "====" + key }
                        }
                        mentionsSearchKey = key
                        updateAtView(key)
                    }
                    val currentTextLength: Int = text?.length ?: 0
                    if (currentTextLength < prevInputTextLength) { //删除操作
                        if (mentions.isNotEmpty()) {
                            updateMentions(true, start)
                        }
                    } else {
                        if (mentions.isNotEmpty()) {
                            updateMentions(false)
                        }
                    }
                    prevInputTextLength = currentTextLength
                }
                // Check if text exceeds maximum file size (10MB) and truncate if needed
                val utf8Bytes = text.toString().toByteArray(Charsets.UTF_8)
                if (utf8Bytes.size > MAX_TEXT_FILE_SIZE) {
                    val truncatedString = text.toString().utf8Substring(MAX_TEXT_FILE_SIZE)
                    ToastUtil.show(getString(R.string.text_file_exceeds_10mb_limit))
                    binding.edittextInput.setText(truncatedString)
                    binding.edittextInput.setSelection(binding.edittextInput.text?.length ?: 0)
                }
            } else {
                if (isGroup) { //处理@相关的逻辑
                    mentionsSearchKeyStartPos = -1
                    mentionsSearchKey = null
                    updateAtView(null)
                }
            }

            updateSubmitButtonView()
            updateInputViewSize()
        }

        binding.edittextInput.setOnClickListener {
            // Don't hide panel here — keyboard will cover it as overlay.
            // Panel is dismissed in onKeyboardAnimationEnded when keyboard fully opens.
            if (binding.llChatActions.visibility != View.VISIBLE) {
                binding.llChatActions.visibility = View.GONE
            }
            //防止闪烁
            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                if (!isAdded || view == null) return@launch
                binding.edittextInput.apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                }
                ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            }
        }

        binding.buttonMedia.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            // check permission
            // callback to select picture in onPicturePermissionForMessageResult
            onPicturePermissionForMessage.launchMultiplePermission(PermissionUtil.picturePermissions)
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            binding.edittextInput.clearFocus()
        }

        binding.buttonAttachment.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            ScreenLockUtil.temporarilyDisabled = true
            launchFilePicker()
        }

        binding.buttonContact.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            selectChatsUtils.showContactSelectDialog(requireActivity()) { contact ->
                // 用户取消选择，直接返回
                if (contact == null) return@showContactSelectDialog
                if (!isAdded || view == null) return@showContactSelectDialog

                viewLifecycleOwner.lifecycleScope.launch {
                    // 从数据库重新查询联系人信息，避免泄漏备注名
                    val displayName = withContext(Dispatchers.IO) {
                        try {
                            val response = ContactorUtil.getContactWithID(requireContext(), contact.id)
                            if (response.isPresent) {
                                response.get().getDisplayNameWithoutRemarkForUI()
                            } else {
                                L.w { "Contact not found in database, using fallback: ${contact.id}" }
                                contact.id.formatBase58Id()
                            }
                        } catch (e: Exception) {
                            L.e { "Failed to query contact, using fallback: ${e.message}" }
                            contact.id.formatBase58Id()
                        }
                    }
                    val phones = mutableListOf<SharedContactPhone>().apply {
                        this.add(SharedContactPhone(contact.id, 3, null))
                    }
                    sharedContacts.add(SharedContact(SharedContactName(null, null, null, null, null, displayName), phones, null, null, null, null))
                    sendTextPush(null)
                }
            }
        }

        binding.buttonSubmit.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            val message: String = binding.edittextInput.text.toString().trim()
            if (!TextUtils.isEmpty(message)) {
                sendValidatedText(message) {
                    binding.edittextInput.setText("")
                }
            }
        }

        binding.buttonMoreActions.setOnClickListener {
            val root = parentFragment?.view as? InsetAwareConstraintLayout

            if (binding.llChatActions.isVisible) {
                // Panel already behind keyboard — just hide keyboard to reveal it
                ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            } else {
                // Enter panel mode: freeze padding, show panel, hide keyboard
                val hasKeyboard = ViewCompat.getRootWindowInsets(binding.root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                root?.freezeKeyboardPadding()
                val keyboardHeight = InsetAwareConstraintLayout.getKeyboardHeight(requireContext())
                if (keyboardHeight > 0) {
                    binding.llChatActions.minHeight = keyboardHeight
                }
                showPanel(animated = !hasKeyboard)
                chatViewModel.setVoiceVisibility(false)
                chatViewModel.showChatActions()
                ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            }

            binding.buttonMoreActions.visibility = View.GONE
            binding.buttonMoreActionsClose.visibility = View.VISIBLE
            isVoiceMode = false
            updateSubmitButtonView()
        }

        binding.buttonMoreActionsClose.setOnClickListener {
            // Show keyboard to replace panel — keyboard slides up as overlay,
            // panel dismissed in onKeyboardAnimationEnded (same as tapping EditText)
            binding.buttonMoreActions.visibility = View.VISIBLE
            binding.buttonMoreActionsClose.visibility = View.GONE

            isVoiceMode = false
            updateSubmitButtonView()
            ViewUtil.focusAndShowKeyboard(binding.edittextInput)
        }

        binding.buttonVoice.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)

            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                if (!isAdded || view == null) return@launch
                isVoiceMode = true
                updateSubmitButtonView()

                chatViewModel.setVoiceVisibility(true)

                hidePanel {
                    (parentFragment?.view as? InsetAwareConstraintLayout)?.releaseKeyboardPaddingFreeze()
                }

                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE
            }
        }

        binding.buttonKeyboard.setOnClickListener {
            ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            chatViewModel.setVoiceVisibility(false)

            isVoiceMode = false
            updateSubmitButtonView()
        }

        updateConfidential()
        listOf(binding.ivConfidential, binding.ivConfidentialRight).forEach { view ->
            view.setOnClickListener {
                val confidentialMode = if (view.tag == 0) 1 else 0
                // Show first-use tip when enabling confidential mode
                if (confidentialMode == 1 && globalServices.userManager.getUserData()?.hasShownConfidentialTip != true) {
                    showConfidentialTipDialog {
                        chatSettingViewModel.setConversationConfigs(
                            requireActivity(),
                            chatViewModel.forWhat.id,
                            null,
                            null,
                            null,
                            confidentialMode
                        )
                    }
                } else {
                    chatSettingViewModel.setConversationConfigs(
                        requireActivity(),
                        chatViewModel.forWhat.id,
                        null,
                        null,
                        null,
                        confidentialMode
                    )
                }
            }
        }

        inputLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (!isAdded || view == null) return@OnGlobalLayoutListener
            val currentLength = binding.edittextInput.text?.length ?: 0
            if (currentLength != lastInputContentLength) {
                lastInputContentLength = currentLength
                val currentHeight = binding.edittextInput.height
                val prevHeight = lastInputHeight
                if (prevHeight == 0) {
                    // 首次获取高度，仅记录，不触发滚动
                    lastInputHeight = currentHeight
                } else if (currentHeight != prevHeight) {
                    lastInputHeight = currentHeight
                    chatViewModel.emitInputHeightChanged()
                }
                // Hysteresis: collapse at >3 lines, expand at ≤2 lines, no change at 3 lines
                updateConfidentialJob?.cancel()
                updateConfidentialJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(200)
                    if (!isAdded || view == null) return@launch
                    val lineCount = binding.edittextInput.lineCount
                    if (shouldHideConfidential) {
                        binding.ivConfidential.visibility = View.GONE
                        binding.ivConfidentialRight.visibility = View.GONE
                        binding.ivFullInputOpen.visibility = if (lineCount > 3) View.VISIBLE else View.GONE
                    } else if (!isConfidentialCollapsed && lineCount > 3) {
                        isConfidentialCollapsed = true
                        binding.ivConfidential.visibility = View.GONE
                        binding.ivConfidentialRight.visibility = View.VISIBLE
                        binding.ivFullInputOpen.visibility = View.VISIBLE
                    } else if (isConfidentialCollapsed && lineCount <= 2) {
                        isConfidentialCollapsed = false
                        binding.ivConfidential.visibility = View.VISIBLE
                        binding.ivConfidentialRight.visibility = View.GONE
                        binding.ivFullInputOpen.visibility = View.GONE
                    }
                }
            }
        }
        inputLayoutViewTreeObserver = binding.edittextInput.viewTreeObserver
        inputLayoutViewTreeObserver?.addOnGlobalLayoutListener(inputLayoutListener)

        // Use OnAttachStateChangeListener to ensure listener is removed when view is detached
        // This prevents memory leaks when ViewTreeObserver.isAlive() returns false in onDestroyView
        // (e.g., during screen rotation or dual-pane/single-pane mode switch)
        inputLayoutAttachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                inputLayoutListener?.let { listener ->
                    v.viewTreeObserver.removeOnGlobalLayoutListener(listener)
                }
                inputLayoutListener = null
                inputLayoutViewTreeObserver = null
            }
        }
        binding.edittextInput.addOnAttachStateChangeListener(inputLayoutAttachListener)

        binding.ivFullInputOpen.setOnClickListener {
            chatViewModel.showOrHideFullInput(true, binding.edittextInput.text.toString().trim())
        }

//            binding.tvBlock.setOnClickListener {
//                showBlockDialog()
//            }

        binding.tvUnblock.setOnClickListener {
            chatSettingViewModel.setConversationConfigs(
                requireActivity(),
                chatViewModel.forWhat.id,
                null,
                null,
                0,
                null,
                false,
                getString(R.string.contact_unblocked)
            )
//            requestAddFriend()
        }

        binding.tvIgnore.setOnClickListener {
            requireActivity().finish()
        }

        binding.tvAccept.setOnClickListener {
            requestAddFriend(action = "accept", showLoading = true)
        }

//        if (globalServices.myId !== chatViewModel.forWhat.id && !chatViewModel.forWhat.id.isBotId()) {
//            binding.buttonNewCall.visibility = View.VISIBLE
//            binding.buttonNewCall.setOnClickListener {
//                if (chatViewModel.forWhat is For.Group) {
//                    val group = chatUIData?.group ?: return@setOnClickListener
//                    if (!groupUtil.canSpeak(group, globalServices.myId)) {
//                        ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
//                        return@setOnClickListener
//                    }
//                    if (LCallActivity.isInCalling()) {
//                        if (LCallActivity.getConversationId() == chatViewModel.forWhat.id) {
//                            LCallManager.bringInCallScreenBack(requireActivity())
//                        } else {
//                            ToastUtil.show(R.string.call_is_calling_tip)
//                        }
//                    } else {
//                        val callData = LCallManager.getCallDataByConversationId(chatViewModel.forWhat.id)
//                        //判断当前是否有livekit会议，有则join会议
//                        if (callData != null) {
//                            LCallManager.joinCall(requireActivity(), callData.roomId)
//                            return@setOnClickListener
//                        }
//                        // 发起群call
//                        chatViewModel.startCall(requireActivity(), group.name)
//                    }
//                } else {
//                    if (LCallActivity.isInCalling()) {
//                        if (LCallActivity.getConversationId() == chatViewModel.forWhat.id) {
//                            LCallManager.bringInCallScreenBack(requireActivity())
//                        } else {
//                            ToastUtil.show(R.string.call_is_calling_tip)
//                        }
//                    } else {
//                        //判断当前是否有livekit会议，有则join会议
//                        val callData = LCallManager.getCallDataByConversationId(chatViewModel.forWhat.id)
//                        if (callData != null) {
//                            LCallManager.joinCall(requireActivity(), callData.roomId)
//                            return@setOnClickListener
//                        }
//                        // 发起1v1call
//                        val chatRoomName = chatUIData?.let {
//                            it.contact?.displayName
//                        } ?: LCallManager.getDisplayName(chatViewModel.forWhat.id) ?: ""
//                        chatViewModel.startCall(requireActivity(), chatRoomName)
//                    }
//                }
//            }
//        } else {
//            binding.buttonNewCall.visibility = View.GONE
//        }

        updateViewByFriendCheck()

        if (isGroup) {
            binding.buttonAt.visibility = View.VISIBLE
            binding.buttonAt.setOnClickListener {
                insertTextToEdittext("@")

                hidePanel(animated = false)
                ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            }
        } else {
            binding.buttonAt.visibility = View.GONE
        }

        binding.rvAt.apply {
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
            adapter = contactsAtAdapter
        }

        binding.ivAtClose.setOnClickListener {
            binding.llAt.visibility = View.GONE
        }

        updateInputViewSize()
    }

    /**
     * Load the draft once from DB and fill the UI.
     */
    private fun loadDraftOneTime(roomId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val draft = draftViewModel.loadDraft(roomId)
            if (draft != null) {
                currentDraft = draft
                quote = draft.quote
                mentions = draft.mentions.toMutableList()
                withContext(Dispatchers.IO) {
                    mentionsSelectedContacts.addAll(wcdb.getContactorsFromAllTable(draft.mentions.mapNotNull { it.uid }))
                }
                if (!isAdded || view == null) return@launch
                // Fill UI
                draft.content?.let { content ->
                    if (binding.edittextInput.text.toString() != content) {
                        binding.edittextInput.setText(content)
                        binding.edittextInput.setSelection(content.length)
                    }
                }

                // 1) Show or hide the quote zone
                val quote = draft.quote
                if (quote != null) {
                    binding.quoteZone.visibility = View.VISIBLE

                    // 显示引用的作者：如果是自己显示 "你"，否则从缓存获取作者名称
                    binding.author.text = if (quote.author == globalServices.myId) {
                        getString(R.string.you)
                    } else {
                        chatViewModel.contactorCache.getContactor(quote.author)
                            ?.getDisplayNameWithoutRemarkForUI()
                            ?: quote.author.formatBase58Id()
                    }

                    // Show the quoted text
                    binding.quoteText.text = quote.text
                    bindQuoteThumbnailPreview(quote)
                } else {
                    binding.quoteZone.visibility = View.GONE
                }
            }
        }
    }

    private fun updateViewByFriendCheck() {
        if (chatViewModel.forWhat is For.Account) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                isFriend = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(chatViewModel.forWhat.id)) != null
                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    updateSubmitButtonView()
                    updateBottomView()
                    updateConfidential()
                    if (!isFriend) {
                        checkAndUpdateFriendRequestStatus()
                    }
                }
            }
        }
    }

    /**
     * ActivityResultLauncher must be registered before Fragment enters CREATED state.
     * Registering in onViewCreated causes "unregistered ActivityResultLauncher" crash
     * when Fragment is recreated (e.g., configuration change, process death).
     *
     * IMPORTANT: PictureSelector must use forResult(ActivityResultLauncher) instead of
     * forResult(OnResultCallbackListener) to avoid the same crash. The callback approach
     * captures the Fragment instance at creation time, and when Fragment is recreated,
     * the callback still references the old Fragment whose launcher is already unregistered.
     */
    private val pictureSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val selectedMedia = PictureSelector.obtainSelectorList(result.data)
            val list = selectedMedia.filter { it.size < FileUtil.MAX_SUPPORT_FILE_SIZE }
            if (list.isNotEmpty()) {
                val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                    putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, ArrayList(list))
                    putExtra(MediaSelectionActivity.EXTRA_CONFIDENTIAL_MODE, chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0)
                    putExtra(MediaSelectionActivity.EXTRA_SHOW_CONFIDENTIAL_TOGGLE, !shouldHideConfidential)
                    putExtra(MediaSelectionActivity.EXTRA_CONVERSATION_ID, chatViewModel.forWhat.id)
                }
                mediaSelectActivityLauncher.launch(intent)
            }
            if (list.size < selectedMedia.size) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
            }
        }
    }

    private val mediaSelectActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                val sendResult = MediaSendActivityResult.fromData(intent)
                val list = sendResult.media
                val body = sendResult.body
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        list.forEachIndexed { index, media ->
                            // Use original filename, fallback to extracting from original path (not realPath which may be UUID)
                            val fileName = media.fileName.takeIf { !it.isNullOrEmpty() }
                                ?: FileUtils.getFileName(media.path)
                            prepareSendAttachmentPush(media.realPath.toUri(), media.mimeType, fileName)
                            if (index < list.size - 1) delay(300)
                        }
                        delay(500)
                        if (body.isNotEmpty()) {
                            sendValidatedText(body)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[ChatMessageInputFragment] sendEditedMessage error: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
    }

    /**
     * Prefer ACTION_OPEN_DOCUMENT (SAF / DocumentsProvider) over ACTION_GET_CONTENT.
     *
     * ACTION_GET_CONTENT can be routed to legacy providers (e.g. some OEM file
     * managers, stale MediaStore _data entries) that expose metadata but fail the
     * byte-stream open with ENOENT. ACTION_OPEN_DOCUMENT resolves against the real
     * filesystem and avoids that. Falls back to ACTION_GET_CONTENT only when no
     * activity handles ACTION_OPEN_DOCUMENT. Mirrors Signal's AttachmentManager.
     */
    private fun launchFilePicker() {
        val intent = Intent().apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        intent.action = Intent.ACTION_OPEN_DOCUMENT
        try {
            fileActivityLauncher.launch(intent)
            return
        } catch (e: ActivityNotFoundException) {
            L.w { "[ChatMessageInputFragment] ACTION_OPEN_DOCUMENT no activity, fallback to GET_CONTENT: ${e.message}" }
        }
        intent.action = Intent.ACTION_GET_CONTENT
        try {
            fileActivityLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            L.w { "[ChatMessageInputFragment] ACTION_GET_CONTENT no activity: ${e.message}" }
            ToastUtil.showLong(R.string.unsupported_file_type)
        }
    }

    private val fileActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val uri = result.data?.data
        if (uri == null) {
            ToastUtil.showLong(R.string.file_unavailable)
            return@registerForActivityResult
        }

        viewLifecycleOwner.lifecycleScope.launch {
            // 优先判断文件大小是否超过200MB
            val fileSize = withContext(Dispatchers.IO) { FileUtil.getFileSize(uri) }
            if (!isAdded || view == null) return@launch
            if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                return@launch
            }

            val copyResult = withContext(Dispatchers.IO) {
                runCatching { FileUtil.copyUriToFile(uri) }
                    .onFailure { L.e { "copyUriToFile failed: ${it.stackTraceToString()}" } }
                    .getOrNull()
            }

            if (copyResult == null) {
                ToastUtil.showLong(R.string.file_unavailable)
                return@launch
            }

            val path = copyResult.tempFile.absolutePath
            val originalFileName = copyResult.originalFileName
            val mimeType = FileUtil.getMimeTypeType(uri)

            if (MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)) {
                val localMedia = LocalMedia().apply {
                    this.realPath = path
                    this.mimeType = mimeType
                    this.fileName = originalFileName
                }
                val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                    putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, arrayListOf(localMedia))
                    putExtra(MediaSelectionActivity.EXTRA_CONFIDENTIAL_MODE, chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0)
                    putExtra(MediaSelectionActivity.EXTRA_SHOW_CONFIDENTIAL_TOGGLE, !shouldHideConfidential)
                    putExtra(MediaSelectionActivity.EXTRA_CONVERSATION_ID, chatViewModel.forWhat.id)
                }
                mediaSelectActivityLauncher.launch(intent)
            } else {
                val filePreSendIntent = FilePreSendActivity.createIntent(
                    context = requireContext(),
                    filePath = path,
                    fileName = originalFileName ?: "",
                    mimeType = mimeType ?: "",
                    fileSize = fileSize,
                    confidentialMode = chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0,
                    showConfidentialToggle = !shouldHideConfidential,
                    conversationId = chatViewModel.forWhat.id
                )
                filePreSendLauncher.launch(filePreSendIntent)
            }
        }
    }


    private val filePreSendLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                val filePath = intent.getStringExtra(FilePreSendActivity.RESULT_FILE_PATH) ?: return@let
                val fileName = intent.getStringExtra(FilePreSendActivity.RESULT_FILE_NAME) ?: ""
                val mimeType = intent.getStringExtra(FilePreSendActivity.RESULT_MIME_TYPE) ?: ""
                val body = intent.getStringExtra(FilePreSendActivity.RESULT_BODY) ?: ""

                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        prepareSendAttachmentPush(filePath.toUri(), mimeType, fileName)
                        if (body.isNotEmpty()) {
                            delay(500)
                            sendValidatedText(body)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[ChatMessageInputFragment] filePreSend error: ${e.stackTraceToString()}" }
                    }
                }
            }
        }
    }


    private fun createQuoteContent(message: TextChatMessage): String {
        val text = if (message.isAttachmentMessage()) {
            if (message.attachment?.isImage() == true) {
                ResUtils.getString(R.string.chat_message_image)
            } else if (message.attachment?.isVideo() == true) {
                ResUtils.getString(R.string.chat_message_video)
            } else if (message.attachment?.isAudioMessage() == true || message.attachment?.isAudioFile() == true) {
                ResUtils.getString(R.string.chat_message_audio)
            } else {
                ResUtils.getString(R.string.chat_message_attachment)
            }
        } else if (message.forwardContext != null) {
            val forwardContext = message.forwardContext
            if (forwardContext?.forwards?.size == 1) {
                val forward = forwardContext.forwards?.firstOrNull()
                if (!forward?.attachments.isNullOrEmpty()) {
                    // Type-specific label (image/video/audio) for a forwarded media message,
                    // matching the normal-attachment branch above instead of a generic "[Attachment]".
                    getString(MediaUtil.quoteTypeLabelRes(forward?.attachments?.firstOrNull()?.contentType))
                } else {
                    forward?.text
                }
            } else {
                getString(R.string.chat_history)
            }
        } else if (!message.sharedContacts.isNullOrEmpty()) {
            getString(R.string.chat_message_contact_card)
        } else {
            message.message.toString()
        }
        return text ?: ""
    }

    /**
     * Builds the [QuotedAttachment] list (a single type-entry: contentType/fileName/flags, NO
     * thumbnail bytes) for a reply to a media message. Returns null for a text-only message (no
     * attachment). Forward-aware: for a single-forward reply, the relevant attachment is the
     * forwarded one. The preview is rendered from the replied message's own local file; the wire
     * carries only the entry so the recipient reverse-looks-up its own local original.
     */
    internal fun buildQuotedAttachments(message: TextChatMessage): List<QuotedAttachment>? {
        val attachment = message.getRelevantAttachment() ?: return null
        return listOf(
            QuotedAttachment(
                contentType = attachment.contentType,
                fileName = attachment.fileName ?: "",
                thumbnail = null,
                flags = attachment.flags
            )
        )
    }

    /**
     * Local file path of the replied message's media, forward-aware.
     * - Normal attachment: `getMessageAttachmentFilePath(message.id) + fileName`.
     * - Single-forward: the forwarded file lives under the attachment's `authorityId` directory
     *   (NOT message.id) — see generateMessageFromForward / ChatMessageListFragment:1774.
     * Returns null if the resolved file does not exist on disk.
     */
    private fun quoteLocalAttachmentPath(message: TextChatMessage, attachment: Attachment): String? {
        val forwards = message.forwardContext?.forwards
        val dirId = if (forwards?.size == 1) attachment.authorityId.toString() else message.id
        val fileName = attachment.fileName ?: return null
        val path = FileUtil.getMessageAttachmentFilePath(dirId) + fileName
        return path.takeIf { File(it).exists() }
    }

    /** Clears the Glide load and hides the quote thumbnail ImageView (does NOT touch quoteZone). */
    private fun clearQuoteThumbnail() {
        // Guard against a dead host: on rapid back-press during send (resetData → finishing Activity)
        // Glide.with can throw IllegalArgumentException. Mirrors clearQuoteThumbnail(ImageView) in
        // ChatMessageViewHolder.
        if (binding.quoteThumbnail.isHostActivityAlive()) {
            Glide.with(binding.quoteThumbnail).clear(binding.quoteThumbnail)
        }
        binding.quoteThumbnail.visibility = View.GONE
    }

    /**
     * Binds the compose-bar quote preview from the live replied message (Mac full-type semantics):
     * audio → mic icon, image/video with a local original on disk → rounded Glide load, image/video
     * without a local file → GONE (text-only), genuine file → [R.drawable.ic_file], text-only → GONE.
     */
    private fun bindQuoteThumbnailPreview(message: TextChatMessage) {
        val attachment = message.getRelevantAttachment()
        if (attachment == null) {
            clearQuoteThumbnail()
            return
        }
        val isAudio = attachment.flags == 1 || MediaUtil.isAudioType(attachment.contentType)
        if (isAudio) {
            binding.quoteThumbnail.visibility = View.VISIBLE
            QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, R.drawable.chat_ic_quote_mic)
            return
        }
        when {
            MediaUtil.isImageOrVideoType(attachment.contentType) -> {
                // Stay GONE until the off-main-thread file check confirms a local original —
                // quoteLocalAttachmentPath does File.exists(), which must not run on the main thread.
                clearQuoteThumbnail()
                loadComposeQuoteThumbnailAsync(message.timeStamp) {
                    quoteLocalAttachmentPath(message, attachment)
                }
            }
            else -> {
                // Genuine file (pdf/doc/zip/etc.) → file icon.
                binding.quoteThumbnail.visibility = View.VISIBLE
                QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, R.drawable.ic_file)
            }
        }
    }

    /**
     * Resolves a compose-bar quote-thumbnail path off the main thread and applies it only if the
     * compose quote is still the same one ([expectedQuoteId]) when the lookup returns. Guards both
     * view teardown (`!isAdded`) AND the quote being dismissed/replaced mid-lookup (the field
     * [quote] is nulled on delete/reset and reassigned on a new reply) — without that check a stale
     * lookup could re-show a thumbnail the user already dismissed.
     */
    private fun loadComposeQuoteThumbnailAsync(expectedQuoteId: Long, resolve: suspend () -> String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { resolve() }
            if (!isAdded || view == null) return@launch
            if (quote?.id != expectedQuoteId) return@launch // quote dismissed or replaced while resolving
            if (path == null) {
                clearQuoteThumbnail()
                return@launch
            }
            binding.quoteThumbnail.visibility = View.VISIBLE
            QuoteThumbnailBinder.loadRoundedThumbnail(binding.quoteThumbnail, File(path))
        }
    }

    /**
     * Draft-restore variant: there is no live [TextChatMessage] in scope, only the deserialized
     * [Quote]. Renders by type — audio → mic, genuine file → [R.drawable.ic_file], null → GONE.
     * For image/video the restored [QuotedAttachment] carries no local path, so the original message
     * is reverse-looked-up locally (by [Quote.id] = original timestamp + the current room) on
     * [Dispatchers.IO]; its on-disk thumbnail is shown if found, else text-only. Mirrors the list
     * renderer's [findOriginalAttachmentPath].
     */
    private fun bindQuoteThumbnailPreview(quote: Quote?) {
        val qa = quote?.attachments?.firstOrNull()
        if (qa == null) {
            clearQuoteThumbnail()
            return
        }
        val isAudio = qa.flags == 1 || MediaUtil.isAudioType(qa.contentType)
        when {
            isAudio -> {
                binding.quoteThumbnail.visibility = View.VISIBLE
                QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, R.drawable.chat_ic_quote_mic)
            }
            MediaUtil.isImageOrVideoType(qa.contentType) -> {
                // No inline bytes on restore → reverse-look-up the local original off the main thread.
                // Stay GONE until/unless the lookup finds a file (avoids a flash of misleading icon).
                clearQuoteThumbnail()
                val forWhat = chatViewModel.forWhat
                loadComposeQuoteThumbnailAsync(quote.id) {
                    findOriginalAttachmentPath(quote.id, forWhat.id, forWhat.typeValue)
                }
            }
            else -> {
                binding.quoteThumbnail.visibility = View.VISIBLE
                QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, R.drawable.ic_file)
            }
        }
    }

    private val contactsAtAdapter: ContactsAtAdapter by lazy {
        object : ContactsAtAdapter() {
            override fun onContactClicked(contact: ContactorModel, position: Int) {
                mentionsSelectedContacts.add(contact)
                insertTextToEdittext(contact.getDisplayNameWithoutRemarkForUI() + " ")
                mentionsSearchKeyStartPos = -1
                mentionsSearchKey = null
                updateAtView(null)
                updateMentions(false)

                binding.edittextInput.requestFocus()
                ServiceUtil.getInputMethodManager(activity).showSoftInput(binding.edittextInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    fun updateMentions(isDelete: Boolean, deletePos: Int = 0) {
        val content = binding.edittextInput.text ?: return

        if (isDelete) {
            mentions.find { deletePos >= it.start && deletePos <= it.start + it.length }?.let {
                val end = if (it.start + it.length < content.length) it.start + it.length else content.length
                content.delete(it.start, end)
            }
        }

        val set = mentionsSelectedContacts.map { "@" + it.getDisplayNameWithoutRemarkForUI() }.toHashSet()
        val text = binding.edittextInput.text.toString().trim()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    val map = findContainedSubstringsWithPositions(text, set)
                    val mentionsMap = hashMapOf<Int, Mention>()

                    map.map { posMap ->
                        val id = mentionsSelectedContacts.find { contact -> "@" + contact.getDisplayNameWithoutRemarkForUI() == posMap.key }?.id
                        posMap.value.forEach { pos ->
                            mentionsMap[pos.first] = Mention(pos.first, posMap.key.length, id, 0)
                        }
                    }

                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        mentions.clear()
                        mentions.addAll(mentionsMap.values.toList())

                        val spannable = content as Spannable
                        spannable.getSpans<ForegroundColorSpan>().forEach {
                            spannable.removeSpan(it)
                        }
                        val spans = mentions.mapNotNull { mention ->
                            val start = mention.start
                            val end = start + mention.length
                            if (start < content.length && end <= content.length && start < end && mention.length > 0) {
                                ForegroundColorSpan(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_info)) to (start to end)
                            } else null
                        }

                        spans.forEach { (span, range) ->
                            spannable.setSpan(span, range.first, range.second, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                }
            }.onFailure { e ->
                L.e { "update mentions span error: ${e.stackTraceToString()}" }
            }
        }

        currentDraft = currentDraft.copy(mentions = mentions.toList())
        draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
    }

    private fun updateAtView(key: String? = null) {
        val content = binding.edittextInput.text.toString().trim()
        var preLetter: Char? = null
        if (mentionsSearchKeyStartPos - 1 >= 0 && mentionsSearchKeyStartPos - 1 < content.length) {
            preLetter = content[mentionsSearchKeyStartPos - 1]
        }
        val canShow = (preLetter == null)
                || (preLetter.isLetter() && preLetter.code in 0x4E00..0x9FFF)
                || preLetter.isWhitespace()
                || (preLetter.isLetterOrDigit().not() && preLetter.isWhitespace().not())

        if (canShow && mentionsSearchKeyStartPos != -1) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {

                val contacts = mutableListOf<ContactsAtAdapter.Item>()

                val contactsOfGroup = chatUIData?.group?.members?.convertToContactorModels()?.mapNotNull { member ->
                    if (key == null || member.getDisplayNameForUI().contains(key, true)) member else null
                }?.sortedByPinyin() ?: emptyList()

                if (contactsOfGroup.isNotEmpty()) {
                    contacts.add(ContactsAtAdapter.Item.Title(getString(R.string.chat_at_group_members), getString(R.string.chat_at_tips)))
                    contacts.add(ContactsAtAdapter.Item.Contact(ContactorModel().apply { id = MENTIONS_ALL_ID; name = getString(R.string.chat_at_all) }))
                    contacts.addAll(contactsOfGroup.map { ContactsAtAdapter.Item.Contact(it) })
                }

//            if (contactsOther.isNotEmpty()) {
//                contacts.add(ContactsAtAdapter.Item.Title(getString(R.string.chat_at_other), getString(R.string.chat_at_other_tips)))
//                contacts.addAll(contactsOther.sortedWith(pinyinComparator).map { ContactsAtAdapter.Item.Contact(it) })
//            }

                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    if (contacts.isEmpty()) {
                        binding.llAt.visibility = View.GONE
                        contactsAtAdapter.submitList(emptyList())
                    } else {
                        binding.llAt.visibility = View.VISIBLE
                        contactsAtAdapter.submitList(contacts)
                    }
                }
            }
        } else {
            binding.llAt.visibility = View.GONE
            contactsAtAdapter.submitList(emptyList())
        }
    }

    private fun findContainedSubstringsWithPositions(mainString: String, substrings: HashSet<String>): Map<String, List<Pair<Int, Int>>> {
        val containedSubstrings = mutableMapOf<String, MutableList<Pair<Int, Int>>>()

        substrings.forEach { substring ->
            val regex = Regex(Regex.escape(substring))
            regex.findAll(mainString).forEach { match ->
                containedSubstrings.getOrPut(substring, ::mutableListOf).add(match.range.first to match.range.last)
            }
        }

        return containedSubstrings
    }

    private fun insertTextToEdittext(content: String) {
        val editable: Editable? = binding.edittextInput.text
        val cursorPosition = binding.edittextInput.selectionStart
        editable?.let { text ->
            mentionsSearchKey?.let { key ->
                val start = mentionsSearchKeyStartPos + 1
                val end = mentionsSearchKeyStartPos + key.length + 1
                if (start <= text.length && end <= text.length) {
                    text.replace(start, end, content)
                }
            } ?: run {
                text.insert(cursorPosition, content)
            }
        }
    }

    private var isFriend = false

    private fun updateBottomView() {
        if (!isGroup) {

            if (isFriend || chatViewModel.forWhat.id.isBotId()) {
                binding.clSendMessage.visibility = View.VISIBLE
                binding.clFriends.visibility = View.GONE

                binding.clLeftActions.visibility = View.VISIBLE
            } else {
                val isReceivedFriendRequest = ContactorUtil.hasContactRequest(chatViewModel.forWhat.id)

                if (isReceivedFriendRequest) {
                    binding.clSendMessage.visibility = View.GONE
                    binding.clFriends.visibility = View.VISIBLE
//                    binding.llFriends.visibility = View.VISIBLE
                    binding.tvFoundYou.visibility = View.VISIBLE
                    val warning = getString(R.string.chat_do_not_trust_warning)
                    val spannable = SpannableString(warning)
                    val boldKeywords = arrayOf("DO NOT", "请勿")
                    for (keyword in boldKeywords) {
                        val start = warning.indexOf(keyword)
                        if (start >= 0) {
                            spannable.setSpan(StyleSpan(Typeface.BOLD), start, start + keyword.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    binding.tvFoundYou.text = spannable
                } else {
                    binding.clSendMessage.visibility = View.VISIBLE
                    binding.clFriends.visibility = View.GONE
                }

                binding.clLeftActions.visibility = View.GONE
            }
//            }

            if (chatSettingViewModel.currentConversationSet?.blockStatus == 1) {
                binding.tvUnblock.visibility = View.VISIBLE
                binding.clFriends.visibility = View.GONE
                binding.clSendMessage.visibility = View.GONE
            } else {
                binding.tvUnblock.visibility = View.GONE
            }
        }
    }

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var chatHttpClient: ChativeHttpClient

    private fun checkAndUpdateFriendRequestStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    chatHttpClient.httpService
                        .fetchShareConversationConfig(
                            (globalServices.userManager.getUserData()?.microToken ?: ""),
                            GetConversationShareRequestBody(
                                listOf(messageArchiveManager.conversationParams(chatViewModel.forWhat.id)),
                                true
                            )
                        )
                }
                if (response.status == 0) {
                    response.data?.conversations?.firstOrNull()?.let { config ->
                        if (config.askedVersion > 0) {
                            ContactorUtil.updateContactRequestStatus(chatViewModel.forWhat.id)
                            updateBottomView()
                        } else {
                            ContactorUtil.updateContactRequestStatus(chatViewModel.forWhat.id, true)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatMessageInputFragment] checkIsFriendExist error: ${e.stackTraceToString()}" }
            }
        }
    }


    private fun updateConfidential() {
        // For group chats, check member count limit first
        if (isGroup) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val memberCount = wcdb.getGroupMemberCount(chatViewModel.forWhat.id)
                val memberLimit = globalConfigsManager.getGroupConfidentialMemberLimit()
                val hideConfidential = memberCount >= memberLimit

                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    updateConfidentialUI(hideConfidential)
                }
            }
        } else if (chatViewModel.forWhat.id.isBotId() || !isFriend) {
            updateConfidentialUI(true)
        } else {
            updateConfidentialUI(false)
        }
    }

    private fun updateConfidentialUI(hideConfidential: Boolean) {
        // Cache the state for use in input layout listener
        shouldHideConfidential = hideConfidential

        if (hideConfidential) {
            // Hide confidential toggle (group member limit exceeded or bot chat)
            binding.ivConfidential.visibility = View.GONE
            binding.ivConfidentialRight.visibility = View.GONE
            binding.edittextInput.hint = getString(R.string.chat_message_input_hint)
            binding.vConfidentialLine.visibility = View.GONE
            applyConfidentialAreaStyle(false)
        } else {
            // Show appropriate icon based on input line count (same hysteresis as listener)
            val lineCount = binding.edittextInput.lineCount
            if (lineCount > 3) isConfidentialCollapsed = true
            else if (lineCount <= 2) isConfidentialCollapsed = false
            // lineCount == 3: keep current state
            binding.ivConfidential.visibility = if (isConfidentialCollapsed) View.GONE else View.VISIBLE
            binding.ivConfidentialRight.visibility = if (isConfidentialCollapsed) View.VISIBLE else View.GONE

            val isConfidentialOn = chatSettingViewModel.currentConversationSet?.confidentialMode == 1
            val drawable = if (isConfidentialOn) {
                ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_enable)
            } else {
                ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable).apply {
                    setTint(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
                }
            }
            listOf(binding.ivConfidential, binding.ivConfidentialRight).forEach { view ->
                view.setImageDrawable(drawable)
                view.tag = if (isConfidentialOn) 1 else 0
            }
            if (isConfidentialOn) {
                binding.edittextInput.hint = getString(R.string.chat_message_input_hint_confidential)
                binding.vConfidentialLine.visibility = View.VISIBLE
                applyConfidentialAreaStyle(true)
            } else {
                binding.edittextInput.hint = getString(R.string.chat_message_input_hint)
                binding.vConfidentialLine.visibility = View.GONE
                applyConfidentialAreaStyle(false)
            }
        }
    }

    private fun applyConfidentialAreaStyle(isConfidential: Boolean) {
        if (isConfidential) {
            val overlayColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg_confidential_area)
            // Apply blue overlay to the entire input area and root
            binding.root.setBackgroundColor(overlayColor)
            binding.clInput.setBackgroundColor(overlayColor)
            binding.llChatActions.setBackgroundColor(overlayColor)
            // Input field: bg1 with line border
            binding.clInputContent.setBackgroundResource(R.drawable.chat_msg_input_bg_confidential)
            // Action icon containers: bg1 background (no border)
            binding.clContact.setBackgroundResource(R.drawable.chat_msg_icon_bg_confidential)
            binding.clAttachment.setBackgroundResource(R.drawable.chat_msg_icon_bg_confidential)
            binding.clAtImage.setBackgroundResource(R.drawable.chat_msg_icon_bg_confidential)
        } else {
            val bg1Color = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1)
            // Restore normal backgrounds
            binding.root.setBackgroundColor(bg1Color)
            binding.clInput.background = null
            binding.llChatActions.background = null
            // Input field: normal bg2 background
            binding.clInputContent.setBackgroundResource(R.drawable.chat_msg_input_field_bg)
            // Action icon containers: normal bg2 background
            binding.clContact.setBackgroundResource(R.drawable.chat_msg_input_bg)
            binding.clAttachment.setBackgroundResource(R.drawable.chat_msg_input_bg)
            binding.clAtImage.setBackgroundResource(R.drawable.chat_msg_input_bg)
        }
    }

    private fun showRecallDialog(message: ChatMessage) {
        ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = requireActivity().getString(R.string.chat_message_action_recall),
            message = requireActivity().getString(R.string.chat_recall_tips),
            confirmText = requireActivity().getString(R.string.chat_recall_dialog_yes),
            cancelText = requireActivity().getString(R.string.chat_recall_dialog_cancel),
            onConfirm = {
                recallMessage(message.id)
            }
        )
    }

    /**
     * Show batch recall confirmation dialog
     */
    private fun showBatchRecallDialog(messageIds: Set<String>) {
        ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = requireActivity().getString(R.string.chat_message_action_recall),
            message = requireActivity().getString(R.string.chat_recall_tips),
            confirmText = requireActivity().getString(R.string.chat_recall_dialog_yes),
            cancelText = requireActivity().getString(R.string.chat_recall_dialog_cancel),
            onConfirm = {
                batchRecallMessages(messageIds)
            }
        )
    }

    /**
     * Perform batch recall operation with wait dialog
     * Waits for all recall jobs to complete (success or failure) before dismissing loading
     */
    private fun batchRecallMessages(messageIds: Set<String>) {
        ComposeDialogManager.showWait(requireActivity(), cancelable = false)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Track pending message IDs
                val pendingIds = messageIds.toMutableSet()
                var hasFailure = false

                // Start listening for recall results before submitting jobs
                val resultJob = launch {
                    RecallResultTracker.recallResults.collect { result ->
                        if (pendingIds.contains(result.messageId)) {
                            pendingIds.remove(result.messageId)
                            if (!result.success) {
                                hasFailure = true
                            }
                            L.i { "Batch recall progress: ${messageIds.size - pendingIds.size}/${messageIds.size}, success: ${result.success}" }
                        }
                    }
                }

                // Submit all recall jobs
                messageIds.forEach { messageId ->
                    submitRecallJob(messageId)
                }

                // Wait for all results with timeout (30 seconds max)
                val timeoutMs = 30_000L
                val startTime = System.currentTimeMillis()
                while (pendingIds.isNotEmpty() && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    delay(100)
                }

                // Cancel the collector job
                resultJob.cancel()

                // Show failure toast if any recall failed or timed out
                if (hasFailure || pendingIds.isNotEmpty()) {
                    ToastUtil.show(R.string.operation_failed)
                }

                // Close selection mode after batch recall
                chatViewModel.selectModel(false)
            } catch (e: Exception) {
                L.e { "Batch recall failed: ${e.stackTraceToString()}" }
                ToastUtil.show(R.string.operation_failed)
            } finally {
                ComposeDialogManager.dismissWait()
            }
        }
    }

    /**
     * Submit a recall job for a single message
     */
    private suspend fun submitRecallJob(messageID: String) = withContext(Dispatchers.IO) {
        val originMessage = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))
        if (originMessage == null) {
            L.w { "Recall message failed, original message not found: $messageID" }
            // Emit failure result for tracking
            RecallResultTracker.emitResult(messageID, false)
            return@withContext
        }
        if (originMessage.fromWho != globalServices.myId) {
            L.w { "Recall message failed, the sender does not match, realSource:${originMessage.fromWho}" }
            RecallResultTracker.emitResult(messageID, false)
            return@withContext
        }
        val textMessage = TextMessage(
            id = messageID,
            fromWho = For.Account(globalServices.myId),
            forWhat = chatViewModel.forWhat,
            systemShowTimestamp = System.currentTimeMillis(),
            timeStamp = System.currentTimeMillis(),
            receivedTimeStamp = System.currentTimeMillis(),
            sendType = SendType.Sending.rawValue,
            expiresInSeconds = originMessage.expiresInSeconds,
            notifySequenceId = 0,
            sequenceId = 0,
            mode = 0,
            text = null
        )
        val sourceDeviceId = originMessage.id.lastOrNull().toString().toInt()
        val recallObj = Recall(
            RealSource(
                globalServices.myId,
                sourceDeviceId,
                originMessage.timeStamp,
                originMessage.systemShowTimestamp
            )
        )
        textMessage.recall = recallObj
        ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
    }

    /**
     * Show confidential message first-use tip dialog
     */
    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        // Mark as shown when dialog closes (regardless of how it's closed)
        globalServices.userManager.update { hasShownConfidentialTip = true }
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            onDismiss = { }
        ) {
            ConfidentialTipDialogContent(
                title = getString(R.string.chat_confidential_tip_title),
                content = getString(R.string.chat_confidential_tip_content),
                onConfirm = {
                    dialog?.dismiss()
                    onConfirm()
                }
            )
        }
    }

    /**
     * Generate a file name for oversized text attachment
     * Format: File-YYYY-MM-dd-HHmmss.txt
     */
    private fun generateOversizedTextFileName(): String {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        val dateString = dateFormatter.format(Date())
        return "File-$dateString.txt"
    }

    /**
     * Create a text file from the given content and return the file path
     */
    private fun createTextFile(messageId: String, fileName: String, content: String): String? {
        return try {
            val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            filePath
        } catch (e: Exception) {
            L.e { "Failed to create text file: ${e.stackTraceToString()}" }
            null
        }
    }

    /** Sends text with size validation: >10MB blocked, ≥4KB → text-file attachment, <4KB → normal. */
    private fun sendValidatedText(message: String, onSent: (() -> Unit)? = null): Boolean {
        val messageBytes = message.toByteArray(Charsets.UTF_8)
        if (messageBytes.size > MAX_TEXT_FILE_SIZE) {
            ToastUtil.show(getString(R.string.text_file_exceeds_10mb_limit))
            L.w { "Text message exceeds MAX_TEXT_FILE_SIZE (${messageBytes.size} bytes), send blocked." }
            return false
        }

        if (messageBytes.size >= OVERSIZED_TEXT_THRESHOLD) {
            val timeStamp = System.currentTimeMillis()
            val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
            val fileName = generateOversizedTextFileName()
            val truncatedText = message.utf8Substring(OVERSIZED_TEXT_BODY_LENGTH)

            L.i { "Text message oversized (${messageBytes.size} bytes), converting to file attachment. Body truncated to $OVERSIZED_TEXT_BODY_LENGTH bytes." }

            viewLifecycleOwner.lifecycleScope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    createTextFile(messageId, fileName, message)
                }

                if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@launch
                }

                if (filePath != null) {
                    sendTextPush(
                        content = truncatedText,
                        timeStamp = timeStamp,
                        messageId = messageId,
                        attachmentInfo = AttachmentInfo(
                            filePath = filePath,
                            fileName = fileName,
                            mimeType = CONTENT_TYPE_LONG_TEXT,
                            isAudioMessage = false
                        )
                    )
                    onSent?.invoke()
                } else {
                    L.e { "Failed to create text file: file creation exception" }
                    ToastUtil.show(getString(R.string.chat_status_fail))
                }
            }
        } else {
            sendTextPush(message)
            onSent?.invoke()
        }
        return true
    }

    /**
     * Unified method to send text messages with optional attachment
     * @param content Text content (can be null for attachment-only messages)
     * @param timeStamp Message timestamp
     * @param messageId Message ID (auto-generated if not provided)
     * @param attachmentInfo Attachment information (null for text-only messages)
     */
    private fun sendTextPush(
        content: String? = null,
        timeStamp: Long = System.currentTimeMillis(),
        messageId: String = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}",
        attachmentInfo: AttachmentInfo? = null,
        screenShot: ScreenShot? = null
    ) {
        val forWhat = if (isGroup) For.Group(chatViewModel.forWhat.id) else For.Account(chatViewModel.forWhat.id)

        // Mentions live in `content`; attachment-only sends must skip them to avoid phantom @-notifications.
        val hasContent = !content.isNullOrEmpty()

        var atPersonsString: String? = null
        if (hasContent && mentions.isNotEmpty()) {
            val atPersons = StringBuilder()
            mentions.forEach { mention ->
                atPersons.append(mention.uid)
                atPersons.append(";")
            }
            atPersonsString = if (atPersons.isNotEmpty()) atPersons.substring(0, atPersons.length - 1) else null
        }

        // Use coroutines to handle both attachment and text-only messages uniformly
        viewLifecycleOwner.lifecycleScope.launch {
            // Create attachment if attachmentInfo is provided, otherwise null
            val attachment = attachmentInfo?.let { info ->
                withContext(Dispatchers.IO) {
                    val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(info.filePath, info.mimeType)
                    val fileSize = FileUtils.getLength(info.filePath)

                    Attachment(
                        messageId,
                        0,
                        info.mimeType,
                        "".toByteArray(),
                        fileSize.toInt(),
                        "".toByteArray(),
                        "".toByteArray(),
                        info.fileName,
                        if (info.isAudioMessage) 1 else 0,
                        mediaWidthAndHeight.first,
                        mediaWidthAndHeight.second,
                        info.filePath,
                        AttachmentStatus.LOADING.code
                    )
                }
            }

            // Build and send TextMessage
            // Force mode to 0 when confidential is hidden (group limit or bot)
            val messageMode = if (shouldHideConfidential) 0 else (chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0)
            val textMessage = TextMessage(
                messageId,
                For.Account(globalServices.myId),
                forWhat,
                timeStamp,
                timeStamp,
                System.currentTimeMillis(),
                SendType.Sending.rawValue,
                chatSettingViewModel.getMessageExpirySeconds(),
                0,
                0,
                messageMode,
                content ?: "",
                if (attachment != null) mutableListOf(attachment) else null,
                quote,
                forwardContext,
                recall,
                if (hasContent) mentions.toMutableList() else mutableListOf(),
                atPersonsString,
                reactions.toMutableList(),
                screenShot,
                sharedContacts.toMutableList(),
                playStatus = if (attachmentInfo?.isAudioMessage == true) AudioMessageManager.PLAY_STATUS_NOT_PLAY else 0
            )

            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))

            if (reactions.isEmpty() && recall == null) {
                chatViewModel.addOneMessage(textMessage)
            }

            checkAndSendAddFriendRequest()

            resetData(clearInputBoundState = hasContent)
        }
    }

    /** Returns false (and shows a toast) when the user is restricted by group speak permission. */
    private fun checkCanSpeak(): Boolean {
        if (!isGroup) return true
        val group = chatUIData?.group ?: return true
        if (!groupUtil.canSpeak(group, globalServices.myId)) {
            ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
            return false
        }
        return true
    }

    /**
     * chative检查是否需要发送好友申请
     */
    private fun checkAndSendAddFriendRequest() {
        if (!isGroup && !isFriend) {
            var sourceType: String? = null
            var source: String? = null
            if (requireActivity() is ChatActivity) {
                (requireActivity() as ChatActivity).intent?.let {
                    sourceType = it.sourceType
                    source = it.source
                }
            }
            requestAddFriend(sourceType, source)
        }
    }

    private fun requestAddFriend(
        sourceType: String? = null,
        source: String? = null,
        action: String? = null,
        showLoading: Boolean = false
    ) {
        if (showLoading) {
            ComposeDialogManager.showWait(requireActivity(), "")
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ContactorUtil.fetchAddFriendRequest(requireContext(), (globalServices.userManager.getUserData()?.microToken ?: ""), chatViewModel.forWhat.id, sourceType, source, action)
                }
                ComposeDialogManager.dismissWait()
                if (response.status == 0) {
                    if (action == "accept") {
                        isFriend = true
                        updateSubmitButtonView()
                        updateBottomView()
                        ContactorUtil.emitFriendStatusUpdate(chatViewModel.forWhat.id, true)
                    }
                } else {
                    response.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ComposeDialogManager.dismissWait()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }


    private fun resetData(clearInputBoundState: Boolean = true) {
        clearQuoteThumbnail()               // clear thumbnail ImageView (does NOT hide quoteZone itself)

        quote = null
        forwardContext = null
        recall = null
        reactions.clear()
        sharedContacts.clear()

        binding.quoteZone.visibility = View.GONE

        // Preserve mentions / length tracker when input text is kept (attachment-only sends).
        if (clearInputBoundState) {
            mentionsSelectedContacts.clear()
            mentions.clear()
            prevInputTextLength = 0
        }
    }

    private fun prepareSendAttachmentPush(
        attachmentUri: Uri?,
        mimeType: String,
        originalFileName: String? = null,
        isAudioMessage: Boolean = false
    ) {
        attachmentUri ?: return

        val timeStamp = System.currentTimeMillis()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
        val fileName = originalFileName ?: FileUtils.getFileName(attachmentUri.path)
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    FileUtils.copy(attachmentUri.path, filePath)
                }
                FileUtil.deleteTempFile(FileUtils.getFileName(attachmentUri.path))
                sendTextPush(
                    timeStamp = timeStamp,
                    messageId = messageId,
                    attachmentInfo = AttachmentInfo(
                        filePath = filePath,
                        fileName = fileName,
                        mimeType = mimeType,
                        isAudioMessage = isAudioMessage
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatMessageInputFragment] prepareSendAttachmentPush error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun sendEmojiReaction(emojiReactionEvent: EmojiReactionEvent) {
        val myId = globalServices.myId
        val sourceDeviceId = emojiReactionEvent.message.id.lastOrNull().toString().toInt()

        val timeStamp = System.currentTimeMillis()
        val reaction = Reaction(
            emojiReactionEvent.emoji,
            myId,
            remove = emojiReactionEvent.remove,
            originTimestamp = timeStamp,
            realSource = RealSource(emojiReactionEvent.message.authorId, sourceDeviceId, emojiReactionEvent.message.timeStamp, emojiReactionEvent.message.systemShowTimestamp)
        )

        sendReactionPush(reaction, timeStamp)

        val forWhat = chatViewModel.forWhat
        lifecycleScope.launch(Dispatchers.IO) {
            ApplicationDependencies.getMessageStore().updateMessageReaction(forWhat.id, reaction, null, null)
        }
    }

    private fun sendReactionPush(reaction: Reaction, timeStamp: Long) {
        val forWhat = chatViewModel.forWhat
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
        val textMessage = buildReactionTextMessage(reaction, timeStamp, messageId, forWhat)

        val realMessageId = reaction.realSource?.mapToMessageId()?.idValue
        if (realMessageId == null) {
            L.w { "[Reaction] realMessageId=null, bypassing dedupe target=${forWhat.id} emoji=${reaction.emoji}" }
            ApplicationDependencies.getJobManager().add(pushReactionSendJobFactory.create(null, textMessage))
            return
        }

        reactionSendCoordinator.enqueueReactionWithDedupe(
            conversationId = forWhat.id,
            realMessageId = realMessageId,
            reaction = reaction,
            textMessage = textMessage,
            factory = pushReactionSendJobFactory,
        )
    }

    private fun buildReactionTextMessage(
        reaction: Reaction,
        timeStamp: Long,
        messageId: String,
        forWhat: For,
    ): TextMessage = TextMessage(
        messageId,
        For.Account(globalServices.myId),
        forWhat,
        timeStamp,
        timeStamp,
        System.currentTimeMillis(),
        SendType.Sending.rawValue,
        chatSettingViewModel.getMessageExpirySeconds(),
        0,
        0,
        0,
        "",
        null,
        null,
        null,
        null,
        null,
        null,
        mutableListOf(reaction),
        null,
        null,
    )

    private fun recallMessage(messageID: String) {
        ComposeDialogManager.showWait(requireActivity(), cancelable = false)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Start listening for recall result before submitting job
                var resultReceived = false
                val resultJob = launch {
                    RecallResultTracker.recallResults.collect { result ->
                        if (result.messageId == messageID && !resultReceived) {
                            resultReceived = true
                            if (!result.success) {
                                ToastUtil.show(R.string.operation_failed)
                            }
                            ComposeDialogManager.dismissWait()
                        }
                    }
                }

                val originMessage = withContext(Dispatchers.IO) {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))
                }
                if (originMessage == null) {
                    L.w { "Recall message failed, original message not found: $messageID" }
                    resultJob.cancel()
                    ComposeDialogManager.dismissWait()
                    ToastUtil.show(R.string.operation_failed)
                    return@launch
                }
                if (originMessage.fromWho != globalServices.myId) {
                    L.w { "Recall message failed, the sender does not match, realSource:${originMessage.fromWho}" }
                    resultJob.cancel()
                    ComposeDialogManager.dismissWait()
                    ToastUtil.show(R.string.operation_failed)
                    return@launch
                }
                val textMessage = TextMessage(
                    id = messageID,
                    fromWho = For.Account(globalServices.myId),
                    forWhat = chatViewModel.forWhat,
                    systemShowTimestamp = System.currentTimeMillis(),
                    timeStamp = System.currentTimeMillis(),
                    receivedTimeStamp = System.currentTimeMillis(),
                    sendType = SendType.Sending.rawValue,
                    expiresInSeconds = originMessage.expiresInSeconds,
                    notifySequenceId = 0,
                    sequenceId = 0,
                    mode = 0,
                    text = null
                )
                val sourceDeviceId = originMessage.id.lastOrNull().toString().toInt()
                recall = Recall(
                    RealSource(
                        globalServices.myId,
                        sourceDeviceId,
                        originMessage.timeStamp,
                        originMessage.systemShowTimestamp
                    )
                )
                textMessage.recall = recall
                ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))

                // Wait for result with timeout (30 seconds max)
                val timeoutMs = 30_000L
                val startTime = System.currentTimeMillis()
                while (!resultReceived && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    delay(100)
                }

                resultJob.cancel()

                // If timeout without receiving result, dismiss loading
                if (!resultReceived) {
                    ComposeDialogManager.dismissWait()
                }

                recall = null
            } catch (e: Exception) {
                L.e { "Recall message failed: ${e.stackTraceToString()}" }
                ComposeDialogManager.dismissWait()
                ToastUtil.show(R.string.operation_failed)
                recall = null
            }
        }
    }

    private fun resendMessage(messageID: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))?.convertToTextMessage()
                }
                message?.let {
                    ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, it))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatMessageInputFragment] sendTextPush error: ${e.stackTraceToString()}" }
            }
        }
    }

    private fun createPictureSelector() {
        ScreenLockUtil.temporarilyDisabled = true
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofAll())
            .setDefaultLanguage(LanguageConfig.ENGLISH)
            .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
            .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
            .setImageEngine(GlideEngine.createGlideEngine())
            .isWithSelectVideoImage(true)
            .isGif(true)
            .setMaxSelectNum(9)
            .setMaxVideoSelectNum(9)
            .isOriginalSkipCompress(true)
            .setSelectionMode(SelectModeConfig.MULTIPLE)
//                .setCompressEngine(ImageFileCompressEngine())
            .forResult(pictureSelectorLauncher)
    }

    private fun onPicturePermissionForMessageResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onPicturePermissionForMessageResult: Denied" }
                ToastUtils.showToast(requireContext(), getString(R.string.not_granted_necessary_permissions))
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onPicturePermissionForMessageResult: Granted" }
                createPictureSelector()
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onPicturePermissionForMessageResult: PermanentlyDenied" }
                ComposeDialogManager.showMessageDialog(
                    context = requireActivity(),
                    title = getString(R.string.tip),
                    message = getString(R.string.no_permission_picture_tip),
                    confirmText = getString(R.string.notification_go_to_settings),
                    cancelText = getString(R.string.notification_ignore),
                    cancelable = false,
                    onConfirm = {
                        PermissionUtil.launchSettings(requireContext())
                    },
                    onCancel = {
                        ToastUtils.showToast(
                            requireContext(), getString(R.string.not_granted_necessary_permissions)
                        )
                    }
                )
            }
        }
    }

    private fun changeAttachmentStatus(
        forward: Forward
    ) {
        forward.attachments?.map {
            it.status = AttachmentStatus.LOADING.code
        }

        forward.forwards?.forEach {
            changeAttachmentStatus(it)
        }
    }

    private fun handleFilePaste(uri: Uri, mimeType: String) {
        if (!isGroup && !isFriend) {
            ToastUtil.show(R.string.contact_non_friend_text_only)
            return
        }
        // Handle file paste - similar to fileActivityLauncher callback
        viewLifecycleOwner.lifecycleScope.launch {
            // 优先判断文件大小是否超过200MB
            val fileSize = withContext(Dispatchers.IO) { FileUtil.getFileSize(uri) }
            if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                return@launch
            }

            val copyResult = withContext(Dispatchers.IO) {
                runCatching { FileUtil.copyUriToFile(uri) }
                    .onFailure { L.e { "copyUriToFile failed: ${it.stackTraceToString()}" } }
                    .getOrNull()
            }

            if (copyResult == null) {
                ToastUtil.showLong(R.string.file_unavailable)
                return@launch
            }

            if (!isAdded || view == null) return@launch

            val path = copyResult.tempFile.absolutePath
            val originalFileName = copyResult.originalFileName

            if (MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)) {
                val localMedia = LocalMedia().apply {
                    this.realPath = path
                    this.mimeType = mimeType
                    this.fileName = originalFileName
                }
                val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                    putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, arrayListOf(localMedia))
                    putExtra(MediaSelectionActivity.EXTRA_CONFIDENTIAL_MODE, chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0)
                    putExtra(MediaSelectionActivity.EXTRA_SHOW_CONFIDENTIAL_TOGGLE, !shouldHideConfidential)
                    putExtra(MediaSelectionActivity.EXTRA_CONVERSATION_ID, chatViewModel.forWhat.id)
                }
                mediaSelectActivityLauncher.launch(intent)
            } else {
                val filePreSendIntent = FilePreSendActivity.createIntent(
                    context = requireContext(),
                    filePath = path,
                    fileName = originalFileName ?: "",
                    mimeType = mimeType,
                    fileSize = fileSize,
                    confidentialMode = chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0,
                    showConfidentialToggle = !shouldHideConfidential,
                    conversationId = chatViewModel.forWhat.id
                )
                filePreSendLauncher.launch(filePreSendIntent)
            }
        }
    }

    // IME stickers/GIFs (Gboard, Sogou, etc.) send directly — they are emoji-like, so they skip the
    // image editor preview (which also can't keep animated WebP/GIF moving) and post the raw file as-is.
    private fun handleStickerCommit(info: InputContentInfoCompat, mimeType: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            // try/finally guarantees the temporary IME read grant is released on every exit path.
            try {
                if (!isGroup && !isFriend) {
                    ToastUtil.show(R.string.contact_non_friend_text_only)
                    return@launch
                }
                // Same muted-member gate every other send path uses.
                if (!checkCanSpeak()) return@launch
                // We only advertise image/* to the IME; ignore anything else a keyboard might commit.
                if (!MediaUtil.isImageType(mimeType)) {
                    L.w { "[ChatMessageInputFragment] sticker commit ignored, non-image mimeType=$mimeType" }
                    return@launch
                }

                val fileSize = withContext(Dispatchers.IO) { FileUtil.getFileSize(info.contentUri) }
                if (!isAdded || view == null) return@launch
                if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                    ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                    return@launch
                }

                val copyResult = withContext(Dispatchers.IO) {
                    runCatching { FileUtil.copyUriToFile(info.contentUri) }
                        .onFailure { L.e { "[ChatMessageInputFragment] sticker copyUriToFile failed: ${it.stackTraceToString()}" } }
                        .getOrNull()
                }

                if (copyResult == null) {
                    ToastUtil.showLong(R.string.unsupported_file_type)
                    return@launch
                }

                if (!isAdded || view == null) return@launch

                // The committed file name comes from an untrusted IME content provider; drop it if unsafe
                // (the temp file is UUID-named) so it can't influence the destination attachment path.
                val safeFileName = copyResult.originalFileName?.takeIf { FileUtil.isFileNameValid(it) }
                prepareSendAttachmentPush(copyResult.tempFile.toUri(), mimeType, safeFileName)
            } finally {
                runCatching { info.releasePermission() }
            }
        }
    }

    /**
     * Send a screenshot notification message.
     * Called when a screenshot is detected.
     */
    private fun sendScreenshotNotification() {
        val timeStamp = System.currentTimeMillis()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
        val screenShot = ScreenShot(RealSource(globalServices.myId, 1, timeStamp, timeStamp))
        // Set messageText for display in chat list preview and message bubble
        val screenshotText = getString(R.string.chat_took_a_screen_shot, getString(R.string.you))
        sendTextPush(
            content = screenshotText,
            timeStamp = timeStamp,
            messageId = messageId,
            screenShot = screenShot
        )
    }

    override fun onResume() {
        super.onResume()
        // Initialize screenshot detector if not already done
        if (screenshotDetector == null) {
            screenshotDetector = ScreenshotDetector(
                activity = requireActivity(),
                coroutineScope = viewLifecycleOwner.lifecycleScope,
                onScreenshotDetected = {
                    val focusLostAt = (requireActivity() as? BaseActivity)?.windowFocusLostAt ?: 0L
                    val focusLostDuration = if (focusLostAt > 0L) System.currentTimeMillis() - focusLostAt else 0L
                    if (focusLostAt > 0L && focusLostDuration >= SCREENSHOT_NOTIFICATION_PANEL_THRESHOLD_MS) {
                        L.i { "[ChatMessageInputFragment][Screenshot] Skipped: focus lost ${focusLostDuration}ms ago (notification panel open)" }
                    } else {
                        L.i { "[ChatMessageInputFragment][Screenshot] Screenshot detected, sending notification" }
                        sendScreenshotNotification()
                    }
                }
            )
        }
        screenshotDetector?.startListening()
    }

    override fun onPause() {
        super.onPause()
        screenshotDetector?.stopListening()
    }

    override fun onDestroyView() {
        // Remove OnGlobalLayoutListener to prevent memory leak
        // The OnAttachStateChangeListener should have already removed it when view detached,
        // but we also try to remove here as a safety measure
        inputLayoutListener?.let { listener ->
            inputLayoutViewTreeObserver?.let { observer ->
                if (observer.isAlive) {
                    observer.removeOnGlobalLayoutListener(listener)
                }
            }
        }
        // Also remove the OnAttachStateChangeListener to prevent it from holding Fragment reference
        inputLayoutAttachListener?.let {
            binding.edittextInput.removeOnAttachStateChangeListener(it)
        }
        inputLayoutListener = null
        inputLayoutViewTreeObserver = null
        inputLayoutAttachListener = null
        updateConfidentialJob?.cancel()
        updateConfidentialJob = null
        panelAnimator?.cancel()
        panelAnimator = null
        super.onDestroyView()
        screenshotDetector?.release()
        screenshotDetector = null
        (parentFragment?.view as? InsetAwareConstraintLayout)?.let { insetLayout ->
            keyboardStateListener?.let { insetLayout.removeKeyboardStateListener(it) }
            // Ensure freeze is released if Fragment is destroyed while panel is open
            insetLayout.releaseKeyboardPaddingFreeze()
        }
        keyboardStateListener = null
    }

    private fun registerKeyboardStateListener() {
        val insetLayout = (parentFragment?.view as? InsetAwareConstraintLayout) ?: return
        keyboardStateListener = object : InsetAwareConstraintLayout.KeyboardStateListener {
            override fun onKeyboardShown() {
                if (!isAdded || view == null) return
                val panelVisible = binding.llChatActions.isVisible
                if (!panelVisible) {
                    hidePanel(animated = false)
                }
                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE
                updateSubmitButtonView()
            }
            override fun onKeyboardHidden() {
                if (!isAdded || view == null) return
                updateSubmitButtonView()
            }
            override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
                if (!isAdded || view == null) return
                val panelVisible = binding.llChatActions.isVisible
                if (isKeyboardVisible && panelVisible) {
                    hidePanel(animated = false)
                    insetLayout.releaseKeyboardPaddingFreeze()
                }
            }
        }.also { insetLayout.addKeyboardStateListener(it) }
    }

    private var panelAnimator: android.animation.ValueAnimator? = null

    /**
     * Show the action panel. Caller MUST set `binding.llChatActions.minHeight` to the
     * desired final height (typically the cached keyboard height) before calling this —
     * `minHeight` is used as the animation target. If `minHeight` is 0, the panel is
     * shown without animation (degraded display, no error).
     */
    private fun showPanel(animated: Boolean = true) {
        panelAnimator?.cancel()
        val panel = binding.llChatActions
        val targetHeight = panel.minHeight
        if (animated && targetHeight > 0) {
            val lp = panel.layoutParams
            lp.height = 0
            panel.layoutParams = lp
            panel.visibility = View.VISIBLE
            panelAnimator = android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                duration = PANEL_ANIM_DURATION
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    lp.height = it.animatedValue as Int
                    panel.layoutParams = lp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        panel.layoutParams = lp
                    }
                })
                start()
            }
        } else {
            panel.layoutParams = panel.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            panel.visibility = View.VISIBLE
        }
    }

    private fun hidePanel(animated: Boolean = true, onEnd: (() -> Unit)? = null) {
        panelAnimator?.cancel()
        val panel = binding.llChatActions
        if (panel.visibility != View.VISIBLE) {
            onEnd?.invoke()
            return
        }
        if (animated) {
            val startHeight = panel.height
            val lp = panel.layoutParams
            panelAnimator = android.animation.ValueAnimator.ofInt(startHeight, 0).apply {
                duration = PANEL_ANIM_DURATION
                interpolator = android.view.animation.AccelerateInterpolator()
                addUpdateListener {
                    lp.height = it.animatedValue as Int
                    panel.layoutParams = lp
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        panel.visibility = View.GONE
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        panel.layoutParams = lp
                        onEnd?.invoke()
                    }
                })
                start()
            }
        } else {
            panel.layoutParams = panel.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
            panel.visibility = View.GONE
            onEnd?.invoke()
        }
    }

    /**
     * Focus the input field and show keyboard.
     * Used when returning from contact detail popup to automatically show keyboard.
     */
    fun focusInputAndShowKeyboard() {
        if (!isAdded || view == null) return
        binding.edittextInput.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        ViewUtil.focusAndShowKeyboard(binding.edittextInput)
    }

}