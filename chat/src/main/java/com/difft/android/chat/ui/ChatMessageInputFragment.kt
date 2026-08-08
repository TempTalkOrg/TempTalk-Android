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
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.integration.webp.WebpHeaderParser
import com.difft.android.PushReactionSendJobFactory
import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RecallResultTracker
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.gif.favorite.collectFavoriteEffects
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.MAX_TEXT_FILE_SIZE
import com.difft.android.chat.common.OVERSIZED_TEXT_BODY_LENGTH
import com.difft.android.chat.common.OVERSIZED_TEXT_THRESHOLD
import com.difft.android.chat.common.SendType
import com.difft.android.chat.compose.CombineForwardBar
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.presend.FilePreSendActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NoticeAggregator
import com.difft.android.chat.jobs.ReactionSendCoordinator
import com.difft.android.chat.message.LocalMessageCreator
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.mention.MentionCandidate
import com.difft.android.chat.mention.MentionCandidateSorter
import com.difft.android.chat.mention.MentionStatsRepository
import com.difft.android.chat.mention.MentionStatsSnapshot
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.config.SelectModeConfig
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import com.difft.android.selector.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.FLAG_GIF
import difft.android.messageserialization.model.isAnimatedImage
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import com.difft.android.chat.mediasend.MediaAttachmentStager
import com.difft.android.chat.mediasend.MediaSendActivityResult
import com.difft.android.chat.mediasend.MediaSendFailureNotice
import com.difft.android.chat.mediasend.v2.MediaSelectionActivity
import com.difft.android.chat.message.getRelevantAttachment
import com.difft.android.chat.util.MediaUtil
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.util.QuoteThumbnailBinder
import com.difft.android.chat.util.isHostActivityAlive
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.chat.util.ViewUtil
import com.difft.android.chat.util.visible
import util.FileSystemUtils
import util.ScreenLockUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
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

    // GIF inline-panel VM, scoped to this input fragment (the full-screen search dialog
    // owns a separate instance so its results never leak into the inline panel).
    private val gifPanelViewModel: com.difft.android.chat.gif.GifPanelViewModel by viewModels()

    // Favorites VM (M3), scoped to the parent (chat) fragment so the long-press "add to favorites"
    // entry in the message list fragment shares the same instance. Init pulls + reconciles.
    private val favoriteViewModel: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    @Inject
    lateinit var gifSendUseCase: com.difft.android.chat.gif.GifSendUseCase

    /**
     * Single source of truth for what occupies the ll_chat_actions container.
     * NONE = collapsed (keyboard or nothing), MORE = more-actions grid, GIF = inline GIF panel.
     * Drives [syncActionButtons], which is the only place that mutates the left-button
     * (more / more-close) visibility and switches the container's sub-content.
     */
    private enum class PanelMode { NONE, MORE, GIF }

    private var panelMode: PanelMode = PanelMode.NONE

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

    @Inject
    internal lateinit var mentionStatsRepository: MentionStatsRepository

    // Per-@-session cache of mention sort stats. Invalidated when a new "@" panel opens;
    // keyword filtering within one session reuses it. Mutex + double-check → one DB load per session.
    // @Volatile: read outside the mutex in ensureMentionStats' double-checked fast path.
    @Volatile
    private var mentionStatsSnapshot: MentionStatsSnapshot? = null
    private val mentionStatsMutex = Mutex()

    // Bumped on each new "@" session so a stale in-flight loadSnapshot can't overwrite the cache.
    private val mentionStatsGeneration = AtomicLong(0)

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
        val isAudioMessage: Boolean = false,
        val isGif: Boolean = false
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

        setupGifPanel()

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
                // Display-only: normalize newlines in the quote preview.
                binding.quoteText.text = text.normalizeNewlines()
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

                syncActionButtons(PanelMode.NONE)

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
                } else {
                    binding.buttonSubmit.visibility = View.GONE
                    binding.buttonVoice.visibility = View.VISIBLE
                    binding.buttonKeyboard.visibility = View.GONE
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
                // Store canonical newlines; mention offsets are re-derived at send.
                content = text?.toString()?.normalizeNewlines(),
                quote = quote,
                mentions = mentions.toList(),
            )
            draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
//            L.i { "======doOnTextChanged======" + text + "===" + start + "===" + before + "===" + count }
            if (!TextUtils.isEmpty(text)) {
                if (isGroup) { //处理@相关的逻辑
                    if (count == 1 && text!!.substring(start, start + 1) == "@") {
                        mentionsSearchKeyStartPos = start
                        mentionsSearchKey = null // new @ session starts with no keyword
                        mentionStatsGeneration.incrementAndGet() // invalidate any in-flight load first
                        mentionStatsSnapshot = null // new @ session → reload stats once
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

        // Photo entry moved into the more-actions grid (M2); same behavior as the former
        // standalone button_media in the input row.
        binding.buttonPhoto.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            // Collapse the keyboard first so both open/request branches behave the same.
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            binding.edittextInput.clearFocus()
            // Open directly when media is already usable (full/partial); else request.
            onPicturePermissionForMessage.launchMediaSelectionOrOpen { createPictureSelector() }
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
            // Normalize newlines before trim so the sent, stored, and wire copies match.
            val message: String = binding.edittextInput.text.toString().normalizeNewlines().trim()
            if (!TextUtils.isEmpty(message)) {
                sendValidatedText(message) {
                    binding.edittextInput.setText("")
                }
            }
        }

        binding.buttonMoreActions.setOnClickListener {
            val root = parentFragment?.view as? InsetAwareConstraintLayout

            if (binding.llChatActions.isVisible) {
                // Panel already on-screen. Switching GIF -> MORE (or refreshing MORE) must ONLY swap
                // content in place; re-running showPanel would replay the 0->height animation and
                // make the panel collapse+re-expand (Issue 1). syncActionButtons(MORE) hides the GIF
                // ComposeView and shows the grid; no re-animation, no re-freeze.
                if (panelMode == PanelMode.MORE) {
                    // Already MORE but behind the keyboard -> just hide keyboard to reveal it.
                    ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
                }
                syncActionButtons(PanelMode.MORE)
            } else {
                // Panel not visible: enter panel mode fresh (animate), freeze padding, hide keyboard.
                val hasKeyboard = ViewCompat.getRootWindowInsets(binding.root)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                root?.freezeKeyboardPadding()
                val keyboardHeight = InsetAwareConstraintLayout.getKeyboardHeight(requireContext())
                if (keyboardHeight > 0) {
                    binding.llChatActions.minHeight = keyboardHeight
                }
                syncActionButtons(PanelMode.MORE)
                showPanel(animated = !hasKeyboard)
                chatViewModel.setVoiceVisibility(false)
                chatViewModel.showChatActions()
                ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            }

            isVoiceMode = false
            updateSubmitButtonView()
        }

        binding.buttonMoreActionsClose.setOnClickListener {
            // Show keyboard to replace panel — keyboard slides up as overlay,
            // panel dismissed in onKeyboardAnimationEnded (same as tapping EditText)
            syncActionButtons(PanelMode.NONE)

            isVoiceMode = false
            updateSubmitButtonView()
            ViewUtil.focusAndShowKeyboard(binding.edittextInput)
        }

        binding.buttonGif.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            openGifPanel()
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
                // Voice is mutually exclusive with any panel: collapse to NONE.
                syncActionButtons(PanelMode.NONE)
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

        updateViewByFriendCheck()

        if (isGroup) {
            binding.buttonAt.visibility = View.VISIBLE
            binding.buttonAt.setOnClickListener {
                insertTextToEdittext("@")

                hidePanel(animated = false)
                syncActionButtons(PanelMode.NONE)
                ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            }
        } else {
            // Group-only: GONE (not INVISIBLE) — button_at now sits mid-row (photo, gif, @, file),
            // so an INVISIBLE placeholder would leave a hole in the middle of the grid. GONE lets
            // the Flow re-spread the remaining 4 entries into the same 4 columns as a group row.
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

                    // Display-only: normalize newlines in the quote preview.
                    binding.quoteText.text = quote.text.normalizeNewlines()
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
        // Re-engage the lock immediately on selector return (OK or cancel both land here).
        ScreenLockUtil.temporarilyDisabled = false
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
                        list.forEachIndexed { index, (media, sendUri) ->
                            // Use original filename, fallback to extracting from original path (not realPath which may be UUID)
                            val fileName = media.fileName.takeIf { !it.isNullOrEmpty() }
                                ?: FileSystemUtils.getFileName(media.path)
                            // sendUri was resolved at the transform boundary; re-deriving it here
                            // would hand back the pre-edit source for every edited item.
                            prepareSendAttachmentPush(sendUri, media.mimeType, fileName)
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
        // Re-engage the lock immediately on picker return (OK or cancel both land here).
        ScreenLockUtil.temporarilyDisabled = false
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
                    this.mimeType = mimeType ?: ""
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
            if (message.attachment?.isAnimatedImage() == true) {
                ResUtils.getString(R.string.chat_message_gif)
            } else if (message.attachment?.isImage() == true) {
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
                    // Type-specific label (gif/image/video/audio) for a forwarded media message,
                    // matching the normal-attachment branch above instead of a generic "[Attachment]".
                    val att = forward.attachments?.firstOrNull()
                    getString(MediaUtil.quoteTypeLabelRes(att?.contentType, att?.flags ?: 0))
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
     * Returns null if the resolved media is not readable on disk (plaintext or ciphertext).
     */
    private fun quoteLocalAttachmentPath(message: TextChatMessage, attachment: Attachment): String? {
        val forwards = message.forwardContext?.forwards
        val dirId = if (forwards?.size == 1) attachment.authorityId.toString() else message.id
        val fileName = attachment.fileName ?: return null
        val path = FileUtil.getMessageAttachmentFilePath(dirId) + fileName
        // isReadable (not File.exists): encrypted-at-rest media keeps only the .encrypt on disk; the
        // loader resolves it to a decrypting content uri via imageGlideModel.
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
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
            QuoteThumbnailBinder.loadRoundedThumbnail(binding.quoteThumbnail, EncryptedAttachmentAccess.imageGlideModel(path))
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

        val set = mentionsSelectedContacts.map(::mentionKeyFor).toHashSet()
        val text = binding.edittextInput.text.toString().trim()

        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.Default) {
                    // Route through the single offset owner so live highlight and send-time
                    // recompute share one source of truth (no behavior change).
                    val computedMentions = mentionOffsets(text, set, ::uidForMentionKey)

                    withContext(Dispatchers.Main) {
                        if (!isAdded || view == null) return@withContext
                        mentions.clear()
                        mentions.addAll(computedMentions)

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

    /**
     * Returns the mention stats snapshot for [roomId], loading it once per @ session.
     * Mutex + double-check so rapid keystrokes share a single DB load. The snapshot is validated
     * by roomId to avoid cross-chat reuse.
     */
    private suspend fun ensureMentionStats(roomId: String): MentionStatsSnapshot {
        mentionStatsSnapshot?.let { if (it.roomId == roomId) return it }
        return mentionStatsMutex.withLock {
            mentionStatsSnapshot?.let { if (it.roomId == roomId) return@withLock it }
            // Capture generation before loading; a concurrent "@" reset bumps it, so a stale
            // in-flight result is still returned to this caller (F1 guard drops its UI) but not cached.
            val gen = mentionStatsGeneration.get()
            mentionStatsRepository.loadSnapshot(roomId, globalServices.myId, System.currentTimeMillis())
                .also { if (mentionStatsGeneration.get() == gen) mentionStatsSnapshot = it }
        }
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
            // Session guard (mirrors loadComposeQuoteThumbnailAsync): capture the @ session identity so a
            // slow DB load can't revive a panel the user already closed / advanced past on completion.
            val sessionStart = mentionsSearchKeyStartPos
            val sessionGen = mentionStatsGeneration.get()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val contacts = mutableListOf<ContactsAtAdapter.Item>()

                runCatching {
                    val roomId = chatViewModel.forWhat.id
                    val members = chatUIData?.group?.members?.convertToContactorModels() ?: emptyList()
                    if (members.isNotEmpty()) {
                        // Stats are an enhancement only: on DB failure degrade to empty stats,
                        // which lands every member in the pinyin-fallback bucket (legacy order).
                        val snapshot = runCatching { ensureMentionStats(roomId) }
                            .onFailure { e ->
                                if (e is CancellationException) throw e
                                L.e { "[Mention] stats load failed, pinyin fallback: ${e.stackTraceToString()}" }
                            }
                            .getOrElse { MentionStatsSnapshot(roomId, System.currentTimeMillis(), emptyMap(), emptyMap()) }
                        val byUid = members.associateBy { it.id }
                        val candidates = members.map { MentionCandidate.from(it) }
                        val ordered = MentionCandidateSorter.sort(
                            candidates = candidates,
                            key = key, // null = "@" only; non-empty = filter + sort
                            now = snapshot.now, // snapshot instant → counts/tier stay consistent
                            mentionStats = snapshot.mentionStats,
                            lastSpeakTime = snapshot.lastSpeakTime,
                        )
                        if (ordered.isNotEmpty()) {
                            contacts.add(ContactsAtAdapter.Item.Title(getString(R.string.chat_at_group_members), getString(R.string.chat_at_tips)))
                            // @all is always pinned first; not keyword-matched.
                            contacts.add(ContactsAtAdapter.Item.Contact(ContactorModel().apply { id = MENTIONS_ALL_ID; name = getString(R.string.chat_at_all) }))
                            ordered.forEach { c -> byUid[c.uid]?.let { contacts.add(ContactsAtAdapter.Item.Contact(it)) } }
                        }
                    }
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    L.e { "[Mention] updateAtView failed: ${e.stackTraceToString()}" }
                    contacts.clear()
                }

                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    // Discard stale results: generation guards against cross-session ABA (same offset
                    // reopened @); startPos guards against a closed panel (close path sets -1 but does
                    // not bump generation); key guards against out-of-order keystrokes in one session.
                    if (mentionStatsGeneration.get() != sessionGen ||
                        mentionsSearchKeyStartPos != sessionStart ||
                        mentionsSearchKey != key
                    ) return@withContext
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

    /**
     * Single owner of "map @display-name key -> mention offsets against [body]".
     * Wraps [findContainedSubstringsWithPositions] so both the live [updateMentions]
     * path and the send-time [mentionsForNormalizedBody] recompute go through one
     * offset source. Offsets are indices into [body] exactly as given, so callers
     * pass the FINAL body (normalized + trimmed at send) to keep start/length aligned
     * after CRLF (2->1) shifts; the other six family members are length-preserving.
     * [uidForKey] resolves the mention uid for a matched key; it defaults to null so
     * unit tests can drive pure offset math without a contact map.
     */
    internal fun mentionOffsets(
        body: String,
        mentionKeys: Set<String>,
        uidForKey: (String) -> String? = { null }
    ): List<Mention> {
        if (mentionKeys.isEmpty()) return emptyList()
        val map = findContainedSubstringsWithPositions(body, HashSet(mentionKeys))
        val rebuilt = hashMapOf<Int, Mention>()
        map.forEach { (key, positions) ->
            val uid = uidForKey(key)
            positions.forEach { pos -> rebuilt[pos.first] = Mention(pos.first, key.length, uid, 0) }
        }
        return rebuilt.values.toList()
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

            if (isFriend || chatViewModel.forWhat.id.isOfficialAccount()) {
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
        } else if (chatViewModel.forWhat.id.isOfficialAccount() || !isFriend) {
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
        // Single choke point: normalize the newline family to `\n` for EVERY caller
        // (send button, media caption, file caption). Idempotent, so re-normalizing an
        // already-normalized send-button body is harmless. No `.trim()` here: caption
        // callers must keep their raw whitespace behavior.
        val message = message.normalizeNewlines()
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
     * Re-derives mention offsets against the FINAL body sent to [sendTextPush] (already
     * normalized at Edit A, possibly truncated in the oversized branch). Running the
     * single offset owner [mentionOffsets] against that exact body makes CRLF (2->1)
     * shifts correct by construction and drops any mention whose substring fell outside
     * a truncated body, so an out-of-bounds offset can never be produced.
     */
    private fun mentionsForNormalizedBody(body: String): MutableList<Mention> {
        if (mentions.isEmpty()) return mutableListOf()
        val keys = mentionsSelectedContacts.map(::mentionKeyFor).toHashSet()
        return mentionOffsets(body, keys, ::uidForMentionKey).toMutableList()
    }

    /** The `@display-name` key used to locate a selected contact's mention in body text. */
    private fun mentionKeyFor(contact: ContactorModel): String = "@" + contact.getDisplayNameWithoutRemarkForUI()

    /** Resolves a mention key (as built by [mentionKeyFor]) back to its contact uid. */
    private fun uidForMentionKey(key: String): String? =
        mentionsSelectedContacts.find { mentionKeyFor(it) == key }?.id

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

        // Offset-carrying mentions must match the final (normalized, possibly truncated) body,
        // so rebuild them from `content` via the single offset owner. `atPersonsString` below
        // reads only mention.uid (offset-independent) and therefore keeps the original `mentions`.
        val normalizedMentions = if (hasContent) mentionsForNormalizedBody(content ?: "") else mutableListOf()

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
                    val fileSize = FileSystemUtils.getLength(info.filePath)

                    Attachment(
                        messageId,
                        0,
                        info.mimeType,
                        "".toByteArray(),
                        fileSize.toInt(),
                        "".toByteArray(),
                        "".toByteArray(),
                        info.fileName,
                        // Mutually exclusive: gif marks the GIF bit, voice keeps 1, else 0.
                        if (info.isGif) FLAG_GIF else if (info.isAudioMessage) 1 else 0,
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
                normalizedMentions,
                atPersonsString,
                reactions.toMutableList(),
                screenShot,
                sharedContacts.toMutableList(),
                playStatus = if (attachmentInfo?.isAudioMessage == true) AudioMessageManager.PLAY_STATUS_NOT_PLAY else 0
            )

            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))

            // Optimistic add so the sent message renders instantly. Recall/reaction add no bubble.
            if (reactions.isEmpty() && recall == null) {
                // Drop the NEW MESSAGES divider for the rest of this page session, but ONLY when the
                // user actually composed something (iOS clearUnreadMessagesIndicator hooks the
                // input-toolbar send path only). A non-null screenShot means this call came from
                // sendScreenshotNotification(), which fires automatically on screenshot detection —
                // that must not consume the divider. Recalls and reactions are already excluded by
                // the enclosing guard; they take a different, bubble-less path.
                //
                // Called BEFORE the optimistic add so the emission it triggers already sees the
                // cleared state instead of rendering the divider one last time.
                if (screenShot == null) {
                    chatViewModel.clearNewMessageDivider()
                }
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

    // ==================== GIF panel (M1) ====================

    /**
     * Wire the inline GIF panel ComposeView, collect its SendGif effect, and listen for the
     * full-screen search dialog's result. Called once from onViewCreated.
     */
    private fun setupGifPanel() {
        binding.gifPanelCompose.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.gifPanelCompose.setContent {
            com.difft.android.base.ui.theme.DifftTheme {
                com.difft.android.chat.gif.compose.GifInlinePanel(
                    viewModel = gifPanelViewModel,
                    favoriteViewModel = favoriteViewModel,
                    onOpenSearch = { openGifSearchDialog() },
                    onPickFavorite = { item -> onFavoritePicked(item) }
                )
            }
        }

        // Inline-panel pick -> send the resolved gif.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gifPanelViewModel.effect.collect { effect ->
                    when (effect) {
                        is com.difft.android.chat.gif.GifPanelContract.Effect.SendGif ->
                            onGifPicked(effect.uri)
                        is com.difft.android.chat.gif.GifPanelContract.Effect.ShowError -> {
                            // Surface a transient toast (e.g. a load-more/append failure) so it isn't
                            // silent; the in-panel "couldn't load" label covers the first-page case.
                            L.w { "[ChatMessageInputFragment] gif inline effect error" }
                            ToastUtil.show(R.string.gif_load_failed)
                        }
                        is com.difft.android.chat.gif.GifPanelContract.Effect.FavoriteRemote ->
                            // Long-press add-to-favorites (Issue 5): forward the raw item to
                            // FavoriteViewModel as FromRemote — the placeholder + toast are instant
                            // and the download + trans-store + CAS PUT run in the background.
                            favoriteViewModel.dispatch(
                                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.Favorite(
                                    com.difft.android.chat.gif.favorite.FavoriteSource.FromRemote(
                                        effect.giphyId, effect.previewUrl, effect.width, effect.height
                                    )
                                )
                            )
                    }
                }
            }
        }

        // Favorites effects (cap dialog / toast).
        collectFavoriteEffects(favoriteViewModel)

        // Full-screen search dialog result -> send the picked gif.
        childFragmentManager.setFragmentResultListener(
            com.difft.android.chat.gif.GifSearchDialogFragment.RESULT_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            val pick = androidx.core.os.BundleCompat.getParcelable(
                bundle,
                com.difft.android.chat.gif.GifSearchDialogFragment.RESULT_PICK,
                com.difft.android.chat.gif.GifSearchDialogFragment.GifPick::class.java
            ) ?: return@setFragmentResultListener
            onGifPicked(pick.uri.toUri())
        }
    }

    private fun openGifSearchDialog() {
        if (childFragmentManager.findFragmentByTag(
                com.difft.android.chat.gif.GifSearchDialogFragment.TAG
            ) != null
        ) {
            return
        }
        com.difft.android.chat.gif.GifSearchDialogFragment.newInstance()
            .show(childFragmentManager, com.difft.android.chat.gif.GifSearchDialogFragment.TAG)
    }

    /**
     * Send a resolved gif Uri as an image/webp attachment (v2 sends webp), then collapse the panel.
     * Width/height ride the SendGif effect for forward use but are not needed here:
     * the attachment pipeline derives dimensions from the file itself.
     */
    private fun onGifPicked(uri: Uri) {
        // Known-animated GIPHY webp — mark it directly, no header inspection needed.
        prepareSendAttachmentPush(uri, "image/webp", "gif_${System.currentTimeMillis()}.webp", isGif = true)
        hideGifPanel()
    }

    /**
     * Send a favorited gif. Resolve the decrypted file by fileHash the same way the grid cell does
     * (cache hit → no network, else download + decrypt) rather than trusting [item.localPath], which
     * is absent once a row is rebuilt from the server blob (e.g. after a fresh sync / rewrap) — that
     * was silently throwing and doing nothing.
     */
    private fun onFavoritePicked(item: com.difft.android.chat.gif.favorite.FavoriteGifUiItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Branch on the TYPED pending source (not the dead sourceUrl column): Remote pending ->
                // send from the preview URL (works before the ciphertext exists); Message pending -> send
                // from LOCAL message bytes only (no network), same source the grid cell renders; confirmed
                // (None) -> resolve the decrypting content:// uri by fileHash. Every unresolvable path shows
                // a failure toast — never a silent no-op send.
                val input = when (val src = item.pendingSource) {
                    is com.difft.android.chat.gif.favorite.PendingSource.Remote ->
                        com.difft.android.chat.gif.GifSendInput.FromUrl(src.previewUrl, item.width, item.height)
                    is com.difft.android.chat.gif.favorite.PendingSource.Message -> {
                        val uri = com.difft.android.chat.gif.favorite.resolveMessageUri(src)
                        if (uri == null) {
                            L.w { "[ChatMessageInputFragment] favorite pick: message bytes gone msgId=${src.messageId}" }
                            ToastUtil.show(R.string.gif_favorites_failed)
                            return@launch
                        }
                        com.difft.android.chat.gif.GifSendInput.FromFavorite(uri.toString(), item.width, item.height)
                    }
                    com.difft.android.chat.gif.favorite.PendingSource.None -> {
                        val contentUri = favoriteViewModel.resolveGif(item.fileHash)
                        if (contentUri == null) {
                            L.w { "[ChatMessageInputFragment] favorite pick: resolve failed hash=${item.fileHash}" }
                            ToastUtil.show(R.string.gif_favorites_failed)
                            return@launch
                        }
                        com.difft.android.chat.gif.GifSendInput.FromFavorite(contentUri.toString(), item.width, item.height)
                    }
                }
                val uri = gifSendUseCase.resolveSendable(input)
                onGifPicked(uri)
            } catch (e: Exception) {
                L.w { "[ChatMessageInputFragment] favorite pick send failed: ${e.stackTraceToString()}" }
                ToastUtil.show(R.string.gif_favorites_failed)
            }
        }
    }

    /**
     * Open the inline GIF panel. Mutually exclusive with the keyboard and the more-actions
     * grid: shows the GIF ComposeView (hiding the grid Flow) inside the shared ll_chat_actions
     * container, mirroring the more-actions panel's freeze/showPanel/hideKeyboard handshake.
     * Never called while the GIF panel is showing — its only entry (the grid GIF item) is
     * hidden in GIF mode; dismissal goes through "+" (back to the grid) or the keyboard paths.
     */
    private fun openGifPanel() {
        // Deferred initial trending load: fire only when the panel is shown, not on VM
        // creation (conversation open). Idempotent across re-opens.
        gifPanelViewModel.onPanelShown()

        // Flush-trigger gap: OpenFavorites (-> ensureKeyAndReconcile -> flushPendingFavorites) is only
        // dispatched on a tab-CHANGE to favorites (LaunchedEffect in GifInlinePanel). Re-opening the
        // panel while ALREADY on favorites retains the composition, so it never re-dispatches and a
        // pending row from an earlier offline favorite/unfavorite never retries until an app restart.
        // Re-dispatch here on every panel-show while on favorites (idempotent, offline-safe).
        if (gifPanelViewModel.state.value.currentTab ==
            com.difft.android.chat.gif.GifPanelContract.GifTab.FAVORITES
        ) {
            favoriteViewModel.dispatch(
                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.OpenFavorites
            )
        }

        if (binding.llChatActions.isVisible) {
            // Panel already on-screen (switching MORE -> GIF): ONLY swap content in place; do not
            // re-run showPanel (it would replay the 0->height animation and jump). syncActionButtons
            // (via setActionContentMode) re-applies the GIF ComposeView keyboard-height bound (Issue 1).
            syncActionButtons(PanelMode.GIF)
        } else {
            // Panel not visible: enter GIF panel mode fresh (animate when no keyboard to displace).
            val root = parentFragment?.view as? InsetAwareConstraintLayout
            val hasKeyboard = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            root?.freezeKeyboardPadding()
            // Use a fallback height when the IME height isn't known yet (keyboard never shown this
            // session) so the panel is bounded — otherwise the GIF lazy grid fills the whole screen.
            binding.llChatActions.minHeight = gifPanelHeightPx()
            syncActionButtons(PanelMode.GIF)
            showPanel(animated = !hasKeyboard)
            chatViewModel.setVoiceVisibility(false)
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
        }

        isVoiceMode = false
        updateSubmitButtonView()
    }

    private fun hideGifPanel() {
        if (panelMode != PanelMode.GIF) return
        hidePanel {
            (parentFragment?.view as? InsetAwareConstraintLayout)?.releaseKeyboardPaddingFreeze()
        }
        // Restore the GIF ComposeView's default height so the shared ll_chat_actions container and
        // the more-actions grid path are unaffected by the keyboard-height bound set on show.
        binding.gifPanelCompose.updateLayoutParams {
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        syncActionButtons(PanelMode.NONE)
    }

    /**
     * Single point that mutates the left action buttons and the ll_chat_actions sub-content for
     * the given [mode]. Replaces the previously scattered direct `buttonMoreActions`/
     * `buttonMoreActionsClose` visibility writes and the M1 `setGifPanelContentVisible` helper,
     * so the panel state never drifts across the keyboard / list-tap / voice / mention / send
     * dismiss paths.
     *
     * - NONE: collapsed. more->VISIBLE, moreClose->GONE, content reset to the grid (so a fresh
     *   more-actions tap shows the grid, not a stale GIF panel).
     * - MORE: more-actions grid. more->GONE, moreClose->VISIBLE, grid content shown.
     * - GIF:  inline GIF panel. more->VISIBLE, moreClose->GONE so the user can tap "+" to switch
     *   back from the GIF panel to the more-actions grid (where the GIF entry now lives). Tapping
     *   "+" routes through the existing MORE path (onClickMoreActions), which sets PanelMode.MORE
     *   and swaps the content cleanly.
     */
    private fun syncActionButtons(mode: PanelMode) {
        panelMode = mode
        when (mode) {
            PanelMode.NONE -> {
                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE
                setActionContentMode(gif = false)
            }
            PanelMode.MORE -> {
                binding.buttonMoreActions.visibility = View.GONE
                binding.buttonMoreActionsClose.visibility = View.VISIBLE
                setActionContentMode(gif = false)
            }
            PanelMode.GIF -> {
                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE
                setActionContentMode(gif = true)
            }
        }
    }

    /**
     * Switch ll_chat_actions content between the GIF panel ([gif] = true) and the more-actions
     * grid ([gif] = false). The grid is driven by the Flow's referenced ids, so the Flow and all
     * grid items toggle together against the GIF ComposeView.
     *
     * Owns the GIF ComposeView fixed height so an IN-PLACE swap into GIF (from a visible MORE panel)
     * still bounds the grid to the keyboard slot (Issue 1): on [gif] = true it (re)applies
     * keyboardHeight minus the container's top+bottom padding (the ComposeView sits inside both, so
     * the panel total still equals keyboardHeight); on [gif] = false it resets to WRAP_CONTENT so the
     * more-grid / collapsed paths are unaffected.
     */
    /**
     * Height (px) for the GIF panel. Uses the measured IME height when known; otherwise falls back to
     * a sane default so the panel's lazy grid is bounded instead of filling the screen (the IME height
     * is unavailable until the keyboard has been shown at least once this session).
     */
    private fun gifPanelHeightPx(): Int {
        val kb = InsetAwareConstraintLayout.getKeyboardHeight(requireContext())
        return if (kb > 0) kb else (280 * resources.displayMetrics.density).toInt()
    }

    private fun setActionContentMode(gif: Boolean) {
        binding.gifPanelCompose.visibility = if (gif) View.VISIBLE else View.GONE
        if (gif) {
            val verticalPadding = binding.llChatActions.paddingTop + binding.llChatActions.paddingBottom
            binding.gifPanelCompose.updateLayoutParams {
                // Always bind an explicit height (with fallback when the IME height is unknown) so the
                // lazy grid is bounded; otherwise the GIF panel fills the whole screen.
                height = (gifPanelHeightPx() - verticalPadding).coerceAtLeast(0)
            }
        } else {
            binding.gifPanelCompose.updateLayoutParams {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
        val gridVisibility = if (gif) View.GONE else View.VISIBLE
        binding.flow.visibility = gridVisibility
        binding.buttonPhoto.visibility = gridVisibility
        binding.buttonGif.visibility = gridVisibility
        binding.buttonContact.visibility = gridVisibility
        binding.buttonAttachment.visibility = gridVisibility
        // @-mention is group-only: GONE in a single chat so the Flow re-spreads the remaining
        // entries instead of leaving a mid-row hole (button_at sits between gif and attachment).
        binding.buttonAt.visibility = if (!gif && isGroup) View.VISIBLE else View.GONE
    }

    /**
     * @param isGif set when the caller already knows the attachment is an animated gif (e.g. the
     * GIPHY picker). When false and the mime is an image type, the just-copied plaintext file header
     * is inspected off the main thread to auto-detect an animated gif/webp, so gallery/camera/sticker
     * image sends carry the GIF flag too.
     */
    private fun prepareSendAttachmentPush(
        attachmentUri: Uri?,
        mimeType: String,
        originalFileName: String? = null,
        isAudioMessage: Boolean = false,
        isGif: Boolean = false
    ) {
        attachmentUri ?: return

        val timeStamp = System.currentTimeMillis()
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
        val fileName = originalFileName ?: FileSystemUtils.getFileName(attachmentUri.path)
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val staged = withContext(Dispatchers.IO) {
                    MediaAttachmentStager.stage(requireContext(), attachmentUri, filePath, mimeType, originalFileName)
                }
                if (staged is MediaAttachmentStager.StageResult.Failed) {
                    // Do NOT enqueue: the previous code sent anyway and the upload then retried
                    // against a file that was never created.
                    MediaSendFailureNotice.showStagingFailure(requireContext(), staged.failure)
                    return@launch
                }
                val isAnimatedImage = withContext(Dispatchers.IO) {
                    // Known-gif callers skip inspection; otherwise auto-detect on image sends only.
                    if (isGif) true
                    else if (MediaUtil.isImageType(mimeType)) detectAnimatedImage(filePath, mimeType)
                    else false
                }
                FileUtil.deleteTempFile(FileSystemUtils.getFileName(attachmentUri.path))
                // Our gif send-cache staging file holds decrypted plaintext; delete it once copied into
                // the encrypted attachment dir so it never lingers in cache (deleteTempFile above does
                // not cover the cacheDir/gif_send dir).
                attachmentUri.path?.takeIf { it.contains("/gif_send/") }?.let { runCatching { File(it).delete() } }
                sendTextPush(
                    timeStamp = timeStamp,
                    messageId = messageId,
                    attachmentInfo = AttachmentInfo(
                        filePath = filePath,
                        fileName = fileName,
                        mimeType = mimeType,
                        isAudioMessage = isAudioMessage,
                        isGif = isAnimatedImage
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatMessageInputFragment] prepareSendAttachmentPush error: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * Reads only the file header (never the whole file) to decide whether an outgoing image is an
     * animated gif/webp. `image/gif` is animated by convention; webp is probed with [WebpHeaderParser].
     * MUST be called off the main thread. Detection failures default to false (never blocks the send).
     */
    private fun detectAnimatedImage(filePath: String, mimeType: String): Boolean {
        if (mimeType.trim() == MediaUtil.IMAGE_GIF) return true
        return try {
            val header = ByteArray(WebpHeaderParser.MAX_WEBP_HEADER_SIZE)
            // read() may return fewer bytes than requested even mid-stream; a short first read could
            // truncate the header before the VP8X animation flag and misclassify an animated webp as
            // static. Loop until the buffer is full or EOF.
            val read = File(filePath).inputStream().use { input ->
                var off = 0
                while (off < header.size) {
                    val n = input.read(header, off, header.size - off)
                    if (n < 0) break
                    off += n
                }
                off
            }
            if (read <= 0) return false
            val bytes = if (read == header.size) header else header.copyOf(read)
            WebpHeaderParser.isAnimatedWebpType(WebpHeaderParser.getType(bytes))
        } catch (e: Exception) {
            L.w { "[ChatMessageInputFragment] detectAnimatedImage failed mime=$mimeType: ${e.stackTraceToString()}" }
            false
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
        // Same muted-member gate every other send entry point uses (fail-fast UX; the server is the
        // authoritative check, but without this the user only learns the send failed after the preview flow).
        if (!checkCanSpeak()) return
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
                // Keyboard is mutually exclusive with any panel (MORE or GIF): collapse to NONE.
                syncActionButtons(PanelMode.NONE)
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
                    // Keyboard replaced the panel: reset content to the grid so the next
                    // more-actions tap shows the grid, not a stale GIF panel.
                    syncActionButtons(PanelMode.NONE)
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