package com.difft.android.chat.ui

import android.annotation.SuppressLint
import com.difft.android.base.widget.applyFloatingSurface
import com.difft.android.base.widget.floatAsTopBarButton
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.core.view.doOnNextLayout
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.OnScrollListener
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.compose.e2ee.E2eeInfoSheet
import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.chat.translate.backend.TranslateBackend
import com.difft.android.chat.translate.ui.ChatTranslateListBinder
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.isVisibleTopNow
import com.difft.android.base.utils.pane.onVisibleTopAgain
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.ChatNormalPaginationController.Companion.TRIM_HIGH_WATER
import com.difft.android.chat.R
import com.difft.android.chat.ScrollAction
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.SendType
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.contacts.contactsdetail.ContactDetailBottomSheetDialogFragment
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.databinding.ChatFragmentMessageListBinding
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.ConfidentialPlaceholderChatMessage
import com.difft.android.chat.message.EncryptionHeaderChatMessage
import com.difft.android.chat.message.MessageActionHelper
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.attachment.AttachmentDownloadDecision
import com.difft.android.chat.attachment.AttachmentTapAction
import com.difft.android.chat.attachment.AttachmentTapBlockedReason
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.singleForwardableAttachment
import com.difft.android.chat.ui.theme.ChatThemeSetting
import com.difft.android.chat.ui.theme.followChatTheme
import difft.android.messageserialization.model.isLongText
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.pagination.PageLoadDecision
import com.difft.android.chat.pagination.PageLoadTrigger
import com.difft.android.chat.pagination.decidePageLoad
import com.difft.android.chat.pagination.launchScrollPrefetch
import com.difft.android.chat.pagination.runGatedPageLoad
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.messageaction.ChatChromeInsets
import com.difft.android.chat.ui.messageaction.ChatContentBand
import com.difft.android.chat.ui.messageaction.FailedMessageActionPopup
import com.difft.android.chat.ui.messageaction.MessageActionCoordinator
import com.difft.android.chat.widget.AudioAmplitudesHelper
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.VoiceMessageView
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.UrlManager
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.selector.basic.IBridgeViewLifecycle
import com.difft.android.selector.basic.PictureSelector
import com.difft.android.selector.engine.ExoVideoPlayerEngine
import com.difft.android.selector.entity.LocalMedia
import com.difft.android.selector.interfaces.OnExternalPreviewEventListener
import com.difft.android.selector.language.LanguageConfig
import com.difft.android.selector.pictureselector.GlideEngine
import com.difft.android.selector.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.TranslateTargetLanguage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import com.difft.android.chat.media.AttachmentPreview
import com.difft.android.chat.media.EncryptedAttachmentAccess
import com.difft.android.chat.media.applyExportPolicy
import com.difft.android.chat.gif.favorite.MAX_FAVORITE_ASSET_BYTES
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isPlayableVideo
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.attachment.videoprobe.VideoProbeCoordinator
import com.difft.android.chat.attachment.mayOpenExternally
import com.difft.android.chat.attachment.opensInAppViewer
import com.difft.android.chat.attachment.videoprobe.rendersAttachment
import difft.android.messageserialization.model.isVideoShownAsFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import com.difft.android.base.widget.InsetAwareConstraintLayout
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.MessageModel
import org.difft.app.database.wcdb
import com.difft.android.chat.components.reaction.ReactionEmojisAdapter
import com.difft.android.chat.dependencies.ApplicationDependencies
import com.difft.android.chat.jobs.DownloadAttachmentJob
import com.difft.android.chat.util.SaveAttachmentUtil
import com.difft.android.chat.util.ServiceUtil
import com.difft.android.chat.util.ThemeUtil
import com.difft.android.chat.util.viewFile
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import util.TimeFormatter
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class ChatMessageListFragment : Fragment() {

    
    private val saveWait = PageWait(this)

    private var keyboardStateListener: InsetAwareConstraintLayout.KeyboardStateListener? = null

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var translateBackend: TranslateBackend

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    @Inject
    lateinit var urlManager: UrlManager

    @Inject
    lateinit var videoProbeCoordinator: VideoProbeCoordinator

    private val messageActionHelper by lazy {
        MessageActionHelper(requireActivity(), viewLifecycleOwner.lifecycleScope)
    }

    private var needsCallHeaderScrollCompensation = false

    private lateinit var binding: ChatFragmentMessageListBinding

    
    
    
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    
    private val favoriteViewModel: com.difft.android.chat.gif.favorite.FavoriteViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    
    private var translateBinder: ChatTranslateListBinder? = null

    private val chatMessageAdapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(chatViewModel.forWhat, chatViewModel.contactorCache) {
            override fun onTranslateRetry(message: TextChatMessage) {
                chatViewModel.onTranslateRetry(message.id)
            }

            override fun onItemClick(rootView: View, data: ChatMessage) {
                if (data !is TextChatMessage) return
                if (data.isMine && data.sendStatus == SendType.SentFailed.rawValue) {
                    chatViewModel.reSendMessage(data)
                    return
                }
                if (data.forwardContext != null) {
                    val forwardContext = data.forwardContext ?: return
                    val forwards = forwardContext.forwards ?: return
                    if (data.isConfidential()) {
                        
                        
                        
                        
                        attachmentOnlyForward(forwards)?.let { soleAttachment ->
                            val consumed = handleAttachmentTapBeforeOpen(soleAttachment, data.getAttachmentProgress()) {
                                downloadAttachment(data, soleAttachment)
                            }
                            if (consumed) return
                        }
                        showConfidentialViewTipIfNeeded(data) {
                            ChatForwardMessageActivity.startActivity(
                                requireActivity(),
                                data.id,
                                if (!data.isMine) data.id else null,
                                chatMessageAdapter.shouldSaveToPhotos
                            )
                            if (!data.isMine) {
                                chatViewModel.sendConfidentialViewReceipt(data)
                            }
                        }
                    } else {
                        if (forwards.size == 1) {
                            val forward = forwardContext.forwards?.getOrNull(0) ?: return
                            val attachment = forward.attachments?.getOrNull(0) ?: return
                            val consumed = handleAttachmentTapBeforeOpen(attachment, data.getAttachmentProgress()) {
                                downloadAttachment(data, attachment)
                            }
                            if (consumed) return

                            if (attachment.isImage() || attachment.isPlayableVideo()) {
                                openPreview(generateMessageFromForward(forward) as TextChatMessage)
                            } else if (attachment.isVideoShownAsFile()) {
                                
                                openAttachmentFile(generateMessageFromForward(forward) as TextChatMessage)
                            }
                        } else {
                            ChatForwardMessageActivity.startActivity(
                                requireActivity(),
                                data.id,
                                null,
                                chatMessageAdapter.shouldSaveToPhotos
                            )
                        }
                    }
                } else if (!data.sharedContacts.isNullOrEmpty()) {
                    val contactId =
                        data.sharedContacts?.getOrNull(0)?.phone?.getOrNull(0)?.value
                    if (!TextUtils.isEmpty(contactId)) {
                        ContactDetailBottomSheetDialogFragment.show(
                            this@ChatMessageListFragment,
                            contactId = contactId!!,
                            sourceType = FriendSourceType.SHARE_CONTACT,
                            source = data.authorId
                        )
                    }
                    
                    sendConfidentialViewReceipt(data)
                    deleteConfidentialMessage(data)
                } else if (data.isAttachmentMessage()) {
                    onAttachmentMessageClick(data)
                } else {
                    if (data.isConfidential() && !TextUtils.isEmpty(data.message)
                    ) {
                        showConfidentialViewTipIfNeeded(data) {
                            showConfidentialTextDialog(data)
                        }
                    }
                }
            }

            override fun onItemLongClick(rootView: View, data: ChatMessage) {
                if (data !is TextChatMessage) return
                if (data.isMine && data.sendStatus != SendType.Sent.rawValue) {
                    if (data.sendStatus == SendType.SentFailed.rawValue) {
                        showResendPop(rootView, data)
                    }
                    return
                }

                val imm = ServiceUtil.getInputMethodManager(activity)
                val isKeyboardVisible = imm.isAcceptingText
                imm.hideSoftInputFromWindow(rootView.windowToken, 0)

                
                
                val delayMs = if (isKeyboardVisible) 300L else 100L
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(delayMs)

                    
                    if (!rootView.isAttachedToWindow) {
                        return@launch
                    }

                    
                    val bodyBubble = rootView.findViewById<View>(R.id.contentContainer) ?: return@launch

                    showNewMessageActionPopup(
                        message = data,
                        messageView = rootView,
                        bubbleView = bodyBubble,
                        mostUseEmojis = globalConfigsManager.getMostUseEmojis(),
                        isForForward = false,
                        isSaved = chatViewModel.forWhat.id == globalServices.myId
                    )
                }

            }

            override fun onAvatarClicked(contactor: ContactorModel?) {
                contactor?.let {
                    var sourceType: String? = null
                    var source: String? = null
                    if (chatViewModel.forWhat is For.Group) {
                        sourceType = FriendSourceType.FROM_GROUP
                        source = chatViewModel.forWhat.id
                    }
                    ContactDetailBottomSheetDialogFragment.show(
                        this@ChatMessageListFragment,
                        contactId = it.id,
                        sourceType = sourceType,
                        source = source
                    )
                }
            }

            override fun onAvatarLongClicked(contactor: ContactorModel?) {
                if (chatViewModel.forWhat is For.Group && contactor != null) {
                    chatViewModel.longClickAvatar(contactor)
                }
            }

            override fun onQuoteClicked(quote: Quote) {
                val position =
                    chatMessageAdapter.currentList.indexOfFirst { chatMessage -> chatMessage.timeStamp == quote.id }
                if (position >= 0) {
                    scrollTo(position)
                } else {
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!chatViewModel.jumpToMessage(quote.id)) {
                            ToastUtil.show(getString(R.string.chat_original_message_not_found))
                        }
                    }
                }
            }

            override fun onReactionClick(
                message: ChatMessage,
                emoji: String,
                remove: Boolean,
                originTimeStamp: Long
            ) {
                chatViewModel.emojiReaction(
                    EmojiReactionEvent(
                        message,
                        emoji,
                        remove,
                        originTimeStamp,
                        EmojiReactionFrom.CHAT_LIST
                    )
                )
            }

            override fun onReactionLongClick(
                message: ChatMessage,
                emoji: String,
            ) {
                detailNavigation().push(this@ChatMessageListFragment, EmojiReactionDestination(chatViewModel.forWhat, message.id))
            }

            override fun onSelectedMessage(messageId: String, selected: Boolean) {
                chatViewModel.selectedMessage(messageId, selected)
            }

            override fun onE2eeHeaderClick() {
                L.i { "[Chat] E2EE header clicked roomId=${chatViewModel.forWhat.id}" }
                E2eeInfoSheet.show(
                    activity = requireActivity(),
                    darkTheme = ThemeUtil.isDarkNotificationTheme(requireContext()),
                    learnMoreUrl = urlManager.e2eeLearnMoreUrl,
                )
            }
        }
    }

    private var messageActionCoordinator: MessageActionCoordinator? = null
    private lateinit var backPressedCallback: ConversationBackCallback

    private var isHapticFeedbackTriggered = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop by lazy { android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop }

    private val language by lazy {
        LanguageUtils.getLanguage(requireContext())
    }

    private var userScrolling = false
    private var isDragging = false

    




    private val scrollPrefetchSignals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    private val pageLoadGate = Mutex()

    
    private var pendingAnchor: MessageAnchor? = null

    
    private var bottomButtonAwaitsLayout = false

    private var lastScrollPos: Int = -1
    private val jumpTarget = JumpTargetAnchor()

    
    private var chromeInsets = ChatChromeInsets.NONE
    private var appliedChromeInsets = ChatChromeInsets.NONE
    private var lastMessageCount: Int = -1
    private var lastDayTimeUpdate: Long = 0
    private val dayTimeUpdateInterval: Long = 500
    private var lastPlaceholderCheckTime: Long = 0
    private companion object {
        const val PLACEHOLDER_CHECK_INTERVAL = 500L
        const val KEY_PENDING_CONFIDENTIAL_BURN = "pending_confidential_burn"
        const val FLOATING_LIST_BUTTON_DP = 40f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        
        
        val pendingBurnIds = savedInstanceState?.getStringArrayList(KEY_PENDING_CONFIDENTIAL_BURN)
        if (!pendingBurnIds.isNullOrEmpty()) {
            pendingBurnIds.forEach { id -> L.i { "[Confidential] Burn pending on restore messageId=$id" } }
            ApplicationDependencies.getMessageStore().deleteMessage(pendingBurnIds)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pendingBurnIds = pendingConfidentialBurnIds(
            audio = currentAudioMessage,
            text = currentTextMessage,
            attachment = currentAttachmentMessage,
            viewerConsumed = confidentialViewerConsumed,
        )
        if (pendingBurnIds.isNotEmpty()) {
            outState.putStringArrayList(KEY_PENDING_CONFIDENTIAL_BURN, pendingBurnIds)
        }
        
        
        (currentAnchor() ?: pendingAnchor)?.saveTo(outState)
    }

    




    private fun currentAnchor(): MessageAnchor? {
        if (view == null || !this::binding.isInitialized) return null
        val recycler = binding.recyclerViewMessage
        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return null
        val itemCount = layoutManager.itemCount
        if (itemCount == 0) return null
        val newestBottom = layoutManager.findViewByPosition(itemCount - 1)?.bottom
        val bottomSlackPx = 2.dp
        if (newestBottom != null && newestBottom <= recycler.height - recycler.paddingBottom + bottomSlackPx) {
            return MessageAnchor.BOTTOM
        }
        val position = layoutManager.findFirstVisibleItemPosition()
        val message = chatMessageAdapter.currentList.getOrNull(position) ?: return null
        val top = layoutManager.findViewByPosition(position)?.top ?: return null
        return MessageAnchor(message.timeStamp, top - recycler.paddingTop)
    }

    



    private fun applyPendingAnchor(list: List<ChatMessage>): Boolean {
        val anchor = pendingAnchor ?: return false
        if (list.isEmpty()) return false
        pendingAnchor = null
        val restore = anchor.resolve(list.map { it.timeStamp })
        L.i { "[ChatList] restore anchor kind=${restore.javaClass.simpleName}" }
        when (restore) {
            MessageAnchor.Restore.Bottom -> {
                binding.recyclerViewMessage.noSmoothScrollToBottom()
                lastScrollPos = list.size - 1
                lastMessageCount = list.size
                binding.recyclerViewMessage.post {
                    if (!isAdded || view == null || !this::binding.isInitialized) return@post
                    sendAndUpdateMessageRead()
                }
            }
            is MessageAnchor.Restore.InList -> {
                (binding.recyclerViewMessage.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(restore.position, restore.offsetPx)
                BubbleWidth.navigated(binding.recyclerViewMessage)
                lastScrollPos = restore.position
                lastMessageCount = list.size
            }
            is MessageAnchor.Restore.Jump -> viewLifecycleOwner.lifecycleScope.launch {
                chatViewModel.jumpToMessage(restore.timestamp)
            }
        }
        return true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentMessageListBinding.inflate(inflater, container, false)
        this.binding = binding
        applyFloatingChips()
        return binding.root
    }


    
    private fun applyFloatingChips() {
        val bg1 = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1)
        val bg3 = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg3)
        binding.cvDayTime.applyFloatingSurface(cornerRadiusPx = resources.getDimension(R.dimen.chat_day_chip_radius), fillColor = bg1, elevationDp = 1f)
        binding.cvAt.applyFloatingSurface(maxCircleDiameterDp = FLOATING_LIST_BUTTON_DP, fillColor = bg3, elevationDp = 1f)
        binding.cvToBottom.applyFloatingSurface(maxCircleDiameterDp = FLOATING_LIST_BUTTON_DP, fillColor = bg3, elevationDp = 1f)
    }

    @SuppressLint("ClickableViewAccessibility", "NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pendingAnchor = MessageAnchor.from(savedInstanceState)
        bottomButtonAwaitsLayout = false

        
        
        
        if (activity is ChatPopupActivity || activity is GroupChatPopupActivity) {
            binding.recyclerViewMessage.background = ChatBackgroundDrawable(requireContext())
        }

        val loadingAnimFile = if (ThemeUtil.isDarkNotificationTheme(requireContext())) {
            "tt_loading_dark.json"
        } else {
            "tt_loading_light.json"
        }

        setupCallHeaderScrollCompensation()
        observeVideoProbeResults()

        
        onVisibleTopAgain {
            sendAndUpdateMessageRead()
            checkAndRecordConfidentialPlaceholders()
        }

        binding.lottieViewLoading.setAnimation(loadingAnimFile)
        binding.lottieViewLoading.playAnimation()

        binding.recyclerViewMessage.apply {
            val linearLayoutManager = LinearLayoutManager(this.context).apply {
                
            }
            layoutManager = linearLayoutManager
            itemAnimator = null

            adapter = chatMessageAdapter
            chatMessageAdapter.followChatTheme(this, viewLifecycleOwner)
            chatMessageAdapter.followBubbleWidth(this)
            translateBinder = ChatTranslateListBinder(this, chatMessageAdapter) { ids, reason ->
                chatViewModel.onVisibleMessages(ids, reason)
            }

            addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            isDragging = false
                            
                            showDayTime(linearLayoutManager.findFirstVisibleItemPosition())
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (userScrolling) {
                                    checkAndLoadMessages(PageLoadTrigger.IDLE)
                                }
                                sendAndUpdateMessageRead()
                                checkAndRecordConfidentialPlaceholders()
                                translateBinder?.onScrollIdle()
                                userScrolling = false
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            isDragging = true
                            userScrolling = true
                            
                            pendingAnchor = null
                            jumpTarget.clear()
                        }

                        RecyclerView.SCROLL_STATE_SETTLING -> {
                            isDragging = false
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    
                    
                    scrollPrefetchSignals.tryEmit(Unit)
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    lastScrollPos = firstVisibleItemPosition

                    val currentTime = System.currentTimeMillis()

                    
                    
                    if (dy == 0 || currentTime - lastDayTimeUpdate >= dayTimeUpdateInterval) {
                        showDayTime(firstVisibleItemPosition)
                        lastDayTimeUpdate = currentTime
                    }

                    
                    if (isDragging && currentTime - lastPlaceholderCheckTime >= PLACEHOLDER_CHECK_INTERVAL) {
                        checkAndRecordConfidentialPlaceholders()
                        lastPlaceholderCheckTime = currentTime
                    }
                }
            })

            observeScrollPrefetch()

            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.RIGHT, ItemTouchHelper.RIGHT) {

                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    
                    if (messageActionCoordinator?.isShowing == true) return makeMovementFlags(0, 0)
                    val swipeFlags = ItemTouchHelper.END
                    return makeMovementFlags(0, swipeFlags)
                }

                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    
                    binding.recyclerViewMessage.itemAnimator = DefaultItemAnimator()
                    val position = viewHolder.bindingAdapterPosition

                    viewLifecycleOwner.lifecycleScope.launch {
                        chatMessageAdapter.notifyItemChanged(position)

                        
                        delay(300)
                        if (!isAdded || view == null) return@launch
                        binding.recyclerViewMessage.itemAnimator = null

                        chatMessageAdapter.currentList.getOrNull(position)?.let { message ->
                            if (canQuote(message)) {
                                chatViewModel.quoteMessage(message)
                            }
                        }
                    }
                }

                override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                    return 0.5f
                }

                override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
                    val position = viewHolder.bindingAdapterPosition
                    chatMessageAdapter.currentList.getOrNull(position)?.let { message ->
                        if (!canQuote(message)) return
                    }

                    val itemView = viewHolder.itemView
                    val maxItemViewDx = 110.dp.toFloat()
                    val iconOffset = 50.dp.toFloat()
                    val limitedDX = dX.coerceIn(0f, maxItemViewDx)

                    itemView.translationX = limitedDX

                    val icon = ContextCompat.getDrawable(requireContext(), R.drawable.chat_message_action_quote) ?: return
                    val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                    val iconTop = itemView.top + iconMargin
                    val iconBottom = iconTop + icon.intrinsicHeight

                    val iconLeft = itemView.left + limitedDX - iconOffset
                    val iconRight = iconLeft + icon.intrinsicWidth

                    icon.setBounds(iconLeft.toInt(), iconTop, iconRight.toInt(), iconBottom)
                    icon.draw(c)

                    if (dX >= maxItemViewDx && !isHapticFeedbackTriggered) {
                        viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        isHapticFeedbackTriggered = true
                    }

                    super.onChildDraw(c, recyclerView, viewHolder, limitedDX, dY, actionState, isCurrentlyActive)
                }

                override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(recyclerView, viewHolder)
                    viewHolder.itemView.translationX = 0f
                    isHapticFeedbackTriggered = false
                }

                private fun canQuote(message: ChatMessage) = message is TextChatMessage && message.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
            })
            itemTouchHelper.attachToRecyclerView(this)

            val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (e1 != null) {
                        val diffX = e2.x - e1.x
                        val diffY = e2.y - e1.y
                        if (abs(diffX) > abs(diffY) && diffX > 100) {
                            val viewHolder = binding.recyclerViewMessage.findViewHolderForAdapterPosition(getTouchedPosition(e1)) ?: return false
                            itemTouchHelper.startSwipe(viewHolder)
                            return true
                        }
                    }
                    return super.onScroll(e1, e2, distanceX, distanceY)
                }
            })

            binding.recyclerViewMessage.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    
                    if (messageActionCoordinator?.isShowing == true) return false
                    return gestureDetector.onTouchEvent(e)
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        
    }
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        
    }
            })
        }


        binding.recyclerViewMessage.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                }

                MotionEvent.ACTION_UP -> {
                    if (isClick(event)) {
                        ServiceUtil.getInputMethodManager(activity).hideSoftInputFromWindow(v.windowToken, 0)
                        chatViewModel.clickList()
                    }
                }
            }
            false
        }

        registerKeyboardStateListener()

        
        
        
        
        binding.recyclerViewMessage.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, oldBottom ->
            val reanchor = jumpTarget.reanchorForBottomChange(oldBottom - bottom)
            if (reanchor != ListReanchor.None) v.post { (v as? RecyclerView)?.reanchor(reanchor) }
        }
        appliedChromeInsets = ChatChromeInsets.NONE
        applyChromeInsets()

        binding.clToBottom.setOnClickListener {
            scrollToBottom()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.speechToTextEvent.collect { (messageId, speechToTextData) ->
                if (!isAdded || view == null) return@collect
                (chatMessageAdapter.currentList.find { it.id == messageId } as? TextChatMessage)?.let {
                    it.speechToTextData = speechToTextData
                    notifyItemChangedAndAnchorBottom(chatMessageAdapter.currentList.indexOf(it))
                }
            }
        }

        setAudioObservers()

        observeChatMessageListState()
        observeSelectMessagesState()

        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.inputHeightChanged.collect {
                binding.recyclerViewMessage.noSmoothScrollToBottom()
            }
        }

        
        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { chatMessageAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.contactorCacheRefreshed
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { chatMessageAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatSettingViewModel.conversationSet
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { conversationSet ->
                
                
                val globalSaveToPhotos = globalServices.userManager.getUserData()?.saveToPhotos == true
                val shouldSaveToPhotos = when (conversationSet?.saveToPhotos) {
                    1 -> true  
                    0 -> false 
                    else -> globalSaveToPhotos 
                }
                chatMessageAdapter.shouldSaveToPhotos = shouldSaveToPhotos
                L.d { "[SaveToPhotos] conversationSetting: ${conversationSet?.saveToPhotos}, globalSetting: $globalSaveToPhotos, result: $shouldSaveToPhotos" }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun observeChatMessageListState() {
        viewLifecycleOwner.lifecycleScope.launch {
            
            chatViewModel.chatMessageListUIState
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .filterNotNull() 
                .distinctUntilChanged()
                .collect { handleChatMessageListState(it) }
        }
    }

    
    private fun observeSelectMessagesState() {
        var wasInEditMode = false
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.selectMessagesState.collect { selectState ->
                chatMessageAdapter.updateSelectionState(selectState)
                refreshBackEnabled()

                
                
                val isNowInEditMode = selectState.editModel
                if (isNowInEditMode && !wasInEditMode) {
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
                    if (layoutManager != null && isAtBottom(layoutManager)) {
                        
                        binding.recyclerViewMessage.post {
                            if (isAdded && view != null) {
                                binding.recyclerViewMessage.noSmoothScrollToBottom()
                            }
                        }
                    }
                }
                wasInEditMode = isNowInEditMode
            }
        }

        
        
        
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.selectionRejected.collect { messageId ->
                val position = chatMessageAdapter.currentList.indexOfFirst { it.id == messageId }
                if (position >= 0) {
                    chatMessageAdapter.notifyItemChanged(position, ChatMessageAdapter.PAYLOAD_SELECTION)
                }
            }
        }
    }

    private fun handleChatMessageListState(state: ChatMessageListUIState) {
        L.i {
            "[Chat][${chatViewModel.forWhat.id}] handleChatMessageListState: size=${state.chatMessages.size}, " +
                "window=${state.windowSize}, scrollAction=${state.scrollAction}, " +
                "reachedLatest=${state.hasReachedLatest}, reachedHistoryStart=${state.hasReachedHistoryStart}"
        }
        if (!isAdded || view == null || !this::binding.isInitialized) return

        binding.lottieViewLoading.clearAnimation()
        binding.lottieViewLoading.visibility = View.GONE

        val list = state.chatMessages
        val scrollAction = state.scrollAction
        val isAtBottomBeforeUpdateList = isAtBottom(binding.recyclerViewMessage.layoutManager as LinearLayoutManager)

        chatMessageAdapter.submitList(list) {
            if (!isAdded || view == null) return@submitList

            
            val action = if (applyPendingAnchor(list)) ScrollAction.PreservePosition else scrollAction
            when (action) {
                is ScrollAction.ToPosition -> {
                    scrollTo(action.position)
                    jumpTarget.set(action.position)
                }

                is ScrollAction.ToMessage -> {
                    val position = list.indexOfFirst { it.timeStamp == action.messageTimeStamp }
                    if (position >= 0) {
                        scrollTo(position)
                        jumpTarget.set(position)
                    }
                }

                is ScrollAction.ToBottom -> {
                    jumpTarget.clear()
                    scrollTo(list.size - 1)
                }

                
                is ScrollAction.PreservePosition -> {
                    viewLifecycleOwner.lifecycleScope.launch {
                        updateBottomFloatingButton()
                    }
                }
                
                null -> {
                    if (isAtBottomBeforeUpdateList) {
                        
                        if (list.isNotEmpty()) {
                            binding.recyclerViewMessage.noSmoothScrollToBottom()
                            binding.recyclerViewMessage.post {
                                if (!isAdded || view == null || !this::binding.isInitialized) return@post
                                sendAndUpdateMessageRead()
                            }
                        }
                    } else {
                        viewLifecycleOwner.lifecycleScope.launch {
                            updateBottomFloatingButton()
                        }
                    }
                }
            }

            
            checkAndRecordConfidentialPlaceholders()
            translateBinder?.onListCommitted()

            
            
            maybeTrimWindow(state)
        }

        if (isFirstShow) {
            needsCallHeaderScrollCompensation = scrollAction !is ScrollAction.ToMessage
            binding.recyclerViewMessage.post {
                doAfterFirstRender()
            }
            isFirstShow = false
            L.i { "[Chat][${chatViewModel.forWhat.id}] First time render messages cost ${System.currentTimeMillis() - chatViewModel.viewModelCreateTime}" }
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        val timeDiff = event.eventTime - event.downTime
        val moveX = abs(event.x - touchDownX)
        val moveY = abs(event.y - touchDownY)
        
        return timeDiff < 200 && moveX < touchSlop && moveY < touchSlop
    }

    private var isFirstShow = true

    private fun sendAndUpdateMessageRead() {
        
        if (!isAdded || !this::binding.isInitialized || !isVisibleTopNow()) return
        getLastVisibleMessage()?.let { message ->
            viewLifecycleOwner.lifecycleScope.launch {
                
                val readAt = ServerTimeProvider.nowMillis()
                chatViewModel.sendReadRecipient(message.systemShowTimestamp, readAt)
                chatViewModel.updateMessageReadPosition(message.systemShowTimestamp, readAt)
                updateBottomFloatingButton()
            }
        }
    }

    
    @SuppressLint("SetTextI18n")
    private suspend fun updateBottomFloatingButton() {
        if (!isAdded || view == null) return
        val defaultTintColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon)
        binding.ivToBottom.imageTintList = ColorStateList.valueOf(defaultTintColor)

        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        val lastPos = layoutManager.findLastVisibleItemPosition()
        if (lastPos == RecyclerView.NO_POSITION && chatMessageAdapter.currentList.isNotEmpty()) {
            
            if (!bottomButtonAwaitsLayout) {
                bottomButtonAwaitsLayout = true
                binding.recyclerViewMessage.doOnNextLayout {
                    bottomButtonAwaitsLayout = false
                    if (view != null) viewLifecycleOwner.lifecycleScope.launch { updateBottomFloatingButton() }
                }
            }
            return
        }
        if (chatMessageAdapter.currentList.size - 1 == lastPos || chatMessageAdapter.currentList.isEmpty()) {
            binding.clToBottom.visibility = View.GONE
        } else {
            binding.clToBottom.visibility = View.VISIBLE
        }

        val unreadMessageInfo = withContext(Dispatchers.IO) {
            chatViewModel.getUnreadMessageInfo()
        }
        if (unreadMessageInfo == null) return
        if (unreadMessageInfo.unreadCount == 0) {
            binding.tvUnreadCount.visibility = View.GONE
        } else {
            val tintColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.primary)
            binding.ivToBottom.imageTintList = ColorStateList.valueOf(tintColor)
            binding.tvUnreadCount.visibility = View.VISIBLE
            binding.tvUnreadCountText.text = unreadMessageInfo.unreadCount.toString()
        }

        if (unreadMessageInfo.mentionCount == null || unreadMessageInfo.mentionCount == 0) {
            binding.clAt.visibility = View.GONE
        } else {
            binding.clAt.visibility = View.VISIBLE
            binding.tvAtCountText.text = unreadMessageInfo.mentionCount.toString()

            binding.clAt.setOnClickListener {
                val firstMentionId = unreadMessageInfo.mentionIds?.firstOrNull() ?: -1L
                val firstMentionPos =
                    chatMessageAdapter.currentList.indexOfFirst { message -> message.timeStamp == firstMentionId }
                if (firstMentionPos >= 0) {
                    scrollTo(firstMentionPos)
                    unreadMessageInfo.mentionIds?.let {
                        chatMessageAdapter.highlightItem(ArrayList(it))
                    }
                } else {
                    if (firstMentionId != -1L) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            if (!chatViewModel.jumpToMessage(firstMentionId)) {
                                ToastUtil.show(getString(R.string.chat_original_message_not_found))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun scrollTo(pos: Int) {
        val currentMessageCount = chatViewModel.chatMessageListUIState.value?.chatMessages?.size ?: 0

        
        if (pos < 0 || (pos == lastScrollPos && currentMessageCount == lastMessageCount)) return

        lastScrollPos = pos
        lastMessageCount = currentMessageCount

        if (currentMessageCount - 1 == pos) {
            binding.recyclerViewMessage.noSmoothScrollToBottom()
        } else {
            binding.recyclerViewMessage.layoutManager?.scrollToPosition(pos)
            BubbleWidth.navigated(binding.recyclerViewMessage)
        }

        binding.recyclerViewMessage.post {
            if (!isAdded || view == null || !this::binding.isInitialized) return@post
            sendAndUpdateMessageRead()
        }
    }

    private fun scrollToBottom() {
        if (!isAdded || view == null) return
        binding.clAt.visibility = View.GONE
        binding.clToBottom.visibility = View.GONE
        binding.recyclerViewMessage.noSmoothScrollToBottom()
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.jumpToBottom()
        }
    }

    



    fun getFirstVisibleMessageTimestamp(): Long? {
        if (!this::binding.isInitialized) return null
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return null
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition == RecyclerView.NO_POSITION) return null
        val message = chatMessageAdapter.currentList.getOrNull(firstVisiblePosition)
        return message?.timeStamp
    }

    
    private var wasAtBottomBeforePause = false

    override fun onPause() {
        super.onPause()
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        wasAtBottomBeforePause = isAtBottom(layoutManager)
    }

    override fun onResume() {
        super.onResume()
        if (wasAtBottomBeforePause) {
            
            binding.recyclerViewMessage.post {
                if (!isAdded) return@post
                val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return@post
                if (!isAtBottom(layoutManager)) {
                    binding.recyclerViewMessage.noSmoothScrollToBottom()
                }
                wasAtBottomBeforePause = false
            }
        }
    }

    override fun onDestroyView() {
        
        appScope.launch {
            if (wcdb.isDbInaccessible) {
                L.w { "[ChatMessageListFragment] skip pending confidential placeholders, DB inaccessible" }
                return@launch
            }
            chatViewModel.processPendingConfidentialPlaceholders()
        }
        keyboardStateListener?.let {
            (parentFragment?.view as? InsetAwareConstraintLayout)?.removeKeyboardStateListener(it)
        }
        keyboardStateListener = null
        keyboardListenerRegisterJob?.cancel()
        keyboardListenerRegisterJob = null
        
        messageActionCoordinator?.dismiss()
        messageActionCoordinator = null
        translateBinder = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        AudioMessageManager.releasePlayer()
        AudioAmplitudesHelper.release()
        super.onDestroy()
    }

    private var wasAtBottomBeforeKeyboardHide = false
    private var keyboardListenerRegisterJob: Job? = null

    




    private fun registerKeyboardStateListener() {
        if (activity == null || !isAdded || view == null || keyboardStateListener != null) return

        keyboardListenerRegisterJob?.cancel()
        keyboardListenerRegisterJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if (keyboardStateListener != null || activity == null || !isAdded || view == null) return@launch

            val insetLayout = (parentFragment?.view as? InsetAwareConstraintLayout) ?: return@launch
            keyboardStateListener = object : InsetAwareConstraintLayout.KeyboardStateListener {
                override fun onKeyboardShown() {
                    if (!isAdded || view == null) return
                    scrollToBottom()
                }
                override fun onKeyboardHidden() {
                    if (!isAdded || view == null) return
                    if (wasAtBottomBeforeKeyboardHide) {
                        
                        
                        binding.recyclerViewMessage.noSmoothScrollToBottom()
                    }
                }
                override fun onKeyboardAnimationEnded(isKeyboardVisible: Boolean) {
                    if (!isAdded || view == null) return
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
                    wasAtBottomBeforeKeyboardHide = layoutManager?.let { isAtBottom(it) } ?: false
                }
            }.also { insetLayout.addKeyboardStateListener(it) }
        }
    }

    




    fun setChromeInsets(top: Int, bottom: Int) {
        chromeInsets = ChatChromeInsets(top, bottom)
        applyChromeInsets()
    }

    private fun applyChromeInsets() {
        if (view == null || !this::binding.isInitialized) return
        val insets = chromeInsets
        val old = appliedChromeInsets
        if (insets == old) return
        appliedChromeInsets = insets
        val rv = binding.recyclerViewMessage
        val edge = resources.getDimensionPixelSize(R.dimen.chat_message_list_edge_padding)
        val gap = resources.getDimensionPixelSize(R.dimen.chat_message_list_chrome_gap)
        rv.clipToPadding = false
        rv.setPadding(rv.paddingLeft, insets.top + edge, rv.paddingRight, insets.bottom + edge)
        ChatContentBand.setInsets(rv, insets)
        binding.cvDayTime.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin = insets.top + gap }
        binding.clToBottom.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = insets.bottom + gap }
        
        binding.clAt.updateLayoutParams<ConstraintLayout.LayoutParams> { goneBottomMargin = insets.bottom + gap }
        rv.reanchor(jumpTarget.reanchorForChrome(old, insets))
    }

    private var failedMessagePopup: FailedMessageActionPopup? = null
    private var failedMessagePopupMessageId: String? = null

    private fun showResendPop(rootView: View, data: ChatMessage) {
        if (data !is TextChatMessage) return

        if (failedMessagePopup?.isShowing == true && data.id == failedMessagePopupMessageId) {
            return
        }
        failedMessagePopup?.dismiss()
        failedMessagePopup = null
        failedMessagePopupMessageId = data.id

        val view = rootView.findViewById<ViewGroup>(R.id.contentContainer) ?: rootView

        failedMessagePopup = FailedMessageActionPopup(requireActivity()).also { popup ->
            popup.show(
                anchorView = view,
                message = data,
                containerView = binding.recyclerViewMessage,
                callbacks = object : FailedMessageActionPopup.Callbacks {
                    override fun onResend() {
                        chatViewModel.reSendMessage(data)
                    }

                    override fun onDelete() {
                        chatViewModel.deleteMessage(data.id)
                    }

                    override fun onDismiss() {
                        failedMessagePopup = null
                        failedMessagePopupMessageId = null
                    }
                }
            )
        }
    }

    private var mConfidentialAudioDialog: ConfidentialAudioBottomSheetFragment? = null
    var voiceMessageView: VoiceMessageView? = null
    private var currentAudioMessage: TextChatMessage? = null

    







    private var confidentialViewerConsumed = false

    
    fun consumeConfidentialInViewer(message: ChatMessage) {
        
        if (confidentialViewerConsumed) return
        confidentialViewerConsumed = true
        sendConfidentialViewReceipt(message)
    }

    fun showConfidentialAudioDialog(message: TextChatMessage) {
        if (!isAdded || activity == null) return

        currentAudioMessage = message
        confidentialViewerConsumed = false

        val fragmentManager = requireActivity().supportFragmentManager

        
        fragmentManager.clearFragmentResult("confidential_audio_dialog_result")
        fragmentManager.setFragmentResultListener(
            "confidential_audio_dialog_result",
            this
        ) { _, _ ->
            onConfidentialAudioDialogDismiss()
        }

        mConfidentialAudioDialog = ConfidentialAudioBottomSheetFragment.newInstance().apply {
            show(fragmentManager, "confidential_audio_dialog")
        }
    }

    fun onConfidentialAudioDialogDismiss() {
        currentAudioMessage?.let { message ->
            message.attachment?.isPlaying = false
            message.attachment?.playProgress = 0
            voiceMessageView = null

            
            
            if (!message.isMine && confidentialViewerConsumed) {
                deleteConfidentialMessage(message)
            }
        }
        currentAudioMessage = null
        mConfidentialAudioDialog = null
    }

    private var mConfidentialTextDialog: ConfidentialTextBottomSheetFragment? = null
    private var currentTextMessage: TextChatMessage? = null

    fun showConfidentialTextDialog(message: TextChatMessage) {
        if (!isAdded || activity == null) return

        currentTextMessage = message
        confidentialViewerConsumed = false

        val fragmentManager = requireActivity().supportFragmentManager

        
        fragmentManager.clearFragmentResult("confidential_text_dialog_result")
        fragmentManager.setFragmentResultListener(
            "confidential_text_dialog_result",
            this
        ) { _, _ ->
            onConfidentialTextDialogDismiss()
        }

        mConfidentialTextDialog = ConfidentialTextBottomSheetFragment.newInstance().apply {
            show(fragmentManager, "confidential_text_dialog")
        }
    }

    fun onConfidentialTextDialogDismiss() {
        currentTextMessage?.let { message ->
            
            
            if (!message.isMine && confidentialViewerConsumed) {
                deleteConfidentialMessage(message)
            }
        }
        currentTextMessage = null
        mConfidentialTextDialog = null
    }

    private var mConfidentialAttachmentDialog: ConfidentialAttachmentBottomSheetFragment? = null
    var currentAttachmentMessage: TextChatMessage? = null

    fun showConfidentialAttachmentDialog(message: TextChatMessage) {
        if (!isAdded || activity == null) return

        currentAttachmentMessage = message

        val fragmentManager = requireActivity().supportFragmentManager

        
        fragmentManager.clearFragmentResult("confidential_attachment_dialog_result")
        fragmentManager.setFragmentResultListener(
            "confidential_attachment_dialog_result",
            this
        ) { _, _ ->
            onConfidentialAttachmentDialogDismiss()
        }

        mConfidentialAttachmentDialog = ConfidentialAttachmentBottomSheetFragment.newInstance().apply {
            show(fragmentManager, "confidential_attachment_dialog")
        }
    }

    fun onConfidentialAttachmentDialogDismiss() {
        currentAttachmentMessage?.let { message ->
            if (!message.isMine) {
                deleteConfidentialMessage(message)
            }
        }
        currentAttachmentMessage = null
        mConfidentialAttachmentDialog = null
    }

    fun isScrollViewScrollable(scrollView: ScrollView): Boolean {
        return scrollView.canScrollVertically(-1) || scrollView.canScrollVertically(1)
    }

    



    private fun checkAndRecordConfidentialPlaceholders() {
        
        if (!isAdded || !this::binding.isInitialized || !isVisibleTopNow()) return
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return

        for (i in firstVisible..lastVisible) {
            val message = chatMessageAdapter.currentList.getOrNull(i) ?: continue
            if (message is ConfidentialPlaceholderChatMessage) {
                chatViewModel.markConfidentialPlaceholderAsSeen(message.timeStamp)
            }
        }
    }

    



    fun sendConfidentialViewReceipt(message: ChatMessage) {
        if (!message.isMine && message.isConfidential()) {
            chatViewModel.sendConfidentialViewReceipt(message)
        }
    }

    


    private fun deleteConfidentialMessage(message: ChatMessage) {
        if (!message.isMine && message.isConfidential()) {
            L.i { "[Confidential] Delete message, messageId: ${message.id}, timestamp: ${message.timeStamp}" }
            chatViewModel.deleteMessage(message.id)
        }
    }

    




    private fun showConfidentialViewTipIfNeeded(message: ChatMessage, onProceed: () -> Unit) {
        if (!message.isMine && message.isConfidential() &&
            globalServices.userManager.getUserData()?.hasShownConfidentialTip != true
        ) {
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
                        onProceed()
                    }
                )
            })
        } else {
            onProceed()
        }
    }

    private fun doAfterFirstRender() {
        if (!isAdded) {
            return
        }
        translateBinder?.onFirstRender()
        backPressedCallback = ConversationBackCallback(
            hosted = isInDetailStack(),
            isMenuShowing = { messageActionCoordinator?.isShowing == true },
            isSelecting = { chatViewModel.selectMessagesState.value.editModel },
            dismissMenu = { messageActionCoordinator?.dismiss() },
            exitSelection = { chatViewModel.selectModel(false) },
            closePage = { detailNavigation().pop(this) },
            passOn = { requireActivity().onBackPressedDispatcher.onBackPressed() },
        ).also { it.refreshEnabled() }
        requireActivity().onBackPressedDispatcher.addCallback(detailBackOwner(), backPressedCallback)
        
        dismissWhenLeavingVisibleTop { dismissActionOverlays() }

        
        messageActionCoordinator = MessageActionCoordinator(
            activity = requireActivity(),
            globalConfigsManager = globalConfigsManager,
            translateSupported = translateBackend.isSupported,
        ).apply {
            setActionListener(MessageActionListenerImpl())
        }
    }

    








    
    
    @SuppressLint("ClickableViewAccessibility")
    private fun showNewMessageActionPopup(
        message: TextChatMessage,
        messageView: View,
        bubbleView: View,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean
    ) {
        val coordinator = messageActionCoordinator ?: return

        
        val bubbleBounds = Rect()
        bubbleView.getGlobalVisibleRect(bubbleBounds)

        
        
        val isConfidential = message.isConfidential()
        val textView = if (!isConfidential) {
            bubbleView.findViewById<android.widget.TextView>(R.id.textView)?.apply {
                
                
                setOnTouchListener(null)
            }
        } else {
            null
        }

        coordinator.show(
            message = message,
            messageView = bubbleView,
            textView = textView,
            mostUseEmojis = mostUseEmojis,
            isForForward = isForForward,
            isSaved = isSaved,
            touchPoint = Point(bubbleBounds.centerX(), bubbleBounds.top),
            containerView = binding.recyclerViewMessage,
            enableTextSelection = !isConfidential  
        )
        refreshBackEnabled()
    }

    private fun refreshBackEnabled() {
        if (::backPressedCallback.isInitialized) backPressedCallback.refreshEnabled()
    }

    
    private inner class MessageActionListenerImpl : MessageActionCoordinator.ActionListener {
        override fun onReactionSelected(message: TextChatMessage, emoji: String, isRemove: Boolean) {
            chatViewModel.emojiReaction(
                EmojiReactionEvent(
                    message,
                    emoji,
                    isRemove,  
                    0L,
                    EmojiReactionFrom.EMOJI_DIALOG
                )
            )
        }

        override fun onMoreEmojiClick(message: TextChatMessage) {
            showMoreEmojiDialog(message)
        }

        override fun onQuote(message: TextChatMessage) {
            chatViewModel.quoteMessage(message)
        }

        override fun onCopy(message: TextChatMessage, selectedText: String?) {
            
            if (!selectedText.isNullOrEmpty()) {
                _root_ide_package_.com.difft.android.chat.util.Util.copyToClipboard(requireContext(), selectedText)
                ToastUtil.show(getString(R.string.chat_message_action_copied))
                chatViewModel.sendCopyNotice(listOf(message))
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    if (messageActionHelper.copyMessageContent(message)) {
                        chatViewModel.sendCopyNotice(listOf(message))
                    }
                }
            }
        }

        override fun onTranslate(message: TextChatMessage, selectedText: String?) {
            if (selectedText != null) {
                
                com.difft.android.chat.ui.textpreview.TranslateBottomSheetFragment.show(requireActivity(), selectedText)
            } else {
                showTranslateDialog(message)
            }
        }

        override fun onTranslateOff(message: TextChatMessage) {
            chatViewModel.translateOff(message)
        }

        override fun onForward(message: TextChatMessage, selectedText: String?) {
            if (selectedText != null) {
                
                
                selectChatsUtils.showChatSelectAndSendDialog(
                    requireActivity(),
                    selectedText,
                    sourceConversation = chatViewModel.forWhat,
                    sourceAuthorIds = listOf(message.authorId),
                    combinedForwardMode = CombinedForwardMode.UNKNOWN,
                )
            } else {
                chatViewModel.forwardMessage(message, false)
            }
        }

        override fun onSpeechToText(message: TextChatMessage) {
            chatViewModel.speechToText(requireActivity(), message)
        }

        override fun onSpeechToTextOff(message: TextChatMessage) {
            chatViewModel.speechToTextOff(message)
        }

        override fun onSave(message: TextChatMessage) {
            if (FileUtil.canWriteToMediaStore()) {
                saveAttachment(message)
            } else {
                pendingSaveAttachmentMessage = message
                mediaPermission.launchMultiplePermission(PermissionUtil.picturePermissions)
            }
        }

        override fun onMultiSelect(message: TextChatMessage) {
            chatViewModel.selectModel(true)
            chatViewModel.selectedMessage(message.id, true)
        }

        override fun onSaveToNote(message: TextChatMessage) {
            chatViewModel.forwardMessage(message, true)
        }

        override fun onDeleteSaved(message: TextChatMessage) {
            deleteSaved(message)
        }

        override fun onRecall(message: TextChatMessage) {
            chatViewModel.recallMessage(message)
        }

        override fun onMoreInfo(message: TextChatMessage) {
            currentItemView(message.id)?.let { gotoDetailPage(it, message) }
        }

        override fun onFavoriteGif(message: TextChatMessage) {
            
            
            
            val (attachment, messageId) = resolveActionAttachment(message) ?: return
            
            
            if (attachment.size > MAX_FAVORITE_ASSET_BYTES) {
                L.w { "[ChatMessageListFragment] favorite gif: too large size=${attachment.size} messageId=$messageId" }
                ToastUtil.show(getString(R.string.gif_favorites_add_size_limit))
                return
            }
            val ref = com.difft.android.chat.gif.favorite.buildMessageRef(attachment, messageId)
            if (ref == null) {
                
                L.w { "[ChatMessageListFragment] favorite gif: missing key/fileName messageId=$messageId" }
                ToastUtil.show(getString(R.string.gif_favorites_failed))
                return
            }
            favoriteViewModel.dispatch(com.difft.android.chat.gif.favorite.FavoriteContract.Intent.Favorite(ref))
        }

        override fun onDismiss() {
            
            refreshBackEnabled()
        }
    }

    


    private fun showMoreEmojiDialog(message: TextChatMessage) {
        val emojis = globalConfigsManager.getMostUseEmojis()
        if (emojis.isNullOrEmpty()) return

        var dialog: ComposeDialog? = null
        dialog = dismissWithView(ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            layoutId = R.layout.layout_more_emoji_dialog,
            onDismiss = {
        
    },
            onViewCreated = { v ->
                val rvMostUse = v.findViewById<RecyclerView>(R.id.rv_most_use)
                val tvOthers = v.findViewById<TextView>(R.id.tv_others)
                val rvOthers = v.findViewById<RecyclerView>(R.id.rv_other)

                var mostUseEmojis: List<String>? = null
                var otherEmojis: List<String>? = null
                if (emojis.size > 7) {
                    mostUseEmojis = emojis.subList(0, 7)
                    otherEmojis = emojis.subList(7, emojis.size)
                } else {
                    mostUseEmojis = emojis
                }

                if (mostUseEmojis.isNotEmpty()) {
                    val rvMostUseAdapter = object : ReactionEmojisAdapter(message) {
                        override fun onEmojiSelected(emoji: String, position: Int, remove: Boolean) {
                            chatViewModel.emojiReaction(
                                EmojiReactionEvent(message, emoji, remove, 0L, EmojiReactionFrom.EMOJI_DIALOG)
                            )
                            dialog?.dismiss()
                            messageActionCoordinator?.dismiss()
                        }
                    }
                    rvMostUse.layoutManager = GridLayoutManager(requireContext(), 7)
                    rvMostUse.adapter = rvMostUseAdapter
                    rvMostUseAdapter.submitList(mostUseEmojis)
                }

                if (!otherEmojis.isNullOrEmpty()) {
                    tvOthers.visibility = View.VISIBLE
                    rvOthers.visibility = View.VISIBLE

                    val rvOthersAdapter = object : ReactionEmojisAdapter(message) {
                        override fun onEmojiSelected(emoji: String, position: Int, remove: Boolean) {
                            chatViewModel.emojiReaction(
                                EmojiReactionEvent(message, emoji, remove, 0L, EmojiReactionFrom.EMOJI_DIALOG)
                            )
                            dialog?.dismiss()
                            messageActionCoordinator?.dismiss()
                        }
                    }
                    rvOthers.layoutManager = GridLayoutManager(requireContext(), 7)
                    rvOthers.adapter = rvOthersAdapter
                    rvOthersAdapter.submitList(otherEmojis)
                } else {
                    tvOthers.visibility = View.GONE
                    rvOthers.visibility = View.GONE
                }
            }
        ))
    }

    private fun deleteSaved(data: ChatMessage) {
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.tip),
            message = getString(R.string.chat_message_delete_tips),
            confirmText = getString(R.string.chat_dialog_ok),
            cancelText = getString(R.string.chat_dialog_cancel),
            onConfirm = {
                chatViewModel.deleteMessage(data.id)
            }
        ))
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
                        ToastUtil.show(getString(R.string.not_granted_necessary_permissions))
                    }
                ))
            }
        }
        pendingSaveAttachmentMessage = null
    }

    




    private fun observeVideoProbeResults() {
        viewLifecycleOwner.lifecycleScope.launch {
            videoProbeCoordinator.results.collect { result ->
                if (chatMessageAdapter.currentList.any { it.rendersAttachment(result.attachmentKey) }) {
                    RoomChangeTracker.trackRoom(chatViewModel.forWhat.id, RoomChangeType.MESSAGE)
                }
            }
        }
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
                SaveAttachmentUtil.saveWithUI(requireContext(), attachmentToSave, wait = saveWait)
            }
        } else {
            L.w { "[ChatMessageListFragment] save attachment error, readable=$isFileValid downloadCompleted=" + (progress == null || progress == 100) }
            if (!rescueMissingAttachment(data, attachment, isFileValid)) {
                ToastUtil.show(resources.getString(R.string.ConversationFragment_error_while_saving_attachments_to_sd_card))
            }
        }
    }

    private fun showTranslateDialog(data: TextChatMessage) {
        var dialog: ComposeDialog? = null
        dialog = dismissWithView(ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            layoutId = R.layout.layout_translate_method_dialog,
            onDismiss = {  },
            onViewCreated = { v ->
                v.findViewById<TextView>(R.id.tv_english).setOnClickListener {
                    dialog?.dismiss()
                    chatViewModel.translate(data, TranslateTargetLanguage.EN)
                }

                v.findViewById<TextView>(R.id.tv_chinese).setOnClickListener {
                    dialog?.dismiss()
                    chatViewModel.translate(data, TranslateTargetLanguage.ZH)
                }

                v.findViewById<TextView>(R.id.tv_cancel).setOnClickListener {
                    dialog?.dismiss()
                }
            }
        ))
    }


    private fun dismissActionOverlays() {
        messageActionCoordinator?.dismiss()
        failedMessagePopup?.dismiss()
        failedMessagePopup = null
        failedMessagePopupMessageId = null
    }


    



    private fun downloadAttachment(message: TextChatMessage, attachment: Attachment) {
        val filePath = AttachmentPathResolver.fileFor(attachment)
        
        val autoSave = chatMessageAdapter.shouldSaveToPhotos &&
                !message.isConfidential() &&
                (attachment.isImage() || attachment.isPlayableVideo())
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

    









    private fun rescueMissingAttachment(
        message: TextChatMessage,
        attachment: Attachment?,
        isFileValid: Boolean
    ): Boolean {
        val progress = message.getAttachmentProgress()
        if (attachment == null ||
            !AttachmentDownloadDecision.shouldRescueMissingFile(isFileValid, attachment.status, progress)
        ) return false
        L.i { "[ChatMessageListFragment] rescuing missing attachment localId=${attachment.localId} messageId=${message.id}" }
        downloadAttachment(message, attachment)
        ToastUtil.showLong(R.string.file_preparing)
        return true
    }

    
    private fun onAttachmentMessageClick(data: TextChatMessage) {
        val attachment = data.attachment ?: return
        
        
        
        
        val consumed = handleAttachmentTapBeforeOpen(
            attachment,
            data.getAttachmentProgress(),
            rescueMissingFile = !attachment.isLongText()
        ) { downloadAttachment(data, attachment) }
        if (consumed) return

        if (attachment.opensInAppViewer(data.isConfidential())) {
            if (data.isConfidential()) {
                showConfidentialViewTipIfNeeded(data) {
                    openPreview(data)
                }
            } else {
                openPreview(data)
            }
        } else if (attachment.isAudioMessage() || attachment.isAudioFile()) {
            if (data.isConfidential()) {
                showConfidentialViewTipIfNeeded(data) {
                    
                    
                    showConfidentialAudioDialog(data)
                }
            }
        } else if (attachment.isLongText()) {
            
            if (data.isConfidential() && !TextUtils.isEmpty(data.message)) {
                showConfidentialViewTipIfNeeded(data) {
                    showConfidentialTextDialog(data)
                }
            }
        } else {
            
            
            if (data.isConfidential()) {
                showConfidentialViewTipIfNeeded(data) {
                    sendConfidentialViewReceipt(data)
                    showConfidentialAttachmentDialog(data)
                }
            } else {
                openAttachmentFile(data)
            }
        }
    }

    
    private fun openAttachmentFile(message: TextChatMessage) {
        
        
        if (AttachmentDownloadDecision.isTransferInFlight(message.getAttachmentProgress())) return
        val filePath = message.attachment?.let { AttachmentPathResolver.fileFor(it) } ?: ""
        if (!EncryptedAttachmentAccess.isReadable(filePath)) {
            if (!rescueMissingAttachment(message, message.attachment, isFileValid = false)) {
                ToastUtil.showLong(R.string.file_load_error)
            }
            return
        }
        requireContext().viewFile(filePath)
    }

    private fun openPreview(message: TextChatMessage) {
        val filePath = message.attachment?.let { AttachmentPathResolver.fileFor(it) } ?: ""
        if (!EncryptedAttachmentAccess.isReadable(filePath)) {
            if (!rescueMissingAttachment(message, message.attachment, isFileValid = false)) {
                ToastUtil.showLong(R.string.file_load_error)
            }
            return
        }
        if (!message.isMine && message.isConfidential()) {
            chatViewModel.sendConfidentialViewReceipt(message)
        }
        val isConfidential = message.isConfidential()
        viewLifecycleOwner.lifecycleScope.launch {
            val mediaListInfo = withContext(Dispatchers.IO) {
                val mediaList = arrayListOf<LocalMedia>()

                if (isConfidential) {
                    
                    message.attachment?.let { mediaList.add(AttachmentPreview.localMediaFor(it)) }
                } else {
                    chatMessageAdapter.currentList.forEach { msg ->
                        if (msg !is TextChatMessage) return@forEach
                        
                        if (msg.isConfidential()) return@forEach

                        msg.attachment?.takeIf { it.isImage() || it.isPlayableVideo() }?.let { attachment ->
                            attachment.fileName ?: return@let
                            
                            
                            
                            
                            val path = AttachmentPathResolver.materializedFileFor(attachment, msg.id)
                            if (EncryptedAttachmentAccess.isReadable(path)) {
                                mediaList.add(AttachmentPreview.localMediaFor(attachment))
                            }
                            return@forEach
                        }

                        val forward = msg.forwardContext?.forwards?.singleOrNull() ?: return@forEach
                        val forwardAttachment = forward.attachments?.firstOrNull()
                        if (forwardAttachment?.isImage() == true || forwardAttachment?.isPlayableVideo() == true) {
                            val forwardMessage = generateMessageFromForward(forward) as? TextChatMessage ?: return@forEach
                            val forwardMsgAttachment = forwardMessage.attachment ?: return@forEach
                            forwardMsgAttachment.fileName ?: return@forEach
                            
                            
                            
                            
                            val path = AttachmentPathResolver.materializedFileFor(forwardMsgAttachment, forwardMessage.id)
                            if (EncryptedAttachmentAccess.isReadable(path)) {
                                mediaList.add(AttachmentPreview.localMediaFor(forwardMsgAttachment))
                            }
                        }
                    }
                }

                val position = if (isConfidential) 0 else mediaList.indexOfFirst { it.realPath == filePath }
                mediaList to position
            }

            if (!isAdded || view == null) return@launch
            if (mediaListInfo.first.isEmpty()) {
                L.w { "media list is empty" }
                ToastUtil.showLong(R.string.file_load_error)
                return@launch
            }

            PictureSelector.create(requireActivity())
                .openPreview()
                .applyExportPolicy(isConfidential)
                .isAutoVideoPlay(true)
                .isVideoPauseResumePlay(true)
                .isShowConfidentialTip(isConfidential)
                
                .isHideBottomClose(isConfidential)
                .setVideoPlayerEngine(ExoVideoPlayerEngine())
                .setAttachViewLifecycle(object : IBridgeViewLifecycle {
                    override fun onViewCreated(fragment: Fragment?, view: View?, savedInstanceState: Bundle?) {
        
    }

                    override fun onDestroy(fragment: Fragment?) {
                        if (message.isConfidential()) {
                            if (!message.isMine) {
                                deleteConfidentialMessage(message)
                            }
                        }
                    }
                })
                .setDefaultLanguage(LanguageConfig.ENGLISH)
                .setLanguage(PictureSelectorUtils.getLanguage(requireContext()))
                .setSelectorUIStyle(PictureSelectorUtils.getSelectorStyle(requireContext()))
                .setImageEngine(GlideEngine.createGlideEngine())
                .setExternalPreviewEventListener(object : OnExternalPreviewEventListener {
                    override fun onPreviewDelete(position: Int) {
        
    }

                    override fun onLongPressDownload(context: Context?, media: LocalMedia?): Boolean {
                        return false
                    }
                }).startActivityPreview(mediaListInfo.second, false, mediaListInfo.first)
        }
    }

    
    private fun currentItemView(messageId: String): View? =
        resolveByMessageId(
            messageId,
            indexOf = { id -> chatMessageAdapter.currentList.indexOfFirst { it.id == id } },
            itemAt = { pos -> binding.recyclerViewMessage.findViewHolderForAdapterPosition(pos)?.itemView }
        )

    private fun gotoDetailPage(rootView: View, message: TextChatMessage) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val bitmapKey = withContext(Dispatchers.IO) {
                    val view = rootView.findViewById<ChatMessageContainerView>(R.id.contentContainer)
                    val bitmap = createBitmap(view.width, view.height)
                    bitmap.eraseColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg1))
                    val canvas = Canvas(bitmap)
                    view.draw(canvas)

                    val byteArrayOutputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 50, byteArrayOutputStream)
                    val byteArray = byteArrayOutputStream.toByteArray()
                    val compressedBitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    byteArrayOutputStream.close()
                    MessageDetailBitmapHolder.put(compressedBitmap)
                }
                val opened = isAdded && view != null && detailNavigation().push(
                    this@ChatMessageListFragment,
                    MessageDetailDestination(message.id, bitmapKey, chatViewModel.forWhat.id),
                )
                if (!opened) MessageDetailBitmapHolder.release(bitmapKey)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[ChatMessageListFragment] showMessageDetail error: ${e.stackTraceToString()}" }
            }
        }
    }

    private var isLoadingTop = false
    private var isLoadingBottom = false

    
    private fun observeScrollPrefetch() {
        viewLifecycleOwner.lifecycleScope.launchScrollPrefetch(
            signals = scrollPrefetchSignals,
            lifecycle = viewLifecycleOwner.lifecycle,
        ) { checkAndLoadMessages(PageLoadTrigger.PREFETCH) }
    }

    
    private fun currentPageLoadDecision(): PageLoadDecision {
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
            ?: return PageLoadDecision.NONE
        val state = chatViewModel.chatMessageListUIState.value
        return decidePageLoad(
            userScrolling = userScrolling,
            firstVisible = layoutManager.findFirstVisibleItemPosition(),
            lastVisible = layoutManager.findLastVisibleItemPosition(),
            itemCount = layoutManager.itemCount,
            isAtBottom = isAtBottom(layoutManager),
            
            hasReachedHistoryStart = state?.hasReachedHistoryStart ?: false,
            hasReachedLatest = state?.hasReachedLatest ?: false,
        )
    }

    




    private suspend fun checkAndLoadMessages(trigger: PageLoadTrigger) = runGatedPageLoad(
        gate = pageLoadGate,
        waitIfBusy = trigger == PageLoadTrigger.IDLE,
        decide = ::currentPageLoadDecision,
        loadNewer = { loadNextPage(trigger) },
        loadOlder = { loadPreviousPage(trigger) },
    )

    



    private fun maybeTrimWindow(state: ChatMessageListUIState) {
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        val trim = shouldTrimWindow(
            windowSize = state.windowSize,
            highWater = TRIM_HIGH_WATER,
            
            
            
            isLoadingPage = isLoadingTop || isLoadingBottom || pageLoadGate.isLocked,
        ) { isAtBottom(layoutManager) }
        if (!trim) return
        viewLifecycleOwner.lifecycleScope.launch { chatViewModel.trimToLatest() }
    }

    private fun notifyItemChangedAndAnchorBottom(position: Int) {
        if (position < 0) return
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
        val wasAtBottom = layoutManager?.let { isAtBottom(it) } == true
        chatMessageAdapter.notifyItemChanged(position)
        if (wasAtBottom) {
            binding.recyclerViewMessage.noSmoothScrollToBottom()
        }
    }

    private fun isAtBottom(layoutManager: LinearLayoutManager): Boolean {
        val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()

        if (lastVisibleItemPosition == layoutManager.itemCount - 1) {
            val verticalScrollRange = binding.recyclerViewMessage.computeVerticalScrollRange()
            val verticalScrollOffset = binding.recyclerViewMessage.computeVerticalScrollOffset()
            val verticalScrollExtent = binding.recyclerViewMessage.computeVerticalScrollExtent()

            return verticalScrollRange - verticalScrollOffset <= verticalScrollExtent
        }

        return false

    }

    private suspend fun loadNextPage(trigger: PageLoadTrigger) {
        if (!isLoadingBottom) {
            isLoadingBottom = true
            
            
            try {
                logPageLoadStart("loadNextPage", trigger)
                chatViewModel.loadNextPage()
            } finally {
                isLoadingBottom = false
            }
            L.i { "[message] loadNextPage done" }
        }
    }

    private suspend fun loadPreviousPage(trigger: PageLoadTrigger) {
        if (!isLoadingTop) {
            isLoadingTop = true
            
            try {
                logPageLoadStart("loadPreviousPage", trigger)
                chatViewModel.loadPreviousPage()
            } finally {
                isLoadingTop = false
            }
            L.i { "[message] loadPreviousPage Done" }
        }
    }

    
    private fun logPageLoadStart(what: String, trigger: PageLoadTrigger) {
        val lm = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
        L.i {
            "[message] $what start src=${trigger.tag} first=${lm?.findFirstVisibleItemPosition()} " +
                "last=${lm?.findLastVisibleItemPosition()} count=${lm?.itemCount}"
        }
    }

    fun getTouchedPosition(e: MotionEvent): Int {
        val child = binding.recyclerViewMessage.findChildViewUnder(e.x, e.y)
        return if (child != null) {
            binding.recyclerViewMessage.getChildAdapterPosition(child)
        } else {
            RecyclerView.NO_POSITION
        }
    }

    private fun showDayTime(firstVisibleItemPosition: Int) {
        if (firstVisibleItemPosition == RecyclerView.NO_POSITION) {
            binding.cvDayTime.visibility = View.GONE
            return
        }

        
        
        if (!binding.recyclerViewMessage.canScrollVertically(1) && !binding.recyclerViewMessage.canScrollVertically(-1)) {
            binding.cvDayTime.visibility = View.GONE
            return
        }

        val data = chatMessageAdapter.currentList.getOrNull(firstVisibleItemPosition) ?: return
        
        if (data.systemShowTimestamp == MessageModel.ARCHIVE_TOMBSTONE_SORT_SENTINEL) {
            binding.cvDayTime.visibility = View.GONE
            return
        }
        
        
        if (data is EncryptionHeaderChatMessage) {
            binding.cvDayTime.visibility = View.GONE
            return
        }
        binding.cvDayTime.visibility = View.VISIBLE
        binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
            requireContext(), language, data.systemShowTimestamp
        )
    }

    private fun getLastVisibleMessage(): ChatMessage? {
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return null
        val lastPos = layoutManager.findLastVisibleItemPosition()
        return chatMessageAdapter.currentList.getOrNull(lastPos)
    }

    private fun getForwardTitle(forwardContext: ForwardContext): String {
        val forwards = forwardContext.forwards
        return if (forwards?.firstOrNull()?.isFromGroup == true) {
            getString(R.string.group_chat_history)
        } else {
            val authorId = forwards?.firstOrNull()?.author ?: ""
            val author = chatViewModel.contactorCache.getContactor(authorId)
            if (author != null) {
                getString(R.string.chat_history_for, author.getDisplayNameWithoutRemarkForUI())
            } else {
                getString(R.string.chat_history_for, authorId.formatBase58Id())
            }
        }
    }

    private fun setAudioObservers() {
        
        val globalSpeed = globalServices.userManager.getUserData()?.voicePlaybackSpeed ?: 1.0f
        AudioMessageManager.setPlaybackSpeed(globalSpeed)

        
        viewLifecycleOwner.lifecycleScope.launch {
            AudioMessageManager.playStatusUpdate.collect { (message, status) ->
                when (status) {
                    AudioMessageManager.PLAY_STATUS_START -> {
                        
                        updateVoiceSpeedButtonVisibility(message, true)
                    }

                    AudioMessageManager.PLAY_STATUS_PAUSED -> {
                        
                        
                        updateVoiceSpeedButtonVisibility(message, false)
                    }

                    AudioMessageManager.PLAY_STATUS_COMPLETE -> {
                        
                        updateVoiceSpeedButtonVisibility(message, false)

                        
                        if (message.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY) {
                            chatViewModel.updatePlayStatus(message, AudioMessageManager.PLAY_STATUS_PLAYED)
                        }
                        
                        val nextAutoPlayMessage = chatMessageAdapter.currentList.filter { msg ->
                            msg.systemShowTimestamp > message.systemShowTimestamp
                                    && msg is TextChatMessage
                                    && msg.isAttachmentMessage()
                                    && msg.mode != SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
                                    && msg.attachment?.isAudioMessage() == true
                                    && msg.authorId == message.authorId
                                    && msg.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY
                        }.minByOrNull { msg -> msg.systemShowTimestamp }
                        nextAutoPlayMessage?.let { next ->
                            val nextAttachment = (next as TextChatMessage).attachment ?: return@let
                            
                            
                            
                            
                            val attachmentPath = withContext(Dispatchers.IO) {
                                AttachmentPathResolver.materializedFileFor(nextAttachment, next.id)
                            }
                            AudioMessageManager.playOrPauseAudio(next, attachmentPath)
                        }
                    }
                }
            }
        }

        
        viewLifecycleOwner.lifecycleScope.launch {
            AudioMessageManager.playbackSpeed.collect { speed ->
                
                AudioMessageManager.currentPlayingMessage?.let { message ->
                    updateVoiceSpeedButtonVisibility(message, true)
                }
            }
        }
    }

    


    private fun updateVoiceSpeedButtonVisibility(message: TextChatMessage, visible: Boolean) {
        val position = chatMessageAdapter.currentList.indexOfFirst { it.id == message.id }
        if (position != -1) {
            val viewHolder = binding.recyclerViewMessage.findViewHolderForAdapterPosition(position)
            if (viewHolder is ChatMessageViewHolder.Message) {
                val currentSpeed = AudioMessageManager.playbackSpeed.value
                viewHolder.updateVoiceSpeedButton(visible, currentSpeed) {
                    
                    AudioMessageManager.cyclePlaybackSpeed()
                }
            }
        }
    }

    
















    private fun setupCallHeaderScrollCompensation() {
        var lastKnownHeight = 0
        binding.recyclerViewMessage.addOnLayoutChangeListener(
            object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    val newHeight = bottom - top
                    if (newHeight <= 0) return

                    val heightDecreased = lastKnownHeight > 0 && newHeight < lastKnownHeight
                    lastKnownHeight = newHeight

                    if (!heightDecreased) return
                    if (!needsCallHeaderScrollCompensation) return
                    if (!onGoingCallStateManager.chatHeaderCallVisibility.value) return

                    needsCallHeaderScrollCompensation = false
                    v.removeOnLayoutChangeListener(this)

                    v.post {
                        if (!isAdded || view == null || !this@ChatMessageListFragment::binding.isInitialized) return@post
                        if ((binding.recyclerViewMessage.adapter?.itemCount ?: 0) == 0) return@post
                        binding.recyclerViewMessage.noSmoothScrollToBottom()
                    }
                }
            }
        )
    }

    class ConfidentialAudioBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialAudioBottomSheetFragment {
                return ConfidentialAudioBottomSheetFragment()
            }
        }

        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_audio_dialog

        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
                dismiss()
            }

            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment()
            val currentMessage = chatFragment?.currentAudioMessage
            if (chatFragment == null || currentMessage == null) {
                dismissRestoredConfidentialSheet("audio")
                return
            }

            chatFragment.voiceMessageView = view.findViewById(R.id.voice_message_view)
            chatFragment.voiceMessageView?.setAudioMessage(currentMessage)

            
            
            if (!currentMessage.isMine) {
                chatFragment.voiceMessageView?.onConfidentialPlayStarted = { message ->
                    chatFragment.consumeConfidentialInViewer(message)
                }
            }

            val clVoiceMessageView = view.findViewById<ConstraintLayout>(R.id.cl_voice_message_view)
            
            val theme = ChatThemeSetting.current
            clVoiceMessageView?.background = bubbleFillDrawable(requireContext(), currentMessage.isMine, theme)
            chatFragment.voiceMessageView?.applyPalette(BubblePalette.of(requireContext(), sent = currentMessage.isMine, theme))
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_audio_dialog_result", Bundle())
            } catch (e: Exception) {
                
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_audio failed: ${e.stackTraceToString()}" }
            }
        }
    }

    class ConfidentialTextBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialTextBottomSheetFragment {
                return ConfidentialTextBottomSheetFragment()
            }
        }

        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_text_dialog

        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        
        @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {

            view.findViewById<ImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
                dismiss()
            }

            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment()
            val currentMessage = chatFragment?.currentTextMessage
            if (chatFragment == null || currentMessage == null) {
                dismissRestoredConfidentialSheet("text")
                return
            }
            val bodyText = currentMessage.message.toString()

            val textView = view.findViewById<TextView>(R.id.textView)
            val llConfidential = view.findViewById<LinearLayout>(R.id.ll_confidential)
            val scrollView = view.findViewById<ScrollView>(R.id.sv_confidential)
            val tvTitle = view.findViewById<TextView>(R.id.tv_title)

            textView.autoLinkMask = 0
            textView.movementMethod = null
            textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

            
            fun renderConfidentialText(text: String) {
                LinkTextUtils.setMarkdownToTextview(requireContext(), text, textView, mentions = null)
                textView.post {
                    val textViewLayout = textView.layout
                    if (textView.lineCount > 0) {
                        llConfidential.removeAllViews()
                        for (i in 0 until textView.lineCount) {
                            val lineView = layoutInflater.inflate(
                                R.layout.chat_item_content_text_confidential_dialog,
                                llConfidential,
                                false
                            )

                            val top = textViewLayout.getLineTop(i)
                            val bottom = textViewLayout.getLineBottom(i)
                            val lineHeight = if (i == textView.lineCount - 1) {
                                bottom - top + textView.lineSpacingExtra.toInt()
                            } else {
                                bottom - top
                            }
                            lineView.layoutParams.height = lineHeight

                            val tvNumber = lineView.findViewById<TextView>(R.id.tv_line_number)
                            tvNumber.text = (i + 1).toString()

                            llConfidential.addView(lineView)
                        }

                        llConfidential.post {
                            llConfidential.setOnTouchListener { v, event ->
                                val itemHeight = llConfidential.getChildAt(0).height
                                val touchX = event.x.toInt()
                                val regionWidth = v.width / 3

                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        
                                        chatFragment.consumeConfidentialInViewer(currentMessage)
                                        v.parent.requestDisallowInterceptTouchEvent(touchX <= regionWidth)
                                    }

                                    MotionEvent.ACTION_MOVE -> {
                                        val currentY = event.y.toInt()
                                        val currentPosition = (currentY / itemHeight).coerceIn(0, llConfidential.childCount - 1)
                                        val startLine = (currentPosition - 2).coerceAtLeast(0)
                                        val endLine = (currentPosition + 2).coerceAtMost(llConfidential.childCount - 1)

                                        llConfidential.children.forEachIndexed { index, child ->
                                            child.visibility = if (index in startLine..endLine) View.INVISIBLE else View.VISIBLE
                                        }
                                        v.parent.requestDisallowInterceptTouchEvent(touchX <= regionWidth)
                                    }

                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        llConfidential.children.forEach { it.visibility = View.VISIBLE }
                                        v.parent.requestDisallowInterceptTouchEvent(false)
                                    }
                                }
                                true
                            }

                            tvTitle.visibility = if (chatFragment.isScrollViewScrollable(scrollView)) View.VISIBLE else View.GONE
                        }
                    }
                }
            }

            renderConfidentialText(bodyText)

            
            val attachment = currentMessage.attachment
            if (attachment?.isLongText() == true) {
                val ownerMessageId = currentMessage.id
                val appContext = requireContext().applicationContext
                viewLifecycleOwner.lifecycleScope.launch {
                    val fullText = withContext(Dispatchers.IO) {
                        
                        
                        val filePath = AttachmentPathResolver.materializedFileFor(attachment, ownerMessageId)
                        if (EncryptedAttachmentAccess.isReadable(filePath)) {
                            
                            try { EncryptedAttachmentAccess.readDecryptedText(appContext, filePath) } catch (_: Exception) { null }
                        } else null
                    }
                    if (fullText != null) renderConfidentialText(fullText)
                }
            }
        }


        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_text_dialog_result", Bundle())
            } catch (e: Exception) {
                
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_text failed: ${e.stackTraceToString()}" }
            }
        }
    }

    class TextContentBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            private const val ARG_TEXT_CONTENT = "text_content"
            private const val ARG_MENTIONS = "mentions"

            fun newInstance(textContent: String, mentions: List<difft.android.messageserialization.model.Mention>? = null): TextContentBottomSheetFragment {
                return TextContentBottomSheetFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_TEXT_CONTENT, textContent)
                        mentions?.let { putSerializable(ARG_MENTIONS, ArrayList(it)) }
                    }
                }
            }
        }

        override fun getContentLayoutResId(): Int = R.layout.chat_layout_text_content_dialog

        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
                dismiss()
            }

            val textContent = arguments?.getString(ARG_TEXT_CONTENT) ?: return

            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            val mentions = arguments?.getSerializable(ARG_MENTIONS) as? ArrayList<difft.android.messageserialization.model.Mention>

            val textView = view.findViewById<TextView>(R.id.textView)

            textView.autoLinkMask = 0
            textView.movementMethod = null

            textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

            LinkTextUtils.setMarkdownToTextview(
                requireContext(),
                textContent,
                textView,
                mentions
            )
        }
    }

    class ConfidentialAttachmentBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialAttachmentBottomSheetFragment {
                return ConfidentialAttachmentBottomSheetFragment()
            }
        }

        private var attachMessageView: com.difft.android.chat.widget.AttachMessageView? = null
        private var currentMessage: TextChatMessage? = null

        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_attachment_dialog

        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).apply { floatAsTopBarButton(leading = false) }.setOnClickListener {
                dismiss()
            }

            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment()
            val message = chatFragment?.currentAttachmentMessage
            if (chatFragment == null || message == null) {
                dismissRestoredConfidentialSheet("attachment")
                return
            }
            currentMessage = message

            val clAttachmentView = view.findViewById<ConstraintLayout>(R.id.cl_attachment_view)
            val theme = ChatThemeSetting.current
            clAttachmentView?.background = bubbleFillDrawable(requireContext(), message.isMine, theme)

            attachMessageView = view.findViewById(R.id.attach_message_view)
            attachMessageView?.setupAttachmentView(message)
            
            attachMessageView?.applyPalette(BubblePalette.of(requireContext(), sent = message.isMine, theme))
            attachMessageView?.setOnClickListener {
                
                
                
                
                val filePath = message.attachment?.let { AttachmentPathResolver.fileFor(it) } ?: ""
                
                if (message.attachment?.mayOpenExternally(isConfidential = true) == false) {
                    L.w { "[ChatMessageListFragment] confidential video blocked from external open messageId=${message.id}" }
                } else if (EncryptedAttachmentAccess.isReadable(filePath)) {
                    requireContext().viewFile(filePath)
                } else if (!chatFragment.rescueMissingAttachment(message, message.attachment, isFileValid = false)) {
                    ToastUtil.showLong(R.string.file_load_error)
                }
            }
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_attachment_dialog_result", Bundle())
            } catch (e: Exception) {
                
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_attachment failed: ${e.stackTraceToString()}" }
            }
        }
    }
}





internal fun shouldTrimWindow(
    windowSize: Int,
    highWater: Int,
    isLoadingPage: Boolean,
    isAtBottom: () -> Boolean,
): Boolean = windowSize >= highWater && !isLoadingPage && isAtBottom()





internal fun <T> resolveByMessageId(
    messageId: String,
    indexOf: (String) -> Int,
    itemAt: (Int) -> T?
): T? {
    val position = indexOf(messageId)
    return if (position >= 0) itemAt(position) else null
}






internal fun resolveActionAttachment(message: TextChatMessage): Pair<Attachment, String>? {
    val attachment = message.singleForwardableAttachment() ?: return null
    return attachment to AttachmentPathResolver.directoryKeyFor(attachment)
}












internal fun handleAttachmentTapBeforeOpen(
    attachment: Attachment,
    progress: Int?,
    rescueMissingFile: Boolean = true,
    download: () -> Unit
): Boolean {
    val action = AttachmentDownloadDecision.tapAction(
        status = attachment.status,
        progress = progress,
        isFileValid = EncryptedAttachmentAccess.isReadable(AttachmentPathResolver.fileFor(attachment)),
        sizeBytes = attachment.size
    )
    when (action) {
        is AttachmentTapAction.Blocked -> ToastUtil.show(action.reason.toastRes())
        AttachmentTapAction.Download -> download()
        AttachmentTapAction.Rescue -> {
            if (!rescueMissingFile) return false
            download()
            ToastUtil.showLong(R.string.file_preparing)
        }
        AttachmentTapAction.Open -> return false
    }
    return true
}

private fun AttachmentTapBlockedReason.toastRes(): Int = when (this) {
    AttachmentTapBlockedReason.EXPIRED -> R.string.file_expired_cannot_view
    AttachmentTapBlockedReason.DOWNLOADING -> R.string.file_preparing
}







internal fun attachmentOnlyForward(forwards: List<Forward>): Attachment? {
    val forward = forwards.singleOrNull() ?: return null
    if (!forward.text.isNullOrBlank() || !forward.forwards.isNullOrEmpty()) return null
    return forward.attachments?.singleOrNull()
}





private fun BaseBottomSheetDialogFragment.dismissRestoredConfidentialSheet(kind: String) {
    L.i { "[Confidential] restored sheet without message, dismissing kind=$kind" }
    dismissAllowingStateLoss()
}
