package com.difft.android.chat.ui

import com.difft.android.base.utils.pane.PageWait
import android.annotation.SuppressLint
import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
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
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.core.text.getSpans
import androidx.core.view.inputmethod.InputContentInfoCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.difft.android.base.media.AnimatedImageDetector
import com.difft.android.PushReactionSendJobFactory
import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.PushScreenShotSendJobFactory
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMediaSelectionOrOpen
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.RecallResultTracker
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.ui.theme.ChatThemeSetting
import com.difft.android.chat.widget.fileTypeIcon
import com.difft.android.chat.jobs.AttachmentSendIdentity
import com.difft.android.chat.gif.favorite.collectFavoriteEffects
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.ForwardSourceContext
import com.difft.android.chat.attachment.deepCopyWithNewAttachmentIdentities
import com.difft.android.chat.attachment.toForwardCopy
import com.difft.android.chat.message.isConfidential
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
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.UnifiedInputTouchHandler
import com.difft.android.chat.widget.buildHoldToTalkHint
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
import difft.android.messageserialization.model.isPlayableVideo
import difft.android.messageserialization.model.isWhitelistedVideoType
import difft.android.messageserialization.model.mapToMessageId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@AndroidEntryPoint
class ChatMessageInputFragment : Fragment() {
    
    
    private val recallWait = PageWait(this)
    private val addFriendWait = PageWait(this)
    private val saveWait = PageWait(this)


    
    
    
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    private val draftViewModel: DraftViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    
    
    private val gifPanelViewModel: com.difft.android.chat.gif.GifPanelViewModel by viewModels()

    
    
    private val favoriteViewModel: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )

    @Inject
    lateinit var gifSendUseCase: com.difft.android.chat.gif.GifSendUseCase

    



    private enum class PanelMode { NONE, GIF }

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

    private var currentDraft = Draft()   

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var pushTextSendJobFactory: PushTextSendJobFactory

    @Inject
    lateinit var pushScreenShotSendJobFactory: PushScreenShotSendJobFactory

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

    
    
    
    @Volatile
    private var mentionStatsSnapshot: MentionStatsSnapshot? = null
    private val mentionStatsMutex = Mutex()

    
    private val mentionStatsGeneration = AtomicLong(0)

    private var keyboardStateListener: InsetAwareConstraintLayout.KeyboardStateListener? = null

    
    private var keyboardPanelHost: KeyboardPanelHost? = null

    
    private var updateInputGap: () -> Unit = {}

    
    private var voiceRecordHost: VoiceRecordHost? = null

    
    
    private var lastNotifiedPanelVisible = false
    private var lastNotifiedPanelHeight = 0

    private var screenshotDetector: ScreenshotDetector? = null

    private var inputLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var inputLayoutViewTreeObserver: ViewTreeObserver? = null
    private var inputLayoutAttachListener: View.OnAttachStateChangeListener? = null
    private var updateConfidentialJob: Job? = null
    private var lastInputContentLength = 0
    private var lastInputHeight = 0

    
    private var shouldHideConfidential = false

    
    private var isConfidentialCollapsed = false

    private val onPicturePermissionForMessage = registerPermission {
        onPicturePermissionForMessageResult(it)
    }

    companion object {
        private const val PANEL_ANIM_DURATION = CHAT_PANEL_ANIM_DURATION_MS
        private const val PRESSED_INPUT_SCALE = 0.97f
        private const val PRESS_ANIM_DURATION_MS = 100L
        private const val MENU_OPEN_ROTATION = 45f
        private const val MENU_ICON_TURN_MS = 200L
    }

    


    private data class AttachmentInfo(
        val filePath: String,
        val fileName: String,
        val mimeType: String,
        



        val localId: String,
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

    



    private fun resolveKeyboardPanelHost(): KeyboardPanelHost? {
        (parentFragment?.view as? InsetAwareConstraintLayout)?.let {
            return InsetAwareKeyboardPanelHost(it)
        }
        return activity as? KeyboardPanelHost
    }

    
    private fun panelLiftHeightPx(): Int =
        binding.llChatActions.minHeight.takeIf { it > 0 } ?: cachedPanelHeightPx()

    
    private fun notifyPanelState(visible: Boolean, heightPx: Int = 0) {
        if (view == null) return
        if (visible == lastNotifiedPanelVisible && heightPx == lastNotifiedPanelHeight) return
        lastNotifiedPanelVisible = visible
        lastNotifiedPanelHeight = heightPx
        keyboardPanelHost?.onChatPanelVisibilityChanged(visible, heightPx)
        updateInputGap()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        
        keyboardPanelHost = resolveKeyboardPanelHost()
        L.i { "[ChatInput] keyboard/panel host=${keyboardPanelHost?.javaClass?.simpleName ?: "none"}" }
        voiceRecordHost = parentFragment as? VoiceRecordHost ?: activity as? VoiceRecordHost

        isGroup = chatViewModel.forWhat is For.Group

        
        ChatInputChrome.apply(binding, glass = usesConversationGlass())
        initView()
        updateInputGap = ChatInputGap.follow(this, binding.clInput) { lastNotifiedPanelVisible }

        setupGifPanel()

        chatViewModel.chatUIData.filterNotNull().onEach { data ->
            chatUIData = data
            data.group?.let {
                if (it.status == 0) {
                    binding.root.visibility = View.VISIBLE
                } else {
                    binding.root.visibility = View.GONE
                }
                
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
            
            ChatThemeSetting.theme.drop(1).collect { theme -> ChatInputChrome.applySendButton(binding.buttonSubmit, theme) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.messageQuoted.collect {
                if (!isAdded || view == null) return@collect
                val message = it as? TextChatMessage ?: return@collect
                val text = createQuoteContent(message)

                
                
                
                quote = Quote(it.timeStamp, it.authorId, text, buildQuotedAttachments(message))
                binding.quoteZone.visibility = View.VISIBLE

                binding.author.text = if (it.authorId == globalServices.myId) {
                    getString(R.string.you)
                } else {
                    chatViewModel.contactorCache.getContactor(it.authorId)
                        ?.getDisplayNameWithoutRemarkForUI()
                        ?: it.authorId.formatBase58Id()
                }
                
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
                                
                                
                                
                                forwardContext = resolvedForwardContext.deepCopyWithNewAttachmentIdentities(
                                    ForwardSourceContext(messageToForward.id, messageToForward.isConfidential())
                                )
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
                                                
                                                
                                                listOf(
                                                    attach.toForwardCopy(
                                                        ForwardSourceContext(messageToForward.id, messageToForward.isConfidential())
                                                    )
                                                )
                                            },
                                            null,
                                            messageToForward.mentions,
                                            messageToForward.systemShowTimestamp
                                        )
                                    )
                                }, isGroup)
                            }

                            
                            val singleMode = NoticeAggregator.computeCombinedForwardMode(
                                listOf(messageToForward),
                                isSubContext = false,
                            )
                            
                            
                            val carriesForeignContent = NoticeAggregator.forwardCarriesForeignContent(
                                listOf(messageToForward),
                                globalServices.myId,
                            )
                            if (saveToNote) {
                                selectChatsUtils.saveToNotes(
                                    requireActivity(),
                                    content,
                                    forwardContext?.let { listOf(it) },
                                    
                                    sourceConversation = chatViewModel.forWhat,
                                    
                                    sourceAuthorIds = listOf(messageToForward.authorId),
                                    combinedForwardMode = singleMode,
                                    carriesForeignContent = carriesForeignContent,
                                    wait = saveWait,
                                )
                            } else {
                                selectChatsUtils.showChatSelectAndSendDialog(
                                    requireActivity(),
                                    content,
                                    null,
                                    null,
                                    forwardContext?.let { listOf(it) },
                                    
                                    scene = ForwardNoticeData.Scene.SINGLE,
                                    
                                    sourceConversation = chatViewModel.forWhat,
                                    
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
            if (it.editModel) {
                
                voiceRecordHost?.voiceRecorderView?.cancelHold()
            }
            if (binding.combineForward.isVisible) {
                binding.combineForward.setContent {
                    
                    
                    
                    DifftTheme(
                        darkTheme = isSystemInDarkTheme(),
                        applyWindowBackground = false
                    ) {
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
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.forwardMultiMessage.collect {
                    selectChatsUtils.showChatSelectAndSendDialog(
                        requireActivity(),
                        it.content,
                        null,
                        null,
                        it.forwardContexts,
                        scene = it.scene,
                        
                        sourceConversation = it.sourceConversation,
                        sourceAuthorIds = it.sourceAuthorIds,
                        messageCount = it.messageCount,
                        combinedForwardMode = it.combinedForwardMode,
                        carriesForeignContent = it.carriesForeignContent,
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.saveMultiMessageToNote.collect {
                    
                    selectChatsUtils.saveToNotes(
                        requireActivity(),
                        it.content,
                        it.forwardContexts,
                        sourceConversation = it.sourceConversation,
                        sourceAuthorIds = it.sourceAuthorIds,
                        messageCount = it.messageCount,
                        combinedForwardMode = it.combinedForwardMode,
                        carriesForeignContent = it.carriesForeignContent,
                        wait = saveWait,
                    )
                }
            }
        }

        registerKeyboardStateListener()

        chatViewModel.listClick
            .onEach {
                if (binding.llChatActions.isVisible) {
                    
                    hidePanel {
                        keyboardPanelHost?.releaseKeyboardPaddingFreeze()
                    }
                }

                syncActionButtons(PanelMode.NONE)

                updateSubmitButtonView()
            }
            .catch { e -> L.w { "[ChatMessageInputFragment] observe recordState error: ${e.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.voiceMessageSend
            .onEach { path ->
                
                
                
                
                
                
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

    private fun updateSubmitButtonView() {
        val hasText = binding.edittextInput.text.toString().trim().isNotEmpty()
        binding.buttonSubmit.visibility = if (hasText) View.VISIBLE else View.GONE
        
        
        val keepForConfidential = isConfidentialCollapsed && !shouldHideConfidential
        binding.clRightActions.visibility = if (hasText || keepForConfidential) View.VISIBLE else View.GONE
        updateInputContentGoneMargin()
        updateInputCursor()
    }

    private fun updateInputContentGoneMargin() {
        val params = binding.clInputContent.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        val needExtraMargin = !isGroup && !isFriend
        params.goneStartMargin = if (needExtraMargin) resources.getDimensionPixelSize(R.dimen.chat_input_edge_inset) else 0
        binding.clInputContent.layoutParams = params
    }

    


    private var attachmentMenu: ChatAttachmentMenu? = null

    
    private fun toggleAttachmentMenu() {
        attachmentMenu?.takeIf { it.isShowing }?.let {
            it.dismiss()
            return
        }
        if (panelMode == PanelMode.GIF) hideGifPanel()
        ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
        val area = parentFragment?.view ?: requireActivity().window.decorView
        attachmentMenu = ChatAttachmentMenu(binding.clLeftButtons, area, viewLifecycleOwner, attachmentMenuItems()) {
            attachmentMenu = null
            if (view != null) binding.buttonMoreActions.animate().rotation(0f).setDuration(MENU_ICON_TURN_MS).start()
        }.also { it.show() }
        binding.buttonMoreActions.animate().rotation(MENU_OPEN_ROTATION).setDuration(MENU_ICON_TURN_MS).start()
    }

    
    private fun attachmentMenuItems(): List<ChatAttachmentMenu.Item> {
        fun item(icon: Int, label: Int, action: () -> Unit) =
            ChatAttachmentMenu.Item(icon, getString(label)) { if (isAdded && view != null) action() }
        return listOfNotNull(
            item(R.drawable.chat_bottom_photos, R.string.chat_action_photo) { pickMedia() },
            item(R.drawable.chat_ic_gif_button, R.string.chat_action_gif) { if (checkCanSpeak()) openGifPanel() },
            item(R.drawable.chat_attachment_open_button, R.string.chat_action_file) { pickFile() },
            item(R.drawable.chat_bottom_contact, R.string.chat_action_contact) { pickContact() },
            if (isGroup) item(R.drawable.chat_btn_at, R.string.chat_action_mention) { insertMention() } else null,
        )
    }

    private fun pickMedia() {
        if (!checkCanSpeak()) return
        
        ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
        binding.edittextInput.clearFocus()
        
        onPicturePermissionForMessage.launchMediaSelectionOrOpen { createPictureSelector() }
    }

    private fun pickFile() {
        if (!checkCanSpeak()) return
        ScreenLockUtil.temporarilyDisabled = true
        launchFilePicker()
    }

    private fun insertMention() {
        insertTextToEdittext("@")
        hidePanel(animated = false)
        syncActionButtons(PanelMode.NONE)
        ViewUtil.focusAndShowKeyboard(binding.edittextInput)
    }

    private fun pickContact() {
        if (!checkCanSpeak()) return
        selectChatsUtils.showContactSelectDialog(requireActivity()) { contact ->
            if (contact == null) return@showContactSelectDialog
            if (!isAdded || view == null) return@showContactSelectDialog

            viewLifecycleOwner.lifecycleScope.launch {
                
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

    private fun initView() {
        binding.quoteDelete.setOnClickListener {
            clearQuoteThumbnail()               

            
            quote = null

            currentDraft = currentDraft.copy(quote = null)

            
            draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)

            
            binding.quoteZone.visibility = View.GONE
        }

        binding.edittextInput.setOnFilePasteListener { uri, mimeType ->
            handleFilePaste(uri, mimeType)
        }

        binding.edittextInput.setOnStickerCommitListener { info, mimeType ->
            handleStickerCommit(info, mimeType)
        }

        var changeStart = 0
        var changeBefore = 0
        var changeCount = 0
        var processingTextChange = false
        binding.edittextInput.addTextChangedListener(
            onTextChanged = { _, start, before, count ->
                if (!processingTextChange) {
                    changeStart = start
                    changeBefore = before
                    changeCount = count
                }
            },
            afterTextChanged = { text ->
                if (processingTextChange) return@addTextChangedListener
                processingTextChange = true
                try {
                    val start = changeStart
                    val before = changeBefore
                    val count = changeCount
                    
                    
                    if (!TextUtils.isEmpty(text)) {
                        if (isGroup) {
                            if (count == 1 && text!!.substring(start, start + 1) == "@") {
                                mentionsSearchKeyStartPos = start
                                mentionsSearchKey = null 
                                mentionStatsGeneration.incrementAndGet() 
                                mentionStatsSnapshot = null 
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
                                }
                                mentionsSearchKey = key
                                updateAtView(key)
                            }
                        }
                        
                        val utf8Bytes = text.toString().toByteArray(Charsets.UTF_8)
                        val truncated = utf8Bytes.size > MAX_TEXT_FILE_SIZE
                        if (truncated) {
                            val truncatedString = text.toString().utf8Substring(MAX_TEXT_FILE_SIZE)
                            ToastUtil.show(getString(R.string.text_file_exceeds_10mb_limit))
                            binding.edittextInput.setText(truncatedString)
                            binding.edittextInput.setSelection(binding.edittextInput.text?.length ?: 0)
                        }
                        if (isGroup && mentions.isNotEmpty()) {
                            
                            
                            updateMentions(!truncated && before > count, start, saveDraft = false)
                        }
                    }
                    if (isGroup && binding.edittextInput.text.isNullOrEmpty()) {
                        mentions.clear()
                        mentionsSelectedContacts.clear()
                        mentionsSearchKeyStartPos = -1
                        mentionsSearchKey = null
                        updateAtView(null)
                    }

                    currentDraft = currentDraft.copy(
                        content = binding.edittextInput.text?.toString()?.normalizeNewlines(),
                        quote = quote,
                        mentions = mentions.toList(),
                    )
                    draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
                    updateSubmitButtonView()
                    updateInputViewSize()
                } finally {
                    processingTextChange = false
                }
            }
        )

        binding.edittextInput.setOnClickListener {
            
            
            if (binding.llChatActions.visibility != View.VISIBLE) {
                binding.llChatActions.visibility = View.GONE
            }
            
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

        initUnifiedInputGesture()

        binding.buttonSubmit.setOnClickListener {
            if (!checkCanSpeak()) return@setOnClickListener
            
            val message: String = binding.edittextInput.text.toString().normalizeNewlines().trim()
            if (!TextUtils.isEmpty(message)) {
                sendValidatedText(message) {
                    binding.edittextInput.setText("")
                }
            }
        }

        binding.buttonMoreActions.setOnClickListener { toggleAttachmentMenu() }

        updateConfidential()
        listOf(binding.ivConfidential, binding.ivConfidentialRight).forEach { view ->
            view.setOnClickListener {
                val confidentialMode = if (view.tag == 0) 1 else 0
                
                if (confidentialMode == 1 && globalServices.userManager.getUserData()?.hasShownConfidentialTip != true) {
                    showConfidentialTipDialog {
                        chatSettingViewModel.setConfidentialMode(confidentialMode)
                    }
                } else {
                    chatSettingViewModel.setConfidentialMode(confidentialMode)
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
                    
                    lastInputHeight = currentHeight
                } else if (currentHeight != prevHeight) {
                    lastInputHeight = currentHeight
                    chatViewModel.emitInputHeightChanged()
                }
                
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
                    updateSubmitButtonView()
                }
            }
        }
        inputLayoutViewTreeObserver = binding.edittextInput.viewTreeObserver
        inputLayoutViewTreeObserver?.addOnGlobalLayoutListener(inputLayoutListener)

        
        
        
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


        binding.tvUnblock.setOnClickListener {
            chatSettingViewModel.setConversationConfigs(
                requireActivity(),
                chatViewModel.forWhat.id,
                null,
                null,
                0,
                false,
                getString(R.string.contact_unblocked)
            )
        }

        
        binding.tvIgnore.setOnClickListener {
            detailNavigation().closeConversation(this)
        }

        binding.tvAccept.setOnClickListener {
            requestAddFriend(action = "accept", showLoading = true)
        }

        updateViewByFriendCheck()

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
                
                draft.content?.let { content ->
                    if (binding.edittextInput.text.toString() != content) {
                        binding.edittextInput.setText(content)
                        binding.edittextInput.setSelection(content.length)
                    }
                }

                
                val quote = draft.quote
                if (quote != null) {
                    binding.quoteZone.visibility = View.VISIBLE

                    binding.author.text = if (quote.author == globalServices.myId) {
                        getString(R.string.you)
                    } else {
                        chatViewModel.contactorCache.getContactor(quote.author)
                            ?.getDisplayNameWithoutRemarkForUI()
                            ?: quote.author.formatBase58Id()
                    }

                    
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
            
            
            if (!isFriendResolved) binding.clSendMessage.alpha = 0f
            val lookupBinding = binding
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    isFriend = withContext(Dispatchers.IO) {
                        wcdb.contactor.getFirstObject(DBContactorModel.id.eq(chatViewModel.forWhat.id)) != null
                    }
                    if (!isAdded || view == null) return@launch
                    updateSubmitButtonView()
                    updateBottomView()
                    updateConfidential()
                    if (!isFriend) {
                        checkAndUpdateFriendRequestStatus()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.e { "[ChatMessageInputFragment] friend check failed: ${e.stackTraceToString()}" }
                } finally {
                    
                    
                    if (isAdded && view != null && binding === lookupBinding) {
                        isFriendResolved = true
                        binding.clSendMessage.alpha = 1f
                    }
                }
            }
        }
    }

    









    private val pictureSelectorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        
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
                            
                            val fileName = media.fileName.takeIf { !it.isNullOrEmpty() }
                                ?: FileSystemUtils.getFileName(media.path)
                            
                            
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
        
        ScreenLockUtil.temporarilyDisabled = false
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val uri = result.data?.data
        if (uri == null) {
            ToastUtil.showLong(R.string.file_unavailable)
            return@registerForActivityResult
        }

        viewLifecycleOwner.lifecycleScope.launch {
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
            } else if (isWhitelistedVideoType(message.attachment?.contentType)) {
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

    








    private fun quoteLocalAttachmentPath(message: TextChatMessage, attachment: Attachment): String? {
        
        if (attachment.fileName == null) return null
        val path = AttachmentPathResolver.materializedFileFor(attachment, message.id)
        
        
        return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
    }

    
    private fun clearQuoteThumbnail() {
        
        
        
        if (binding.quoteThumbnail.isHostActivityAlive()) {
            Glide.with(binding.quoteThumbnail).clear(binding.quoteThumbnail)
        }
        binding.quoteThumbnail.visibility = View.GONE
    }

    




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
            MediaUtil.isImageType(attachment.contentType) || attachment.isPlayableVideo() -> {
                
                
                clearQuoteThumbnail()
                loadComposeQuoteThumbnailAsync(message.timeStamp) {
                    quoteLocalAttachmentPath(message, attachment)
                }
            }
            else -> {
                
                binding.quoteThumbnail.visibility = View.VISIBLE
                QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, fileTypeIcon(attachment.fileName), ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
            }
        }
    }

    






    private fun loadComposeQuoteThumbnailAsync(expectedQuoteId: Long, resolve: suspend () -> String?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = withContext(Dispatchers.IO) { resolve() }
            if (!isAdded || view == null) return@launch
            if (quote?.id != expectedQuoteId) return@launch 
            if (path == null) {
                clearQuoteThumbnail()
                return@launch
            }
            binding.quoteThumbnail.visibility = View.VISIBLE
            QuoteThumbnailBinder.loadRoundedThumbnail(binding.quoteThumbnail, EncryptedAttachmentAccess.imageGlideModel(path))
        }
    }

    







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
            
            MediaUtil.isImageType(qa.contentType) || isWhitelistedVideoType(qa.contentType) -> {
                
                
                clearQuoteThumbnail()
                val forWhat = chatViewModel.forWhat
                loadComposeQuoteThumbnailAsync(quote.id) {
                    findOriginalAttachmentPath(quote.id, forWhat.id, forWhat.typeValue)
                }
            }
            else -> {
                binding.quoteThumbnail.visibility = View.VISIBLE
                QuoteThumbnailBinder.setTypeIcon(binding.quoteThumbnail, fileTypeIcon(qa.fileName), ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
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

    



    fun updateMentions(isDelete: Boolean, deletePos: Int = 0, saveDraft: Boolean = true) {
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

                        
                        
                        if (saveDraft && binding.edittextInput.text.toString().trim() == text) {
                            currentDraft = currentDraft.copy(mentions = mentions.toList())
                            draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
                        }
                    }
                }
            }.onFailure { e ->
                L.e { "update mentions span error: ${e.stackTraceToString()}" }
            }
        }
    }

    




    private suspend fun ensureMentionStats(roomId: String): MentionStatsSnapshot {
        mentionStatsSnapshot?.let { if (it.roomId == roomId) return it }
        return mentionStatsMutex.withLock {
            mentionStatsSnapshot?.let { if (it.roomId == roomId) return@withLock it }
            
            
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
            
            
            val sessionStart = mentionsSearchKeyStartPos
            val sessionGen = mentionStatsGeneration.get()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val contacts = mutableListOf<ContactsAtAdapter.Item>()

                runCatching {
                    val roomId = chatViewModel.forWhat.id
                    val members = chatUIData?.group?.members?.convertToContactorModels() ?: emptyList()
                    if (members.isNotEmpty()) {
                        
                        
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
                            key = key, 
                            now = snapshot.now, 
                            mentionStats = snapshot.mentionStats,
                            lastSpeakTime = snapshot.lastSpeakTime,
                        )
                        if (ordered.isNotEmpty()) {
                            contacts.add(ContactsAtAdapter.Item.Title(getString(R.string.chat_at_group_members), getString(R.string.chat_at_tips)))
                            
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
    private var isFriendResolved = false

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
        
        shouldHideConfidential = hideConfidential

        if (hideConfidential) {
            
            binding.ivConfidential.visibility = View.GONE
            binding.ivConfidentialRight.visibility = View.GONE
            applyConfidentialAreaStyle(false)
        } else {
            
            val lineCount = binding.edittextInput.lineCount
            if (lineCount > 3) isConfidentialCollapsed = true
            else if (lineCount <= 2) isConfidentialCollapsed = false
            
            binding.ivConfidential.visibility = if (isConfidentialCollapsed) View.GONE else View.VISIBLE
            binding.ivConfidentialRight.visibility = if (isConfidentialCollapsed) View.VISIBLE else View.GONE

            val isConfidentialOn = chatSettingViewModel.currentConversationSet?.confidentialMode == 1
            
            
            val drawable = if (isConfidentialOn) {
                AppCompatResources.getDrawable(requireContext(), R.drawable.chat_btn_confidential_mode_enable)
            } else {
                AppCompatResources.getDrawable(requireContext(), R.drawable.chat_btn_confidential_mode_disable)?.mutate()?.apply {
                    setTint(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
                }
            }
            listOf(binding.ivConfidential, binding.ivConfidentialRight).forEach { view ->
                view.setImageDrawable(drawable)
                view.tag = if (isConfidentialOn) 1 else 0
            }
            applyConfidentialAreaStyle(isConfidentialOn)
        }
        refreshInputHint()
        updateSubmitButtonView()
    }

    private val canRecordVoice: Boolean
        get() = isGroup || isFriend

    



    private fun isInputMode(): Boolean {
        if (!binding.edittextInput.hasFocus()) return false
        val config = resources.configuration
        val hardKeyboard = config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
        return hardKeyboard || ViewCompat.getRootWindowInsets(binding.root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun refreshInputHint() {
        if (view == null) return
        val isConfidentialOn = !shouldHideConfidential &&
            chatSettingViewModel.currentConversationSet?.confidentialMode == 1
        
        binding.edittextInput.hint = when {
            isConfidentialOn -> getString(R.string.chat_message_input_hint_confidential)
            canRecordVoice && !isInputMode() -> buildHoldToTalkHint(requireContext())
            else -> getString(chatViewModel.neutralInputHintRes)
        }
        updateInputCursor()
    }

    



    private fun updateInputCursor() {
        val holdHintShown = canRecordVoice && !isInputMode() && binding.edittextInput.text.isNullOrEmpty()
        binding.edittextInput.isCursorVisible = !holdHintShown
    }

    



    @SuppressLint("ClickableViewAccessibility")
    private fun initUnifiedInputGesture() {
        binding.edittextInput.setOnFocusChangeListener { _, _ -> refreshInputHint() }
        val handler = UnifiedInputTouchHandler(
            moveTolerancePx = (UnifiedInputTouchHandler.MOVE_TOLERANCE_DP * resources.displayMetrics.density).toInt(),
            shouldIntercept = { canRecordVoice && binding.edittextInput.isEnabled && !isInputMode() },
            callbacks = object : UnifiedInputTouchHandler.Callbacks {
                override fun onPressStateChanged(pressed: Boolean) {
                    val scale = if (pressed) PRESSED_INPUT_SCALE else 1f
                    binding.clInputContent.animate().scaleX(scale).scaleY(scale).setDuration(PRESS_ANIM_DURATION_MS).start()
                }

                override fun onTap() {
                    binding.edittextInput.performClick()
                }

                override fun onLongPress(): Boolean = startVoiceHold()

                override fun onHoldMove(rawX: Float, rawY: Float) {
                    voiceRecordHost?.voiceRecorderView?.moveHold(rawX, rawY)
                }

                override fun onHoldEnd() {
                    voiceRecordHost?.voiceRecorderView?.endHold()
                }
            },
        )
        binding.edittextInput.setOnTouchListener(handler)
    }

    private fun startVoiceHold(): Boolean {
        
        binding.clInputContent.animate().cancel()
        binding.clInputContent.scaleX = 1f
        binding.clInputContent.scaleY = 1f
        if (!checkCanSpeak()) return false
        val host = voiceRecordHost
        val recorder = host?.voiceRecorderView
        if (recorder == null) {
            L.w { "[ChatInput] voice hold ignored, no recorder host" }
            return false
        }
        recorder.onHoldSessionEnded = {
            if (isAdded && view != null) setVoiceHoldControlsHidden(false)
        }
        host.setVoiceRecordOverlayVisible(true)
        if (!recorder.beginHold(binding.clInputContent)) {
            host.setVoiceRecordOverlayVisible(false)
            return false
        }
        
        
        binding.clInputContent.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        setVoiceHoldControlsHidden(true)
        if (binding.llChatActions.isVisible) {
            
            hidePanel {
                keyboardPanelHost?.releaseKeyboardPaddingFreeze()
            }
            syncActionButtons(PanelMode.NONE)
        }
        return true
    }

    
    private fun setVoiceHoldControlsHidden(hidden: Boolean) {
        val visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        binding.clLeftButtons.visibility = visibility
        binding.clRightButtons.visibility = visibility
    }

    private fun applyConfidentialAreaStyle(isConfidential: Boolean) {
        ChatInputChrome.setConfidential(binding, isConfidential)
        binding.clInput.background = null
        binding.llChatActions.setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1))
    }

    private fun showRecallDialog(message: ChatMessage) {
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = requireActivity().getString(R.string.chat_message_action_recall),
            message = requireActivity().getString(R.string.chat_recall_tips),
            confirmText = requireActivity().getString(R.string.chat_recall_dialog_yes),
            cancelText = requireActivity().getString(R.string.chat_recall_dialog_cancel),
            onConfirm = {
                recallMessage(message.id)
            }
        ))
    }

    


    private fun showBatchRecallDialog(messageIds: Set<String>) {
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = requireActivity().getString(R.string.chat_message_action_recall),
            message = requireActivity().getString(R.string.chat_recall_tips),
            confirmText = requireActivity().getString(R.string.chat_recall_dialog_yes),
            cancelText = requireActivity().getString(R.string.chat_recall_dialog_cancel),
            onConfirm = {
                batchRecallMessages(messageIds)
            }
        ))
    }

    



    private fun batchRecallMessages(messageIds: Set<String>) {
        recallWait.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                
                val pendingIds = messageIds.toMutableSet()
                var hasFailure = false

                
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

                
                messageIds.forEach { messageId ->
                    submitRecallJob(messageId)
                }

                
                val timeoutMs = 30_000L
                val startTime = System.currentTimeMillis()
                while (pendingIds.isNotEmpty() && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    delay(100)
                }

                
                resultJob.cancel()

                
                if (hasFailure || pendingIds.isNotEmpty()) {
                    ToastUtil.show(R.string.operation_failed)
                }

                
                chatViewModel.selectModel(false)
            } catch (e: Exception) {
                L.e { "Batch recall failed: ${e.stackTraceToString()}" }
                ToastUtil.show(R.string.operation_failed)
            } finally {
                recallWait.dismiss()
            }
        }
    }

    


    private suspend fun submitRecallJob(messageID: String) = withContext(Dispatchers.IO) {
        val originMessage = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))
        if (originMessage == null) {
            L.w { "Recall message failed, original message not found: $messageID" }
            
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

    


    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        var dialog: ComposeDialog? = null
        dialog = dismissWithView(ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            showDragHandle = false,
            onDismiss = { }
        ) {
            ConfidentialTipDialogContent(
                title = getString(R.string.chat_confidential_tip_title),
                content = getString(R.string.chat_confidential_tip_content),
                onConfirm = {
                    globalServices.userManager.update { hasShownConfidentialTip = true }
                    dialog?.dismiss()
                    onConfirm()
                }
            )
        })
    }

    



    private fun generateOversizedTextFileName(): String {
        val dateFormatter = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US)
        val dateString = dateFormatter.format(Date())
        return "File-$dateString.txt"
    }

    


    private fun createTextFile(attachmentLocalId: String, fileName: String, content: String): String? {
        return try {
            val filePath = AttachmentPathResolver.stagingFileFor(attachmentLocalId, fileName)
            val file = File(filePath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            filePath
        } catch (e: Exception) {
            L.e { "Failed to create text file: ${e.stackTraceToString()}" }
            null
        }
    }

    
    private fun sendValidatedText(message: String, onSent: (() -> Unit)? = null): Boolean {
        
        
        
        
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
            val attachmentLocalId = UUID.randomUUID().toString()
            val truncatedText = message.utf8Substring(OVERSIZED_TEXT_BODY_LENGTH)

            L.i { "Text message oversized (${messageBytes.size} bytes), converting to file attachment. Body truncated to $OVERSIZED_TEXT_BODY_LENGTH bytes." }

            viewLifecycleOwner.lifecycleScope.launch {
                val filePath = withContext(Dispatchers.IO) {
                    createTextFile(attachmentLocalId, fileName, message)
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
                            localId = attachmentLocalId,
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

    



    private fun mentionsForNormalizedBody(body: String): MutableList<Mention> {
        if (mentions.isEmpty()) return mutableListOf()
        val keys = mentionsSelectedContacts.map(::mentionKeyFor).toHashSet()
        return mentionOffsets(body, keys, ::uidForMentionKey).toMutableList()
    }

    
    private fun mentionKeyFor(contact: ContactorModel): String = "@" + contact.getDisplayNameWithoutRemarkForUI()

    
    private fun uidForMentionKey(key: String): String? =
        mentionsSelectedContacts.find { mentionKeyFor(it) == key }?.id

    






    private fun sendTextPush(
        content: String? = null,
        timeStamp: Long = System.currentTimeMillis(),
        messageId: String = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}",
        attachmentInfo: AttachmentInfo? = null,
    ) {
        val forWhat = if (isGroup) For.Group(chatViewModel.forWhat.id) else For.Account(chatViewModel.forWhat.id)

        
        val hasContent = !content.isNullOrEmpty()

        
        
        
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

        
        viewLifecycleOwner.lifecycleScope.launch {
            
            val attachment = attachmentInfo?.let { info ->
                withContext(Dispatchers.IO) {
                    val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(info.filePath, info.mimeType)
                    val fileSize = FileSystemUtils.getLength(info.filePath)

                    Attachment(
                        
                        
                        
                        
                        "",
                        0,
                        info.mimeType,
                        "".toByteArray(),
                        fileSize.toInt(),
                        "".toByteArray(),
                        "".toByteArray(),
                        info.fileName,
                        
                        if (info.isGif) FLAG_GIF else if (info.isAudioMessage) 1 else 0,
                        mediaWidthAndHeight.first,
                        mediaWidthAndHeight.second,
                        info.filePath,
                        AttachmentStatus.LOADING.code,
                        localId = info.localId
                    )
                }
            }

            
            
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
                sharedContact = sharedContacts.toMutableList(),
                playStatus = if (attachmentInfo?.isAudioMessage == true) AudioMessageManager.PLAY_STATUS_NOT_PLAY else 0
            )

            ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))

            
            if (reactions.isEmpty() && recall == null) {
                
                
                
                
                
                
                
                
                chatViewModel.clearNewMessageDivider()
                chatViewModel.addOneMessage(textMessage)
            }

            checkAndSendAddFriendRequest()

            resetData(clearInputBoundState = hasContent)
        }
    }

    
    private fun checkCanSpeak(): Boolean {
        if (!isGroup) return true
        val group = chatUIData?.group ?: return true
        if (!groupUtil.canSpeak(group, globalServices.myId)) {
            ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
            return false
        }
        return true
    }

    private fun checkAndSendAddFriendRequest() {
        if (!isGroup && !isFriend) {
            val (sourceType, source) = ChatFragment.conversationSourceOf(this)
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
            addFriendWait.show()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    ContactorUtil.fetchAddFriendRequest(requireContext(), chatViewModel.forWhat.id, sourceType, source, action)
                }
                if (showLoading) addFriendWait.dismiss()
                if (response.status == 0) {
                    if (action == "accept") {
                        isFriend = true
                        updateSubmitButtonView()
                        refreshInputHint()
                        updateBottomView()
                        ContactorUtil.emitFriendStatusUpdate(chatViewModel.forWhat.id, true)
                    }
                } else {
                    response.reason?.let { message -> ToastUtil.show(message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (showLoading) addFriendWait.dismiss()
                e.message?.let { message -> ToastUtil.show(message) }
            }
        }
    }


    private fun resetData(clearInputBoundState: Boolean = true) {
        clearQuoteThumbnail()               

        quote = null
        forwardContext = null
        recall = null
        reactions.clear()
        sharedContacts.clear()

        binding.quoteZone.visibility = View.GONE

        
        if (clearInputBoundState) {
            mentionsSelectedContacts.clear()
            mentions.clear()
        }
    }

    

    



    private fun setupGifPanel() {
        binding.gifPanelCompose.setViewCompositionStrategy(
            androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.gifPanelCompose.setContent {
            com.difft.android.base.ui.theme.DifftTheme(applyWindowBackground = false) {
                com.difft.android.chat.gif.compose.GifInlinePanel(
                    viewModel = gifPanelViewModel,
                    favoriteViewModel = favoriteViewModel,
                    onOpenSearch = { openGifSearchDialog() },
                    onPickFavorite = { item -> onFavoritePicked(item) }
                )
            }
        }

        
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                gifPanelViewModel.effect.collect { effect ->
                    when (effect) {
                        is com.difft.android.chat.gif.GifPanelContract.Effect.SendGif ->
                            onGifPicked(effect.uri)
                        is com.difft.android.chat.gif.GifPanelContract.Effect.ShowError -> {
                            
                            
                            L.w { "[ChatMessageInputFragment] gif inline effect error" }
                            ToastUtil.show(R.string.gif_load_failed)
                        }
                        is com.difft.android.chat.gif.GifPanelContract.Effect.FavoriteRemote ->
                            
                            
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

        
        collectFavoriteEffects(favoriteViewModel)

        
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

    




    private fun onGifPicked(uri: Uri) {
        
        prepareSendAttachmentPush(uri, "image/webp", "gif_${System.currentTimeMillis()}.webp", isGif = true)
        hideGifPanel()
    }

    



    private fun onFavoritePicked(item: com.difft.android.chat.gif.favorite.FavoriteGifUiItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                
                
                
                
                
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

    




    private fun openGifPanel() {
        
        
        gifPanelViewModel.onPanelShown()

        
        
        
        
        
        if (gifPanelViewModel.state.value.currentTab ==
            com.difft.android.chat.gif.GifPanelContract.GifTab.FAVORITES
        ) {
            favoriteViewModel.dispatch(
                com.difft.android.chat.gif.favorite.FavoriteContract.Intent.OpenFavorites
            )
        }

        if (binding.llChatActions.isVisible) {
            
            
            syncActionButtons(PanelMode.GIF)
        } else {
            
            val hasKeyboard = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            keyboardPanelHost?.freezeKeyboardPadding()
            
            
            binding.llChatActions.minHeight = cachedPanelHeightPx()
            syncActionButtons(PanelMode.GIF)
            showPanel(animated = !hasKeyboard)
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
        }
    }

    private fun hideGifPanel() {
        if (panelMode != PanelMode.GIF) return
        hidePanel {
            keyboardPanelHost?.releaseKeyboardPaddingFreeze()
        }
        
        binding.gifPanelCompose.updateLayoutParams {
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        }
        syncActionButtons(PanelMode.NONE)
    }

    



    private fun syncActionButtons(mode: PanelMode) {
        panelMode = mode
        setGifContentVisible(gif = mode == PanelMode.GIF)
        
        
        
        if (mode != PanelMode.NONE && binding.llChatActions.isVisible) {
            notifyPanelState(true, panelLiftHeightPx())
        }
    }

    




    private fun cachedPanelHeightPx(): Int {
        val kb = InsetAwareConstraintLayout.getKeyboardHeight(requireContext())
        return if (kb > 0) kb else (280 * resources.displayMetrics.density).toInt()
    }

    





    private fun setGifContentVisible(gif: Boolean) {
        binding.gifPanelCompose.visibility = if (gif) View.VISIBLE else View.GONE
        if (gif) {
            val verticalPadding = binding.llChatActions.paddingTop + binding.llChatActions.paddingBottom
            binding.gifPanelCompose.updateLayoutParams {
                
                
                height = (cachedPanelHeightPx() - verticalPadding).coerceAtLeast(0)
            }
        } else {
            binding.gifPanelCompose.updateLayoutParams {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }
    }

    





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
        
        
        val attachmentLocalId = UUID.randomUUID().toString()
        val filePath = AttachmentPathResolver.stagingFileFor(attachmentLocalId, fileName)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val staged = withContext(Dispatchers.IO) {
                    MediaAttachmentStager.stage(requireContext(), attachmentUri, filePath, mimeType, originalFileName)
                }
                if (staged is MediaAttachmentStager.StageResult.Failed) {
                    
                    MediaSendFailureNotice.showStagingFailure(requireContext(), staged.failure)
                    return@launch
                }
                val isAnimatedImage = withContext(Dispatchers.IO) {
                    
                    if (isGif) true
                    else if (MediaUtil.isImageType(mimeType)) detectAnimatedImage(filePath, mimeType)
                    else false
                }
                FileUtil.deleteTempFile(FileSystemUtils.getFileName(attachmentUri.path))
                
                
                
                attachmentUri.path?.takeIf { it.contains("/gif_send/") }?.let { runCatching { File(it).delete() } }
                sendTextPush(
                    timeStamp = timeStamp,
                    messageId = messageId,
                    attachmentInfo = AttachmentInfo(
                        filePath = filePath,
                        fileName = fileName,
                        mimeType = mimeType,
                        localId = attachmentLocalId,
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

    private fun detectAnimatedImage(filePath: String, mimeType: String): Boolean =
        AnimatedImageDetector.isAnimated(File(filePath), mimeType)

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
        recallWait.show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                
                var resultReceived = false
                val resultJob = launch {
                    RecallResultTracker.recallResults.collect { result ->
                        if (result.messageId == messageID && !resultReceived) {
                            resultReceived = true
                            if (!result.success) {
                                ToastUtil.show(R.string.operation_failed)
                            }
                            recallWait.dismiss()
                        }
                    }
                }

                val originMessage = withContext(Dispatchers.IO) {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))
                }
                if (originMessage == null) {
                    L.w { "Recall message failed, original message not found: $messageID" }
                    resultJob.cancel()
                    recallWait.dismiss()
                    ToastUtil.show(R.string.operation_failed)
                    return@launch
                }
                if (originMessage.fromWho != globalServices.myId) {
                    L.w { "Recall message failed, the sender does not match, realSource:${originMessage.fromWho}" }
                    resultJob.cancel()
                    recallWait.dismiss()
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

                
                val timeoutMs = 30_000L
                val startTime = System.currentTimeMillis()
                while (!resultReceived && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    delay(100)
                }

                resultJob.cancel()

                
                if (!resultReceived) {
                    recallWait.dismiss()
                }

                recall = null
            } catch (e: Exception) {
                L.e { "Recall message failed: ${e.stackTraceToString()}" }
                recallWait.dismiss()
                ToastUtil.show(R.string.operation_failed)
                recall = null
            }
        }
    }

    



    private fun resendMessage(messageID: String) {
        val jobFactory = pushTextSendJobFactory
        appScope.launch {
            try {
                val message = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))?.convertToTextMessage()
                    ?: return@launch
                
                val attachment = message.attachments?.firstOrNull()
                if (attachment != null && !AttachmentSendIdentity(message.id).hasUploadSource(attachment)) {
                    L.w { "[ChatResend] resend declined id=${message.id} reason=source_missing" }
                    ToastUtil.show(R.string.chat_resend_source_missing)
                    return@launch
                }
                ApplicationDependencies.getJobManager().add(jobFactory.create(null, message))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[ChatResend] resend failed id=$messageID: ${e.stackTraceToString()}" }
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
                dismissWithView(ComposeDialogManager.showMessageDialog(
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
                ))
            }
        }
    }

    private fun handleFilePaste(uri: Uri, mimeType: String) {
        if (!isGroup && !isFriend) {
            ToastUtil.show(R.string.contact_non_friend_text_only)
            return
        }
        
        
        if (!checkCanSpeak()) return
        
        viewLifecycleOwner.lifecycleScope.launch {
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

    
    
    private fun handleStickerCommit(info: InputContentInfoCompat, mimeType: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            
            try {
                if (!isGroup && !isFriend) {
                    ToastUtil.show(R.string.contact_non_friend_text_only)
                    return@launch
                }
                
                if (!checkCanSpeak()) return@launch
                
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

                
                
                val safeFileName = copyResult.originalFileName?.takeIf { FileUtil.isFileNameValid(it) }
                prepareSendAttachmentPush(copyResult.tempFile.toUri(), mimeType, safeFileName)
            } finally {
                runCatching { info.releasePermission() }
            }
        }
    }

    











    private fun sendScreenshotNotification() {
        val timeStamp = System.currentTimeMillis()
        val myId = globalServices.myId
        val forWhat = if (isGroup) For.Group(chatViewModel.forWhat.id) else For.Account(chatViewModel.forWhat.id)
        
        val messageMode = if (shouldHideConfidential) 0 else (chatSettingViewModel.currentConversationSet?.confidentialMode ?: 0)
        val textMessage = TextMessage(
            LocalMessageCreator.generateMessageId(timeStamp, myId, DEFAULT_DEVICE_ID),
            For.Account(myId),
            forWhat,
            timeStamp,
            timeStamp,
            System.currentTimeMillis(),
            SendType.Sending.rawValue,
            chatSettingViewModel.getMessageExpirySeconds(),
            0,
            0,
            messageMode,
            getString(R.string.chat_took_a_screen_shot, getString(R.string.you)),
            screenShot = ScreenShot(RealSource(myId, DEFAULT_DEVICE_ID, timeStamp, timeStamp)),
        )
        ApplicationDependencies.getJobManager().add(pushScreenShotSendJobFactory.create(null, textMessage))
    }

    override fun onResume() {
        super.onResume()
        
        if (screenshotDetector == null) {
            screenshotDetector = ScreenshotDetector(
                activity = requireActivity(),
                coroutineScope = viewLifecycleOwner.lifecycleScope,
                onScreenshotDetected = {
                    L.i { "[ChatMessageInputFragment][Screenshot] Screenshot detected, sending notification" }
                    sendScreenshotNotification()
                }
            )
        }
        screenshotDetector?.startListening()
    }

    override fun onPause() {
        super.onPause()
        screenshotDetector?.stopListening()
        
        draftViewModel.flushNow()
    }

    override fun onDestroyView() {
        
        isFriendResolved = false
        
        
        
        inputLayoutListener?.let { listener ->
            inputLayoutViewTreeObserver?.let { observer ->
                if (observer.isAlive) {
                    observer.removeOnGlobalLayoutListener(listener)
                }
            }
        }
        
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
        keyboardPanelHost?.let { host ->
            keyboardStateListener?.let { host.removeKeyboardStateListener(it) }
            
            host.releaseKeyboardPaddingFreeze()
            
            
            
            host.onChatPanelVisibilityChanged(false, 0)
        }
        keyboardStateListener = null
        keyboardPanelHost = null
        voiceRecordHost?.voiceRecorderView?.onHoldSessionEnded = null
        voiceRecordHost = null
        
        
        lastNotifiedPanelVisible = false
        lastNotifiedPanelHeight = 0
    }

    private fun registerKeyboardStateListener() {
        val host = keyboardPanelHost ?: return
        keyboardStateListener = object : InsetAwareConstraintLayout.KeyboardStateListener {
            override fun onKeyboardShown() {
                if (!isAdded || view == null) return
                val panelVisible = binding.llChatActions.isVisible
                if (!panelVisible) {
                    hidePanel(animated = false)
                }
                
                syncActionButtons(PanelMode.NONE)
                updateSubmitButtonView()
                refreshInputHint()
                updateInputGap()
            }
            override fun onKeyboardHidden() {
                if (!isAdded || view == null) return
                updateSubmitButtonView()
                refreshInputHint()
                updateInputGap()
            }
            override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
                if (!isAdded || view == null) return
                refreshInputHint()
                val panelVisible = binding.llChatActions.isVisible
                if (isKeyboardVisible && panelVisible) {
                    hidePanel(animated = false)
                    keyboardPanelHost?.releaseKeyboardPaddingFreeze()
                    
                    syncActionButtons(PanelMode.NONE)
                }
            }
        }.also { host.addKeyboardStateListener(it) }
    }

    private var panelAnimator: android.animation.ValueAnimator? = null

    





    private fun showPanel(animated: Boolean = true) {
        panelAnimator?.cancel()
        
        
        notifyPanelState(true, panelLiftHeightPx())
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
        
        
        notifyPanelState(false)
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

    



    fun focusInputAndShowKeyboard() {
        if (!isAdded || view == null) return
        binding.edittextInput.apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        ViewUtil.focusAndShowKeyboard(binding.edittextInput)
    }

}
