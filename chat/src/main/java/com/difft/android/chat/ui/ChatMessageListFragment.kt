package com.difft.android.chat.ui

import android.annotation.SuppressLint
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
import com.difft.android.base.ui.noSmoothScrollToBottom
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.R
import com.difft.android.chat.ScrollAction
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.SendType
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.contacts.contactsdetail.ContactDetailBottomSheetDialogFragment
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.databinding.ChatFragmentMessageListBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.ConfidentialPlaceholderChatMessage
import com.difft.android.chat.message.MessageActionHelper
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.messageaction.FailedMessageActionPopup
import com.difft.android.chat.ui.messageaction.MessageActionCoordinator
import com.difft.android.chat.widget.AudioAmplitudesHelper
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.VoiceMessageView
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.config.GlobalConfigsManager
import com.luck.picture.lib.basic.IBridgeViewLifecycle
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.engine.ExoVideoPlayerEngine
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.TranslateTargetLanguage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.components.reaction.ReactionEmojisAdapter
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.StorageUtil
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.viewFile
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import util.TimeFormatter
import util.concurrent.TTExecutors
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class ChatMessageListFragment : Fragment() {

    // Keyboard visibility listener is managed by KeyboardVisibilityEvent with viewLifecycleOwner
    private var isKeyboardListenerRegistered = false

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private val messageActionHelper by lazy {
        MessageActionHelper(requireActivity(), viewLifecycleOwner.lifecycleScope)
    }

    private var isFriend = false
    private var mScrollToPosition = -1

    private lateinit var binding: ChatFragmentMessageListBinding

    // Use parent fragment as ViewModel owner when nested (in ChatFragment/GroupChatFragment),
    // otherwise use activity (when directly in Activity).
    // Parent fragment initializes ViewModels in onCreateView before child fragments are created.
    private val chatViewModel: ChatMessageViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    private val chatSettingViewModel: ChatSettingViewModel by viewModels(
        ownerProducer = { parentFragment ?: requireActivity() }
    )
    private val chatMessageAdapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(chatViewModel.forWhat, chatViewModel.contactorCache) {
            override fun onItemClick(rootView: View, data: ChatMessage) {
                if (data !is TextChatMessage) return
                if (data.isMine && data.sendStatus == SendType.SentFailed.rawValue) {
                    showResendPop(rootView, data)
                    return
                }
                if (data.forwardContext != null) {
                    val forwardContext = data.forwardContext ?: return
                    val forwards = forwardContext.forwards ?: return
                    if (data.isConfidential()) {
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
                            val progress = data.getAttachmentProgress()

                            // Use authorityId for messageId to match file path in ImageAndVideoMessageView
                            val forwardMessageId = attachment.authorityId.toString()
                            if (shouldTriggerManualDownload(attachment, progress, forwardMessageId)) {
                                downloadAttachment(forwardMessageId, attachment, data)
                                return
                            }

                            if (attachment.isImage() || attachment.isVideo()) {
                                openPreview(generateMessageFromForward(forward) as TextChatMessage)
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
                    // Shared contact: send receipt and delete immediately (only needs contact ID)
                    sendConfidentialViewReceipt(data)
                    deleteConfidentialMessage(data)
                } else if (data.isAttachmentMessage()) {
                    val attachment = data.attachment ?: return
                    val progress = data.getAttachmentProgress()

                    if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                        downloadAttachment(data.id, attachment, data)
                        return
                    }

                    if (attachment.isImage() || attachment.isVideo()) {
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
                                // VoiceMessageView handles download progress internally
                                // View receipt is sent when user clicks play (not when dialog opens)
                                showConfidentialAudioDialog(data)
                            }
                        }
                    } else {
                        // 普通附件（非图片/视频/音频）机密消息：打开时发送回执，关闭时删除
                        if (data.isConfidential()) {
                            showConfidentialViewTipIfNeeded(data) {
                                sendConfidentialViewReceipt(data)
                                showConfidentialAttachmentDialog(data)
                            }
                        }
                    }
                } else {
                    if (data.isConfidential() && (!TextUtils.isEmpty(data.card?.content) || !TextUtils.isEmpty(
                            data.message
                        ))
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

                // Delay to wait for view layout to stabilize before showing reaction menu
                // Use longer delay when keyboard is closing (layout needs to resize)
                val delayMs = if (isKeyboardVisible) 300L else 100L
                viewLifecycleOwner.lifecycleScope.launch {
                    delay(delayMs)

                    // Check if the view is still attached after delay (RecyclerView may have recycled it)
                    if (!rootView.isAttachedToWindow) {
                        return@launch
                    }

                    // Get bubble view (ChatMessageContainerView with id contentContainer)
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
                EmojiReactionActivity.startActivity(
                    requireActivity(),
                    if (chatViewModel.forWhat is For.Group) chatViewModel.forWhat.id else null,
                    if (chatViewModel.forWhat !is For.Group) chatViewModel.forWhat.id else null,
                    message.id
                )
            }

            override fun onSelectedMessage(messageId: String, selected: Boolean) {
                chatViewModel.selectedMessage(messageId, selected)
            }
        }
    }

    private var messageActionCoordinator: MessageActionCoordinator? = null
    private lateinit var backPressedCallback: BackPressedDelegate

    private var isHapticFeedbackTriggered = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop by lazy { android.view.ViewConfiguration.get(requireContext()).scaledTouchSlop }

    private val language by lazy {
        LanguageUtils.getLanguage(requireContext())
    }

    private var userScrolling = false
    private var isDragging = false

    private var lastScrollPos: Int = -1 //上次滚动位置
    private var lastMessageCount: Int = -1 //上次消息数量
    private var lastDayTimeUpdate: Long = 0 //上次更新时间
    private val dayTimeUpdateInterval: Long = 500 //更新间隔时间(毫秒)
    private var lastPlaceholderCheckTime: Long = 0
    private companion object {
        const val PLACEHOLDER_CHECK_INTERVAL = 500L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentMessageListBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }


    @SuppressLint("ClickableViewAccessibility", "NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set chat background for popup mode only
        // Note: In dual-pane mode, background is set on detail_pane by IndexActivity
        // to keep it fixed when keyboard appears (Fragment root receives IME padding)
        if (activity is ChatPopupActivity) {
            binding.recyclerViewMessage.background = ChatBackgroundDrawable(requireContext())
        }

        val loadingAnimFile = if (ThemeUtil.isDarkNotificationTheme(requireContext())) {
            "tt_loading_dark.json"
        } else {
            "tt_loading_light.json"
        }

        registerCallStatusViewListener()

        binding.lottieViewLoading.setAnimation(loadingAnimFile)
        binding.lottieViewLoading.playAnimation()

        binding.recyclerViewMessage.apply {
            val linearLayoutManager = LinearLayoutManager(this.context).apply {
//                stackFromEnd = true
            }
            layoutManager = linearLayoutManager
            itemAnimator = null

            adapter = chatMessageAdapter

            addOnScrollListener(object : OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    when (newState) {
                        RecyclerView.SCROLL_STATE_IDLE -> {
                            isDragging = false
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (userScrolling) {
                                    checkAndLoadMessages(linearLayoutManager)
                                    hideDayTime()
                                }
                                sendAndUpdateMessageRead()
                                checkAndRecordConfidentialPlaceholders()
                                userScrolling = false
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            isDragging = true
                            userScrolling = true
                        }

                        RecyclerView.SCROLL_STATE_SETTLING -> {
                            isDragging = false
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    // 实时更新位置
                    lastScrollPos = firstVisibleItemPosition

                    val currentTime = System.currentTimeMillis()

                    // 对showDayTime进行节流
                    if (userScrolling) {
                        if (currentTime - lastDayTimeUpdate >= dayTimeUpdateInterval) {
                            showDayTime(firstVisibleItemPosition)
                            lastDayTimeUpdate = currentTime
                        }
                    }

                    // Detect confidential placeholders during dragging (throttled 500ms, skip flying state)
                    if (isDragging && currentTime - lastPlaceholderCheckTime >= PLACEHOLDER_CHECK_INTERVAL) {
                        checkAndRecordConfidentialPlaceholders()
                        lastPlaceholderCheckTime = currentTime
                    }
                }
            })

            //右滑item进行引用消息功能
            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.RIGHT, ItemTouchHelper.RIGHT) {

                override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                    val swipeFlags = ItemTouchHelper.END
                    return makeMovementFlags(0, swipeFlags)
                }

                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    // 临时启用 DefaultItemAnimator，让 notifyItemChanged 触发平滑的复位动画
                    binding.recyclerViewMessage.itemAnimator = DefaultItemAnimator()
                    val position = viewHolder.bindingAdapterPosition

                    lifecycleScope.launch {
                        chatMessageAdapter.notifyItemChanged(position)

                        // 等待动画完成后（DefaultItemAnimator 默认 changeDuration 为 250ms）触发引用
                        delay(300)
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
                    return gestureDetector.onTouchEvent(e)
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
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

        registerKeyboardVisibilityListener()

        chatViewModel.chatActionsShow
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                scrollToBottom()
            }, { L.w { "[ChatMessageListFragment] observe chatActionsShow error: ${it.stackTraceToString()}" } })

        binding.clToBottom.setOnClickListener {
            scrollToBottom()
        }

        chatViewModel.translateEvent
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ (messageId, translateData) ->
                (chatMessageAdapter.currentList.find { it.id == messageId } as? TextChatMessage)?.let {
                    it.translateData = translateData
                    chatMessageAdapter.notifyItemChanged(chatMessageAdapter.currentList.indexOf(it))
                }
            }, { L.w { "[ChatMessageListFragment] observe translateEvent error: ${it.stackTraceToString()}" } })

        chatViewModel.speechToTextEvent
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ (messageId, speechToTextData) ->
                (chatMessageAdapter.currentList.find { it.id == messageId } as? TextChatMessage)?.let {
                    it.speechToTextData = speechToTextData
                    chatMessageAdapter.notifyItemChanged(chatMessageAdapter.currentList.indexOf(it))
                }
            }, { L.w { "[ChatMessageListFragment] observe speechToTextEvent error: ${it.stackTraceToString()}" } })

        setAudioObservers()

        observeChatMessageListState()
        observeSelectMessagesState()

        // 监听输入框高度变化事件
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.inputHeightChanged.collect {
                binding.recyclerViewMessage.noSmoothScrollToBottom()
            }
        }

        // Collect text size changes at Fragment level and notify adapter
        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { chatMessageAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // 监听联系人缓存刷新事件
        chatViewModel.contactorCacheRefreshed
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { chatMessageAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        // 监听会话配置变化，更新自动保存到相册设置
        chatSettingViewModel.conversationSet
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { conversationSet ->
                // Calculate shouldSaveToPhotos based on conversation-level and global settings
                // Priority: conversation-level setting > global setting
                val globalSaveToPhotos = globalServices.userManager.getUserData()?.saveToPhotos == true
                val shouldSaveToPhotos = when (conversationSet?.saveToPhotos) {
                    1 -> true  // Always save
                    0 -> false // Never save
                    else -> globalSaveToPhotos // Follow global
                }
                chatMessageAdapter.shouldSaveToPhotos = shouldSaveToPhotos
                L.i { "[SaveToPhotos] conversationSetting: ${conversationSet?.saveToPhotos}, globalSetting: $globalSaveToPhotos, result: $shouldSaveToPhotos" }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        initPrivacyBanner()
    }

    private fun initPrivacyBanner() {
        if (chatViewModel.forWhat is For.Account && !chatViewModel.forWhat.id.isBotId()) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                isFriend = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(chatViewModel.forWhat.id)) != null
                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    updatePrivacyBanner()
                }
            }

            ContactorUtil.contactsUpdate
                .asFlow()
                .onEach {
                    if (it.contains(chatViewModel.forWhat.id)) {
                        withContext(Dispatchers.IO) {
                            isFriend = wcdb.contactor.getFirstObject(DBContactorModel.id.eq(chatViewModel.forWhat.id)) != null
                        }
                        updatePrivacyBanner()
                    }
                }
                .launchIn(viewLifecycleOwner.lifecycleScope)
        }
    }

    private fun updatePrivacyBanner() {
        binding.tvPrivacyBanner.visibility = if (!isFriend) View.VISIBLE else View.GONE
    }

    private fun observeChatMessageListState() {
        viewLifecycleOwner.lifecycleScope.launch {
            //页面不可见时，不进行刷新，防止触发已读逻辑等
            chatViewModel.chatMessageListUIState
                .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
                .filterNotNull() // 跳过初始 null 状态，null = 未加载，非 null = 已加载（包括空列表）
                .distinctUntilChanged()
                .collect { handleChatMessageListState(it) }
        }
    }

    /**
     * 单独观察选择状态，不通过 ViewModel combine
     *
     * 选择状态变化只更新 Adapter 的选择 UI，不重新组装整个消息列表
     * 这样可以避免选择消息时触发不必要的数据重组和滚动
     */
    private fun observeSelectMessagesState() {
        var wasInEditMode = false
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.selectMessagesState.collect { selectState ->
                chatMessageAdapter.updateSelectionState(selectState)
                
                // When entering edit mode (CombineForwardBar appears), 
                // scroll to bottom only if already at bottom to prevent being covered
                val isNowInEditMode = selectState.editModel
                if (isNowInEditMode && !wasInEditMode) {
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
                    if (layoutManager != null && isAtBottom(layoutManager)) {
                        // Post to wait for CombineForwardBar layout to complete
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
    }

    private fun handleChatMessageListState(state: ChatMessageListUIState) {
        L.i { "[${chatViewModel.forWhat.id}] handleChatMessageListState: size=${state.chatMessages.size}, scrollAction=${state.scrollAction}" }
        if (!isAdded || view == null || !this::binding.isInitialized) return

        binding.lottieViewLoading.clearAnimation()
        binding.lottieViewLoading.visibility = View.GONE

        val list = state.chatMessages
        val scrollAction = state.scrollAction
        val previousListSize = chatMessageAdapter.currentList.size
        val isAtBottomBeforeUpdateList = isAtBottom(binding.recyclerViewMessage.layoutManager as LinearLayoutManager)

        chatMessageAdapter.submitList(list) {
            // 1. 如果有 scrollAction，执行强制滚动
            when (scrollAction) {
                is ScrollAction.ToPosition -> {
                    scrollTo(scrollAction.position)
                }

                is ScrollAction.ToMessage -> {
                    val position = list.indexOfFirst { it.timeStamp == scrollAction.messageTimeStamp }
                    if (position >= 0) {
                        scrollTo(position)
                    }
                }

                is ScrollAction.ToBottom -> {
                    scrollTo(list.size - 1)
                }
                // 2. 没有 scrollAction，走自动滚动逻辑
                null -> {
                    if (isAtBottomBeforeUpdateList) {
                        // 在底部时：有新消息才需要滚动，其他情况（删除/更新）保持现状
                        if (list.size > previousListSize) {
                            scrollTo(list.size - 1)
                        }
                    } else {
                        // 不在底部时，才需要更新悬浮按钮
                        lifecycleScope.launch {
                            updateBottomFloatingButton()
                        }
                    }
                }
            }

            // After list update, check visible area for newly appeared placeholder messages
            checkAndRecordConfidentialPlaceholders()
        }

        if (isFirstShow) {
            mScrollToPosition = when (scrollAction) {
                is ScrollAction.ToPosition -> scrollAction.position
                is ScrollAction.ToBottom -> list.size - 1
                is ScrollAction.ToMessage -> list.indexOfFirst { it.timeStamp == scrollAction.messageTimeStamp }
                null -> -1
            }
            binding.recyclerViewMessage.post {
                doAfterFirstRender()
            }
            isFirstShow = false
            L.i { "[${chatViewModel.forWhat.id}] First time render messages cost ${System.currentTimeMillis() - chatViewModel.viewModelCreateTime}" }
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        val timeDiff = event.eventTime - event.downTime
        val moveX = abs(event.x - touchDownX)
        val moveY = abs(event.y - touchDownY)
        // A click must be quick (< 200ms) AND have minimal movement
        return timeDiff < 200 && moveX < touchSlop && moveY < touchSlop
    }

    private var isFirstShow = true

    private fun sendAndUpdateMessageRead() {
        if (!isAdded || view == null || !this::binding.isInitialized) return
        getLastVisibleMessage()?.let { message ->
            viewLifecycleOwner.lifecycleScope.launch {
                chatViewModel.sendReadRecipient(message.systemShowTimestamp)
                chatViewModel.updateMessageReadPosition(message.systemShowTimestamp)
                updateBottomFloatingButton()
            }
        }
    }

    private suspend fun updateBottomFloatingButton() {
        val defaultTintColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon)
        binding.ivToBottom.imageTintList = ColorStateList.valueOf(defaultTintColor)

        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        val lastPos = layoutManager.findLastVisibleItemPosition()
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
            val tintColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.t_info)
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

        //如果当前消息数量和上次消息数量相同，且滚动位置没有变化，则不进行滚动
        if (pos < 0 || (pos == lastScrollPos && currentMessageCount == lastMessageCount)) return

        lastScrollPos = pos
        lastMessageCount = currentMessageCount

        if (currentMessageCount - 1 == pos) {
            binding.recyclerViewMessage.noSmoothScrollToBottom()
        } else {
            binding.recyclerViewMessage.layoutManager?.scrollToPosition(pos)
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

    /**
     * Get the timestamp of the first visible message.
     * Used for preserving scroll position when transitioning to full ChatActivity.
     */
    fun getFirstVisibleMessageTimestamp(): Long? {
        if (!this::binding.isInitialized) return null
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return null
        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
        if (firstVisiblePosition == RecyclerView.NO_POSITION) return null
        val message = chatMessageAdapter.currentList.getOrNull(firstVisiblePosition)
        return message?.timeStamp
    }

    // Track scroll position across pause/resume to handle system bar changes
    private var wasAtBottomBeforePause = false

    override fun onPause() {
        super.onPause()
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        wasAtBottomBeforePause = isAtBottom(layoutManager)
    }

    override fun onResume() {
        super.onResume()
        if (wasAtBottomBeforePause) {
            // Restore scroll position after system bar changes (e.g., returning from fullscreen activity)
            binding.recyclerViewMessage.post {
                if (!isAdded) return@post
                val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return@post
                if (!isAtBottom(layoutManager)) {
                    binding.recyclerViewMessage.noSmoothScrollToBottom()
                }
                wasAtBottomBeforePause = false
            }
        }
        // 如果键盘监听器因 SOFT_INPUT_ADJUST_NOTHING 注册失败，在 resume 时重新注册
        if (!isKeyboardListenerRegistered) {
            registerKeyboardVisibilityListener()
        }
    }

    override fun onDestroyView() {
        // Batch delete seen confidential placeholders on page close (appScope, independent of UI lifecycle)
        appScope.launch {
            chatViewModel.processPendingConfidentialPlaceholders()
        }
        // KeyboardVisibilityEvent with viewLifecycleOwner will auto-unregister
        isKeyboardListenerRegistered = false
        keyboardListenerRegisterJob?.cancel()
        keyboardListenerRegisterJob = null
        // Dismiss message action coordinator
        messageActionCoordinator?.dismiss()
        messageActionCoordinator = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        AudioMessageManager.releasePlayer()
        AudioAmplitudesHelper.release()
        super.onDestroy()
    }

    private var wasAtBottomBeforeKeyboardHide = false
    private var keyboardListenerRegisterJob: Job? = null

    /**
     * Register keyboard visibility listener with 500ms delay to avoid conflicts with
     * message search positioning (keyboard state changes during Activity transition
     * may trigger scrollToBottom and override the target position).
     */
    private fun registerKeyboardVisibilityListener() {
        if (activity == null || !isAdded || view == null || isKeyboardListenerRegistered) return

        keyboardListenerRegisterJob?.cancel()
        keyboardListenerRegisterJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if (isKeyboardListenerRegistered || activity == null || !isAdded || view == null) return@launch

            try {
                KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) { isKeyboardVisible ->
                    if (isKeyboardVisible) {
                        scrollToBottom()
                    } else if (wasAtBottomBeforeKeyboardHide) {
                        // Post to wait for layout height change
                        binding.recyclerViewMessage.post {
                            binding.recyclerViewMessage.noSmoothScrollToBottom()
                        }
                    }
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager
                    wasAtBottomBeforeKeyboardHide = layoutManager?.let { isAtBottom(it) } ?: false
                }
                isKeyboardListenerRegistered = true
            } catch (e: IllegalArgumentException) {
                // Throws when softInputMode is SOFT_INPUT_ADJUST_NOTHING, will retry in onResume
                L.w { "[ChatMessageListFragment] keyboard listener register failed: ${e.stackTraceToString()}" }
            }
        }
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

    fun showConfidentialAudioDialog(message: TextChatMessage) {
        // 检查 Fragment 是否已附加且 Activity 存在
        if (!isAdded || activity == null) return

        currentAudioMessage = message

        // 使用 Activity 的 FragmentManager
        val fragmentManager = requireActivity().supportFragmentManager

        // 设置结果监听器
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

            if (!message.isMine) {
                deleteConfidentialMessage(message)
            }
        }
        currentAudioMessage = null
        mConfidentialAudioDialog = null
    }

    private var mConfidentialTextDialog: ConfidentialTextBottomSheetFragment? = null
    private var currentTextMessage: TextChatMessage? = null

    fun showConfidentialTextDialog(message: TextChatMessage) {
        // 检查 Fragment 是否已附加且 Activity 存在
        if (!isAdded || activity == null) return

        currentTextMessage = message

        // 使用 Activity 的 FragmentManager
        val fragmentManager = requireActivity().supportFragmentManager

        // 设置结果监听器
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
            if (!message.isMine) {
                deleteConfidentialMessage(message)
            }
        }
        currentTextMessage = null
        mConfidentialTextDialog = null
    }

    private var mConfidentialAttachmentDialog: ConfidentialAttachmentBottomSheetFragment? = null
    var currentAttachmentMessage: TextChatMessage? = null

    fun showConfidentialAttachmentDialog(message: TextChatMessage) {
        // 检查 Fragment 是否已附加且 Activity 存在
        if (!isAdded || activity == null) return

        currentAttachmentMessage = message

        // 使用 Activity 的 FragmentManager
        val fragmentManager = requireActivity().supportFragmentManager

        // 设置结果监听器
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

    /**
     * Check visible confidential placeholder messages and record them in ViewModel.
     * Called during scroll dragging and after list updates.
     */
    private fun checkAndRecordConfidentialPlaceholders() {
        if (!isAdded || view == null || !this::binding.isInitialized) return
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return

        for (i in firstVisible..lastVisible) {
            val message = chatMessageAdapter.currentList.getOrNull(i) ?: continue
            if (message is ConfidentialPlaceholderChatMessage && message.isMine) {
                chatViewModel.markConfidentialPlaceholderAsSeen(message.timeStamp)
            }
        }
    }

    /**
     * Send view receipt for confidential message without deleting it.
     * Used when deletion should happen separately (e.g., when dialog closes).
     */
    fun sendConfidentialViewReceipt(message: ChatMessage) {
        if (!message.isMine && message.isConfidential()) {
            chatViewModel.sendConfidentialViewReceipt(message)
        }
    }

    /**
     * Delete confidential message with logging.
     */
    private fun deleteConfidentialMessage(message: ChatMessage) {
        if (!message.isMine && message.isConfidential()) {
            L.i { "[Confidential] Delete message, messageId: ${message.id}, timestamp: ${message.timeStamp}" }
            chatViewModel.deleteMessage(message.id)
        }
    }

    /**
     * Show first-use tip for viewing confidential message if not shown before.
     * @param message The confidential message being viewed
     * @param onProceed Callback to execute after tip is confirmed or if tip was already shown
     */
    private fun showConfidentialViewTipIfNeeded(message: ChatMessage, onProceed: () -> Unit) {
        if (!message.isMine && message.isConfidential() &&
            globalServices.userManager.getUserData()?.hasShownConfidentialTip != true
        ) {
            // Mark as shown when dialog is displayed (regardless of how it's closed)
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
                        onProceed()
                    }
                )
            }
        } else {
            onProceed()
        }
    }

    private fun doAfterFirstRender() {
        if (!isAdded) {
            return
        }
        backPressedCallback = BackPressedDelegate()
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            backPressedCallback
        )

        // Initialize message action coordinator
        messageActionCoordinator = MessageActionCoordinator(
            activity = requireActivity(),
            globalConfigsManager = globalConfigsManager
        ).apply {
            setActionListener(MessageActionListenerImpl())
        }
    }

    /**
     * Show the new message action popup
     * @param message The message to show actions for
     * @param messageView The view of the message item
     * @param bubbleView The bubble view of the message
     * @param mostUseEmojis List of most used emojis
     * @param isForForward Whether in forward selection mode
     * @param isSaved Whether the message is saved/favorited
     */
    private fun showNewMessageActionPopup(
        message: TextChatMessage,
        messageView: View,
        bubbleView: View,
        mostUseEmojis: List<String>?,
        isForForward: Boolean,
        isSaved: Boolean
    ) {
        val coordinator = messageActionCoordinator ?: return

        // Get bubble bounds
        val bubbleBounds = Rect()
        bubbleView.getGlobalVisibleRect(bubbleBounds)

        // Find TextView from bubble view (for text messages only, not confidential)
        // Custom text selection is handled by TextSelectionManager in MessageActionCoordinator
        val isConfidential = message.isConfidential()
        val textView = if (!isConfidential) {
            bubbleView.findViewById<android.widget.TextView>(R.id.textView)?.apply {
                // Clear OnTouchListener set by TextTruncationUtil.setupDoubleClickPreview
                // It will be restored when the popup is dismissed
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
            enableTextSelection = !isConfidential  // Disable text selection for confidential messages
        )
    }

    /**
     * Implementation of MessageActionCoordinator.ActionListener
     * Handles actions from the new message action popup
     */
    private inner class MessageActionListenerImpl : MessageActionCoordinator.ActionListener {
        override fun onReactionSelected(message: TextChatMessage, emoji: String, isRemove: Boolean) {
            chatViewModel.emojiReaction(
                EmojiReactionEvent(
                    message,
                    emoji,
                    isRemove,  // true = remove reaction, false = add reaction
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
            if (selectedText != null) {
                org.thoughtcrime.securesms.util.Util.copyToClipboard(requireContext(), selectedText)
                ToastUtil.show(getString(R.string.chat_message_action_copied))
            } else {
                messageActionHelper.copyMessageContent(message)
            }
        }

        override fun onTranslate(message: TextChatMessage, selectedText: String?) {
            if (selectedText != null) {
                // Translate selected text
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
                // Forward selected text as new message
                selectChatsUtils.showChatSelectAndSendDialog(requireActivity(), selectedText)
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
            if (StorageUtil.canWriteToMediaStore()) {
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
            // Find the message view for navigation
            val position = chatMessageAdapter.currentList.indexOfFirst { it.id == message.id }
            if (position >= 0) {
                val viewHolder = binding.recyclerViewMessage.findViewHolderForAdapterPosition(position)
                viewHolder?.itemView?.let { gotoDetailPage(it, message) }
            }
        }

        override fun onDismiss() {
            // New popup doesn't use shade, so nothing to hide here
        }
    }

    /**
     * Show the more emoji reaction dialog
     */
    private fun showMoreEmojiDialog(message: TextChatMessage) {
        val emojis = globalConfigsManager.getMostUseEmojis()
        if (emojis.isNullOrEmpty()) return

        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            layoutId = R.layout.layout_more_emoji_dialog,
            onDismiss = { },
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
        )
    }

    private fun deleteSaved(data: ChatMessage) {
        ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.tip),
            message = getString(R.string.chat_message_delete_tips),
            confirmText = getString(R.string.chat_dialog_ok),
            cancelText = getString(R.string.chat_dialog_cancel),
            onConfirm = {
                chatViewModel.deleteMessage(data.id)
            }
        )
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
                )
            }
        }
        pendingSaveAttachmentMessage = null
    }

    private fun saveAttachment(data: TextChatMessage) {
        var messageId = ""
        val attachment = when {
            data.isAttachmentMessage() -> {
                messageId = data.id
                data.attachment
            }

            data.forwardContext?.forwards?.size == 1 -> {
                val attachment = data.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
                messageId = attachment?.authorityId.toString()
                attachment
            }

            else -> null
        }

        attachment?.let {
            val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + it.fileName
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

    private fun showTranslateDialog(data: TextChatMessage) {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = requireActivity(),
            layoutId = R.layout.layout_translate_method_dialog,
            onDismiss = { /* Dialog dismissed */ },
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
        )
    }


    private inner class BackPressedDelegate : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                // First priority: hide message action coordinator if showing
                messageActionCoordinator?.isShowing == true -> {
                    messageActionCoordinator?.dismiss()
                }
                // Second priority: exit selection mode if active
                chatViewModel.selectMessagesState.value.editModel -> {
                    chatViewModel.selectModel(false)
                }
                // Finally: close the page
                else -> {
                    requireActivity().finish()
                }
            }
        }
    }

    /**
     * Check if attachment needs manual download (failed or large file not downloaded)
     * @return true if needs to download, false otherwise
     */
    private fun shouldTriggerManualDownload(
        attachment: Attachment,
        progress: Int?,
        messageId: String
    ): Boolean {
        // Check if download failed or expired
        val isFailedOrExpired = if (progress != null) {
            progress == -1 || progress == -2
        } else {
            attachment.status == AttachmentStatus.FAILED.code || attachment.status == AttachmentStatus.EXPIRED.code
        }
        if (isFailedOrExpired) return true

        // Check if large file needs manual download (>10M)
        val fileSize = attachment.size
        val isLargeFile = fileSize > FileUtil.LARGE_FILE_THRESHOLD
        val fileName = attachment.fileName ?: ""
        val attachmentPath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName
        val isFileValid = FileUtil.isFileValid(attachmentPath)

        return isLargeFile && (attachment.status != AttachmentStatus.SUCCESS.code && progress != 100 || !isFileValid) && progress == null
    }

    private fun downloadAttachment(messageId: String, attachment: Attachment, message: ChatMessage) {
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attachment.fileName
        // Auto-save only for non-confidential images/videos when conversation setting allows
        val autoSave = chatMessageAdapter.shouldSaveToPhotos &&
                !message.isConfidential() &&
                (attachment.isImage() || attachment.isVideo())
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

    private fun openPreview(message: TextChatMessage) {
        val filePath = FileUtil.getMessageAttachmentFilePath(message.id) + message.attachment?.fileName
        if (!FileUtil.isFileValid(filePath)) {
            ToastUtil.showLong(R.string.file_load_error)
            return
        }
        if (!message.isMine && message.isConfidential()) {
            chatViewModel.sendConfidentialViewReceipt(message)
        }
        lifecycleScope.launch {
            val mediaListInfo = withContext(Dispatchers.IO) {
                val mediaList = arrayListOf<LocalMedia>()
                chatMessageAdapter.currentList.forEach { message ->
                    if (message !is TextChatMessage) return@forEach

                    //消息附件
                    message.attachment?.takeIf { it.isImage() || it.isVideo() }?.let { attachment ->
                        val path = FileUtil.getMessageAttachmentFilePath(message.id) + (attachment.fileName ?: return@let)
                        if (FileUtil.isFileValid(path)) {
                            mediaList.add(LocalMedia.generateLocalMedia(context, path))
                        }
                        return@forEach
                    }

                    //单条转发消息附件
                    val forward = message.forwardContext?.forwards?.singleOrNull() ?: return@forEach
                    val forwardAttachment = forward.attachments?.firstOrNull()
                    if (forwardAttachment?.isImage() == true || forwardAttachment?.isVideo() == true) {
                        val forwardMessage = generateMessageFromForward(forward) as? TextChatMessage ?: return@forEach
                        val path = FileUtil.getMessageAttachmentFilePath(forwardMessage.id) + (forwardMessage.attachment?.fileName ?: return@forEach)
                        if (FileUtil.isFileValid(path)) {
                            mediaList.add(LocalMedia.generateLocalMedia(context, path))
                        }
                    }
                }

                val position = mediaList.indexOfFirst { it.path == filePath }
                mediaList to position
            }

            if (mediaListInfo.first.isEmpty()) {
                L.w { "media list is empty" }
                ToastUtil.showLong(R.string.file_load_error)
                return@launch
            }

            PictureSelector.create(requireActivity())
                .openPreview()
                .isHidePreviewDownload(false)
                .isAutoVideoPlay(true)
                .isVideoPauseResumePlay(true)
                .setVideoPlayerEngine(ExoVideoPlayerEngine())
                .setAttachViewLifecycle(object : IBridgeViewLifecycle {
                    override fun onViewCreated(fragment: Fragment?, view: View?, savedInstanceState: Bundle?) {}

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

    private fun gotoDetailPage(rootView: View, message: TextChatMessage) {
        Single.fromCallable {
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
            MessageDetailBitmapHolder.setBitmap(compressedBitmap)
        }.compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this@ChatMessageListFragment))
            .subscribe({
                MessageDetailActivity.startActivity(
                    requireActivity(),
                    message.id
                )
            }, { L.w { "[ChatMessageListFragment] showMessageDetail error: ${it.stackTraceToString()}" } })
    }

    private var isLoadingTop = false
    private var isLoadingBottom = false

    private suspend fun checkAndLoadMessages(linearLayoutManager: LinearLayoutManager) {

        if (isAtBottom(linearLayoutManager)) {
            L.d { "[message] isAtBottom" }
            loadNextPage()
        }

        if (isAtTop(linearLayoutManager)) {
            L.d { "[message] isAtTop" }
            loadPreviousPage()
        }
    }

    // 检查是否滑动到底部
    private fun isAtBottom(layoutManager: LinearLayoutManager): Boolean {
        val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()

        if (lastVisibleItemPosition == layoutManager.itemCount - 1) {
            val verticalScrollRange = binding.recyclerViewMessage.computeVerticalScrollRange()
            val verticalScrollOffset = binding.recyclerViewMessage.computeVerticalScrollOffset()
            val verticalScrollExtent = binding.recyclerViewMessage.computeVerticalScrollExtent()

            // 计算是否已经滑动到底部（考虑内容的总高度和当前的垂直偏移量）
            return verticalScrollRange - verticalScrollOffset <= verticalScrollExtent
        }

        return false

    }

    // 检查是否滑动到顶部
    private fun isAtTop(layoutManager: LinearLayoutManager): Boolean {
        return layoutManager.findFirstVisibleItemPosition() == 0
    }

    // 加载下一页数据的方法
    private suspend fun loadNextPage() {
        if (!isLoadingBottom) {
            isLoadingBottom = true  // 标记为正在加载数据
            chatViewModel.loadNextPage()
            isLoadingBottom = false  // 标记为加载完成
            L.i { "[message] loadNextPage done" }
        }
    }

    // 加载上一页数据的方法
    private suspend fun loadPreviousPage() {
        if (!isLoadingTop) {
            isLoadingTop = true
            chatViewModel.loadPreviousPage()
            isLoadingTop = false
            L.i { "[message] loadPreviousPage Done" }
        }
    }

    // 获取 RecyclerView 中手指触摸到的 item 的位置
    fun getTouchedPosition(e: MotionEvent): Int {
        val child = binding.recyclerViewMessage.findChildViewUnder(e.x, e.y)
        return if (child != null) {
            binding.recyclerViewMessage.getChildAdapterPosition(child)
        } else {
            RecyclerView.NO_POSITION
        }
    }

    private fun showDayTime(firstVisibleItemPosition: Int) {
        dayTimeHideJob?.cancel()
        if (firstVisibleItemPosition == RecyclerView.NO_POSITION) {
            binding.cvDayTime.visibility = View.GONE
            return
        }

        val data = chatMessageAdapter.currentList.getOrNull(firstVisibleItemPosition) ?: return
        binding.cvDayTime.alpha = 1f
        binding.cvDayTime.visibility = View.VISIBLE
        binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
            requireContext(), language, data.systemShowTimestamp
        )
    }

    private var dayTimeHideJob: Job? = null

    private fun hideDayTime() {
        dayTimeHideJob?.cancel()
        dayTimeHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
            if (!isAdded || view == null) return@launch
            // Animate the CardView (cvDayTime) instead of just the TextView
            // This ensures both background and text fade out together
            binding.cvDayTime.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    if (!isAdded || view == null) return@withEndAction
                    binding.cvDayTime.visibility = View.GONE
                    binding.cvDayTime.alpha = 1f
                }
                .start()
        }
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
        // Initialize playback speed from global settings
        val globalSpeed = globalServices.userManager.getUserData()?.voicePlaybackSpeed ?: 1.0f
        AudioMessageManager.setPlaybackSpeed(globalSpeed)

        // Only handle business logic here, UI updates are handled by VoiceMessageView
        viewLifecycleOwner.lifecycleScope.launch {
            AudioMessageManager.playStatusUpdate.collect { (message, status) ->
                when (status) {
                    AudioMessageManager.PLAY_STATUS_START -> {
                        // Show voice speed button for the playing message
                        updateVoiceSpeedButtonVisibility(message, true)
                    }

                    AudioMessageManager.PLAY_STATUS_PAUSED -> {
                        // Hide speed button when paused to avoid state inconsistency
                        // (clicking speed button while paused won't resume playback)
                        updateVoiceSpeedButtonVisibility(message, false)
                    }

                    AudioMessageManager.PLAY_STATUS_COMPLETE -> {
                        // Hide voice speed button when playback completes
                        updateVoiceSpeedButtonVisibility(message, false)

                        // Update play status in database
                        if (message.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY) {
                            chatViewModel.updatePlayStatus(message, AudioMessageManager.PLAY_STATUS_PLAYED)
                        }
                        // Find next auto-play message
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
                            val fileName: String = (next as TextChatMessage).attachment?.fileName ?: ""
                            val attachmentPath = FileUtil.getMessageAttachmentFilePath(next.id) + fileName
                            AudioMessageManager.playOrPauseAudio(next, attachmentPath)
                        }
                    }
                }
            }
        }

        // Observe playback speed changes to update the speed button text
        viewLifecycleOwner.lifecycleScope.launch {
            AudioMessageManager.playbackSpeed.collect { speed ->
                // Update speed button text for the currently playing message
                AudioMessageManager.currentPlayingMessage?.let { message ->
                    updateVoiceSpeedButtonVisibility(message, true)
                }
            }
        }
    }

    /**
     * Update voice speed button visibility for a message item
     */
    private fun updateVoiceSpeedButtonVisibility(message: TextChatMessage, visible: Boolean) {
        val position = chatMessageAdapter.currentList.indexOfFirst { it.id == message.id }
        if (position != -1) {
            val viewHolder = binding.recyclerViewMessage.findViewHolderForAdapterPosition(position)
            if (viewHolder is ChatMessageViewHolder.Message) {
                val currentSpeed = AudioMessageManager.playbackSpeed.value
                viewHolder.updateVoiceSpeedButton(visible, currentSpeed) {
                    // On click: cycle speed
                    AudioMessageManager.cyclePlaybackSpeed()
                }
            }
        }
    }

    private fun registerCallStatusViewListener() {
        onGoingCallStateManager.chatHeaderCallVisibility
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChanged()
            .onEach { isVisible ->
                if (!isVisible) return@onEach
                val targetPos = mScrollToPosition
                if (targetPos == -1) return@onEach
                mScrollToPosition = -1
                binding.recyclerViewMessage.post {
                    if (!isAdded || view == null || !this::binding.isInitialized) return@post
                    scrollTo(targetPos)
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * 机密音频消息对话框
     */
    class ConfidentialAudioBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialAudioBottomSheetFragment {
                return ConfidentialAudioBottomSheetFragment()
            }
        }

        // 使用默认容器（带圆角和拖拽条）
        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_audio_dialog

        // 全屏、不可取消、不可拖拽
        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).setOnClickListener {
                dismiss()
            }

            // 通过接口获取 ChatMessageListFragment
            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment() ?: return
            val currentMessage = chatFragment.currentAudioMessage ?: return

            chatFragment.voiceMessageView = view.findViewById(R.id.voice_message_view)
            chatFragment.voiceMessageView?.setAudioMessage(currentMessage)

            // Set up callback to send view receipt when user clicks play (requirement 3.5)
            // Note: Only send receipt here, deletion happens in onConfidentialAudioDialogDismiss
            if (!currentMessage.isMine) {
                chatFragment.voiceMessageView?.onConfidentialPlayStarted = { message ->
                    chatFragment.sendConfidentialViewReceipt(message)
                }
            }

            val clVoiceMessageView = view.findViewById<ConstraintLayout>(R.id.cl_voice_message_view)
            if (currentMessage.isMine) {
                clVoiceMessageView?.setBackgroundResource(R.drawable.chat_message_content_bg_mine)
            } else {
                clVoiceMessageView?.setBackgroundResource(R.drawable.chat_message_content_bg_others)
            }
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            // 使用现代的 Fragment 结果 API
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_audio_dialog_result", Bundle())
            } catch (e: Exception) {
                // Fragment 可能已经被销毁，忽略错误
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_audio failed: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * 机密文本消息对话框
     */
    class ConfidentialTextBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialTextBottomSheetFragment {
                return ConfidentialTextBottomSheetFragment()
            }
        }

        // 使用默认容器（带圆角和拖拽条）
        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_text_dialog

        // 全屏、不可取消、不可拖拽
        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {

            view.findViewById<ImageView>(R.id.iv_close).setOnClickListener {
                dismiss()
            }

            // 通过接口获取 ChatMessageListFragment
            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment() ?: return
            val currentMessage = chatFragment.currentTextMessage ?: return
            val messageText = currentMessage.message.toString()

            val textView = view.findViewById<TextView>(R.id.textView)
            val llConfidential = view.findViewById<LinearLayout>(R.id.ll_confidential)
            val scrollView = view.findViewById<ScrollView>(R.id.sv_confidential)
            val tvTitle = view.findViewById<TextView>(R.id.tv_title)

            textView.autoLinkMask = 0
            textView.movementMethod = null

            textView.textSize = if (TextSizeUtil.isLarger) 24f else 16f

            LinkTextUtils.setMarkdownToTextview(
                requireContext(),
                messageText,
                textView,
                mentions = null
            )

            textView.post {
                val textViewLayout = textView.layout
                if (textView.lineCount > 0) {
                    llConfidential.removeAllViews()
                    for (i in 0 until textView.lineCount) {
                        val view = layoutInflater.inflate(
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
                        view.layoutParams.height = lineHeight

                        val tvNumber = view.findViewById<TextView>(R.id.tv_line_number)
                        val number = (i + 1).toString()
                        tvNumber.text = number

                        llConfidential.addView(view)
                    }

                    llConfidential.post {
                        llConfidential.setOnTouchListener { v, event ->
                            val itemHeight = llConfidential.getChildAt(0).height
                            val touchX = event.x.toInt()

                            val regionWidth = v.width / 3

                            when (event.action) {
                                MotionEvent.ACTION_DOWN -> {
                                    // Only send view receipt on first touch, deletion happens in onConfidentialTextDialogDismiss
                                    chatFragment.sendConfidentialViewReceipt(currentMessage)
                                    if (touchX <= regionWidth) {
                                        v.parent.requestDisallowInterceptTouchEvent(true)
                                    } else {
                                        v.parent.requestDisallowInterceptTouchEvent(false)
                                    }
                                }

                                MotionEvent.ACTION_MOVE -> {
                                    val currentY = event.y.toInt()
                                    val currentPosition = (currentY / itemHeight).coerceIn(0, llConfidential.childCount - 1)

                                    val startLine = (currentPosition - 2).coerceAtLeast(0)
                                    val endLine = (currentPosition + 2).coerceAtMost(llConfidential.childCount - 1)

                                    llConfidential.children.forEachIndexed { index, view ->
                                        if (index in startLine..endLine) {
                                            view.visibility = View.INVISIBLE
                                        } else {
                                            view.visibility = View.VISIBLE
                                        }
                                    }

                                    if (touchX <= regionWidth) {
                                        v.parent.requestDisallowInterceptTouchEvent(true)
                                    } else {
                                        v.parent.requestDisallowInterceptTouchEvent(false)
                                    }
                                }

                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    llConfidential.children.forEach { it.visibility = View.VISIBLE }
                                    v.parent.requestDisallowInterceptTouchEvent(false)
                                }
                            }
                            true
                        }

                        if (chatFragment.isScrollViewScrollable(scrollView)) {
                            tvTitle.visibility = View.VISIBLE
                        } else {
                            tvTitle.visibility = View.GONE
                        }
                    }
                }
            }
        }


        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            // 使用现代的 Fragment 结果 API
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_text_dialog_result", Bundle())
            } catch (e: Exception) {
                // Fragment 可能已经被销毁，忽略错误
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_text failed: ${e.stackTraceToString()}" }
            }
        }
    }

    /**
     * 显示完整文本内容的对话框
     */
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

        // 使用默认容器（带圆角和拖拽条）
        override fun getContentLayoutResId(): Int = R.layout.chat_layout_text_content_dialog

        // 全屏、不可取消、不可拖拽
        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).setOnClickListener {
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

    /**
     * 机密附件消息对话框
     */
    class ConfidentialAttachmentBottomSheetFragment : BaseBottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialAttachmentBottomSheetFragment {
                return ConfidentialAttachmentBottomSheetFragment()
            }
        }

        private var attachMessageView: com.difft.android.chat.widget.AttachMessageView? = null
        private var currentMessage: TextChatMessage? = null

        // 使用默认容器（带圆角和拖拽条）
        override fun getContentLayoutResId(): Int = R.layout.chat_layout_confidential_attachment_dialog

        // 全屏、不可取消、不可拖拽
        override fun isFullScreen(): Boolean = true
        override fun isCancelableByUser(): Boolean = false
        override fun isDraggable(): Boolean = false
        override fun showDragHandle(): Boolean = false

        override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
            view.findViewById<ImageView>(R.id.iv_close).setOnClickListener {
                dismiss()
            }

            // 通过接口获取 ChatMessageListFragment
            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment() ?: return
            currentMessage = chatFragment.currentAttachmentMessage ?: return
            val message = currentMessage ?: return

            val clAttachmentView = view.findViewById<ConstraintLayout>(R.id.cl_attachment_view)
            if (message.isMine) {
                clAttachmentView?.setBackgroundResource(R.drawable.chat_message_content_bg_mine)
            } else {
                clAttachmentView?.setBackgroundResource(R.drawable.chat_message_content_bg_others)
            }

            attachMessageView = view.findViewById(R.id.attach_message_view)
            attachMessageView?.setupAttachmentView(message)
            attachMessageView?.setOnClickListener {
                // 点击附件打开文件查看器
                val filePath = FileUtil.getMessageAttachmentFilePath(message.id) + message.attachment?.fileName
                if (FileUtil.isFileValid(filePath)) {
                    requireContext().viewFile(filePath)
                } else {
                    ToastUtil.showLong(R.string.file_load_error)
                }
            }
        }

        override fun onDismiss(dialog: DialogInterface) {
            super.onDismiss(dialog)
            // 使用现代的 Fragment 结果 API
            try {
                requireActivity().supportFragmentManager.setFragmentResult("confidential_attachment_dialog_result", Bundle())
            } catch (e: Exception) {
                // Fragment 可能已经被销毁，忽略错误
                L.w { "[ChatMessageListFragment] setFragmentResult confidential_attachment failed: ${e.stackTraceToString()}" }
            }
        }
    }
}