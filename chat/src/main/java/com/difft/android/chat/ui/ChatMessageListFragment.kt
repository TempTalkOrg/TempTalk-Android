package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
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
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.dp
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.common.ScreenShotUtil
import com.difft.android.chat.common.SendType
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.data.ChatMessageListUIState
import com.difft.android.chat.databinding.ChatFragmentMessageListBinding
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.canDownloadOrCopyFile
import com.difft.android.chat.message.generateMessageFromForward
import com.difft.android.chat.message.getAttachmentProgress
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.message.isConfidential
import com.difft.android.chat.recent.RecentChatUtil
import com.difft.android.chat.widget.AudioAmplitudesHelper
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.chat.widget.AudioWaveProgressBar
import com.difft.android.chat.widget.VoiceMessageView
import com.difft.android.network.config.GlobalConfigsManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.luck.picture.lib.basic.IBridgeViewLifecycle
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnExternalPreviewEventListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.TranslateTargetLanguage
import difft.android.messageserialization.model.isAudioFile
import difft.android.messageserialization.model.isAudioMessage
import difft.android.messageserialization.model.isImage
import difft.android.messageserialization.model.isVideo
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import net.yslibrary.android.keyboardvisibilityevent.Unregistrar
import org.difft.app.database.models.ContactorModel
import org.thoughtcrime.securesms.components.reaction.ConversationItemSelection
import org.thoughtcrime.securesms.components.reaction.ConversationReactionDelegate
import org.thoughtcrime.securesms.components.reaction.ConversationReactionOverlay
import org.thoughtcrime.securesms.components.reaction.InteractiveConversationElement
import org.thoughtcrime.securesms.components.reaction.MotionEventRelay
import org.thoughtcrime.securesms.components.reaction.SelectedConversationModel
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.DownloadAttachmentJob
import org.thoughtcrime.securesms.util.SaveAttachmentTask
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.StorageUtil
import org.thoughtcrime.securesms.util.Stub
import org.thoughtcrime.securesms.util.ThemeUtil
import org.thoughtcrime.securesms.util.Util
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.WindowUtil
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import util.TimeFormatter
import util.concurrent.TTExecutors
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class ChatMessageListFragment : Fragment() {

    private var keyboardVisibilityEventListener: Unregistrar? = null

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    private var mScrollToPosition = -1

    private lateinit var binding: ChatFragmentMessageListBinding
    private val chatViewModel: ChatMessageViewModel by activityViewModels()
    private val chatMessageAdapter: ChatMessageAdapter by lazy {
        object : ChatMessageAdapter(chatViewModel.forWhat) {
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
                        ChatForwardMessageActivity.startActivity(
                            requireActivity(),
                            getString(R.string.chat_history),
                            forwardContext,
                            if (!data.isMine) data.id else null
                        )
                        if (!data.isMine) {
                            chatViewModel.setConfidentialRecipient(data)
                        }
                    } else {
                        if (forwards.size == 1) {
                            val forward = forwardContext.forwards?.getOrNull(0) ?: return
                            val attachment = forward.attachments?.getOrNull(0) ?: return
                            val progress = data.getAttachmentProgress()

                            if (shouldTriggerManualDownload(attachment, progress, attachment.id)) {
                                downloadAttachment(attachment.id, attachment, data)
                                return
                            }

                            if (attachment.isImage() || attachment.isVideo()) {
                                openPreview(generateMessageFromForward(forward) as TextChatMessage)
                            }
                        } else {
                            ChatForwardMessageActivity.startActivity(
                                requireActivity(),
                                getString(R.string.chat_history),
                                forwardContext
                            )
                        }
                    }
                } else if (!data.sharedContacts.isNullOrEmpty()) {
                    val contactId =
                        data.sharedContacts?.getOrNull(0)?.phone?.getOrNull(0)?.value
//                                val contactName = data.sharedContacts?.getOrNull(0)?.name?.displayName
                    if (!TextUtils.isEmpty(contactId)) {
                        ContactDetailActivity.startActivity(requireActivity(), contactId, sourceType = FriendSourceType.SHARE_CONTACT, source = data.authorId)
                    }
                    setConfidentialRecipient(data)
                } else if (data.isAttachmentMessage()) {
                    val attachment = data.attachment ?: return
                    val progress = data.getAttachmentProgress()

                    if (shouldTriggerManualDownload(attachment, progress, data.id)) {
                        downloadAttachment(data.id, attachment, data)
                        return
                    }

                    if (attachment.isImage() || attachment.isVideo()) {
                        openPreview(data)
                    } else if (attachment.isAudioMessage() || attachment.isAudioFile()) {
                        if (data.isConfidential()) {
                            val filePath = FileUtil.getMessageAttachmentFilePath(data.id) + data.attachment?.fileName
                            if (!FileUtil.isFileValid(filePath) && !FileUtil.isFileValid("$filePath.encrypt")) {
                                ToastUtil.showLong(R.string.file_load_error)
                                return
                            }
                            showConfidentialAudioDialog(data)
                            if (!data.isMine) {
                                chatViewModel.setConfidentialRecipient(data)
                            }
                        }
                    }
                } else {
                    if (data.isConfidential() && (!TextUtils.isEmpty(data.card?.content) || !TextUtils.isEmpty(
                            data.message
                        ))
                    ) {
                        showConfidentialTextDialog(data)
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

                ServiceUtil.getInputMethodManager(activity)
                    .hideSoftInputFromWindow(rootView.windowToken, 0)
                Observable.just(Unit)
                    .delay(100, TimeUnit.MILLISECONDS)
                    .compose(RxUtil.getSchedulerComposer())
                    .to(RxUtil.autoDispose(this@ChatMessageListFragment))
                    .subscribe({
                        chatViewModel.showReactionShade(true)
                        binding.reactionsShade.visibility = View.VISIBLE
                        binding.recyclerViewMessage.suppressLayout(true)

                        val target: InteractiveConversationElement? = rootView as? InteractiveConversationElement

                        if (target != null) {
                            val snapshot = ConversationItemSelection.snapshotView(
                                target,
                                binding.recyclerViewMessage,
                                data,
                                null
                            )
                            val bodyBubble = target.bubbleView
                            val selectedConversationModel = SelectedConversationModel(
                                bitmap = snapshot,
                                itemX = rootView.x,
                                itemY = rootView.y + binding.recyclerViewMessage.translationY,
                                bubbleY = bodyBubble.y,
                                bubbleWidth = bodyBubble.width,
                                audioUri = null,
                                isOutgoing = data.isMine,
                                focusedView = null,
                                snapshotMetrics = target.getSnapshotStrategy()?.snapshotMetrics
                                    ?: InteractiveConversationElement.SnapshotMetrics(
                                        snapshotOffset = bodyBubble.x,
                                        contextMenuPadding = bodyBubble.x
                                    ),
//                                emojis = globalConfigsManager.getEmojis(),
                                mostUseEmojis = globalConfigsManager.getMostUseEmojis(),
                                chatUIData = chatViewModel.chatUIData.value,
                                isForForward = false,
                                isSaved = chatViewModel.forWhat.id == globalServices.myId
                            )
                            handleReaction(
                                rootView,
                                data,
                                ReactionsToolbarListener(data),
                                selectedConversationModel,
                                object : ConversationReactionOverlay.OnHideListener {
                                    override fun startHide(focusedView: View?) {
                                    }

                                    override fun onHide() {
                                        ViewUtil.fadeOut(binding.reactionsShade, 50, View.GONE)
                                        chatViewModel.showReactionShade(false)

                                        binding.recyclerViewMessage.suppressLayout(false)

                                        if (activity != null) {
                                            WindowUtil.setLightStatusBarFromTheme(requireActivity())
                                            WindowUtil.setLightNavigationBarFromTheme(
                                                requireActivity()
                                            )
                                        }

//                            bodyBubble.visibility = View.VISIBLE
                                    }
                                }
                            )
                        }
                    }, { it.printStackTrace() })

            }

            override fun onAvatarClicked(contactor: ContactorModel?) {
                contactor?.let {
                    var sourceType: String? = null
                    var source: String? = null
                    if (chatViewModel.forWhat is For.Group) {
                        sourceType = FriendSourceType.FROM_GROUP
                        source = chatViewModel.forWhat.id
                    }
                    ContactDetailActivity.startActivity(
                        this@ChatMessageListFragment.requireActivity(),
                        it.id,
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

            override fun onContactAcceptClicked(chatMessage: NotifyChatMessage) {
                chatViewModel.agreeFriendRequest(
                    chatMessage.id,
                    chatMessage.notifyMessage?.data?.askID ?: -1,
                    SecureSharedPrefsUtil.getToken()
                )
            }

            override fun onFriendRequestClicked(chatMessage: NotifyChatMessage) {
//                chatMessage.contactor?.id?.let {
//                    chatViewModel.requestAddFriend(requireActivity(), it)
//                }
            }

            override fun onPinClicked(chatMessage: NotifyChatMessage) {
                lifecycleScope.launch {
                    val groupPin = chatMessage.notifyMessage?.data?.groupPins?.firstOrNull()
                        ?: return@launch
                    val pinnedMessageTimeStamp =
                        groupPin.conversationId.split(":").last().toLongOrNull()
                            ?: return@launch
                    chatViewModel.jumpToMessage(pinnedMessageTimeStamp)
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

            override fun onRecallReEditClicked(chatMessage: TextChatMessage) {
                chatViewModel.reEditMessage(chatMessage)
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

            override fun onSendStatusClicked(rootView: View, message: TextChatMessage) {
                if (message.sendStatus == SendType.SentFailed.rawValue) {
                    showResendPop(rootView, message)
                } else {
                    gotoDetailPage(rootView, message)
                }
            }

            override fun onSelectedMessage(messageId: String, selected: Boolean) {
                chatViewModel.selectedMessage(messageId, selected)
            }
        }
    }

    private lateinit var reactionDelegate: ConversationReactionDelegate
    private val motionEventRelay: MotionEventRelay by viewModels(ownerProducer = { requireActivity() })
    private lateinit var backPressedCallback: BackPressedDelegate

    private var isHapticFeedbackTriggered = false

    private val language by lazy {
        LanguageUtils.getLanguage(requireContext())
    }

    private var userScrolling = false

    private var lastScrollPos: Int = -1 //上次滚动位置
    private var lastMessageCount: Int = -1 //上次消息数量
    private var lastDayTimeUpdate: Long = 0 //上次更新时间
    private val dayTimeUpdateInterval: Long = 500 //更新间隔时间(毫秒)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentMessageListBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }


    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (userScrolling) {
                                    checkAndLoadMessages(linearLayoutManager)
                                    hideDayTime()
                                }
                                sendAndUpdateMessageRead()
                                userScrolling = false
                            }
                        }

                        RecyclerView.SCROLL_STATE_DRAGGING -> {
                            userScrolling = true
                        }

                        RecyclerView.SCROLL_STATE_SETTLING -> {}
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return
                    val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                    // 实时更新位置
                    lastScrollPos = firstVisibleItemPosition

                    // 对showDayTime进行节流
                    if (userScrolling) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDayTimeUpdate >= dayTimeUpdateInterval) {
                            showDayTime(firstVisibleItemPosition)
                            lastDayTimeUpdate = currentTime
                        }
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
                    binding.recyclerViewMessage.itemAnimator = DefaultItemAnimator()
                    val position = viewHolder.bindingAdapterPosition
                    chatMessageAdapter.currentList.getOrNull(position)?.let { message ->
                        if (canQuote(message)) {
                            chatViewModel.quoteMessage(message)
                        }
                    }

                    lifecycleScope.launch {
                        delay(100)
                        chatMessageAdapter.notifyItemChanged(position)
                        viewHolder.itemView.translationX = 0f

                        delay(500)
                        binding.recyclerViewMessage.itemAnimator = null
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
        RecentChatUtil.confidentialRecipient
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this@ChatMessageListFragment))
            .subscribe({
                setConfidentialRecipient(it)
            }, { it.printStackTrace() })


        binding.recyclerViewMessage.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (isClick(event)) {
                    ServiceUtil.getInputMethodManager(activity).hideSoftInputFromWindow(v.windowToken, 0)
                    chatViewModel.clickList()
                }
            }
            false
        }

        lifecycleScope.launch {
            delay(500)
            keyboardVisibilityEventListener = KeyboardVisibilityEvent.registerEventListener(activity) {
                if (it) {
                    scrollToBottom()
                }
            }
        }

        chatViewModel.chatActionsShow
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                scrollToBottom()
            }, { it.printStackTrace() })

        binding.clToBottom.setOnClickListener {
            scrollToBottom()
        }
        FileUtil.progressUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ messageId ->
                val position = chatMessageAdapter.currentList.indexOfFirst {
                    it.id == messageId || (it is TextChatMessage && it.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()?.authorityId.toString() == messageId)
                }
//                L.d { "===progressUpdate===${messageId} position:${position}" }
                chatMessageAdapter.notifyItemChanged(position)
            }, { it.printStackTrace() })

        chatViewModel.translateEvent
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ (messageId, translateData) ->
                (chatMessageAdapter.currentList.find { it.id == messageId } as? TextChatMessage)?.let {
                    it.translateData = translateData
                    chatMessageAdapter.notifyItemChanged(chatMessageAdapter.currentList.indexOf(it))
                }
            }, { it.printStackTrace() })

        chatViewModel.speechToTextEvent
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ (messageId, speechToTextData) ->
                (chatMessageAdapter.currentList.find { it.id == messageId } as? TextChatMessage)?.let {
                    it.speechToTextData = speechToTextData
                    chatMessageAdapter.notifyItemChanged(chatMessageAdapter.currentList.indexOf(it))
                }
            }, { it.printStackTrace() })

        setAudioObservers()

        observeChatMessageListState()

        // 监听输入框高度变化事件
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.inputHeightChanged.collect {
                binding.recyclerViewMessage.noSmoothScrollToBottom()
            }
        }

        // Collect text size changes at Fragment level and notify adapter
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TextSizeUtil.textSizeState.collect {
                    chatMessageAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun observeChatMessageListState() {
        viewLifecycleOwner.lifecycleScope.launch {
            //页面不可见时，不进行刷新，防止触发已读逻辑等
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                chatViewModel.chatMessageListUIState
                    .dropWhile { it.scrollToPosition == -2 }
                    .collect { handleChatMessageListState(it) }
            }
        }
    }

    private fun handleChatMessageListState(it: ChatMessageListUIState) {
        L.i { "[Temp] Hide the loading UI, all the received values are " }
        if (!isAdded || view == null || !this::binding.isInitialized) return

        binding.lottieViewLoading.clearAnimation()
        binding.lottieViewLoading.visibility = View.GONE
        val (list, scrollToPosition, triggeredByUser) = it
        val isAtBottomBeforeUpdateList = isAtBottom(binding.recyclerViewMessage.layoutManager as LinearLayoutManager)
        chatMessageAdapter.submitList(list) {
            if (triggeredByUser) {
                scrollTo(scrollToPosition)
            } else if (isAtBottomBeforeUpdateList && list.size - 1 == scrollToPosition) { // if the list is at bottom before update list, also expected to scroll to bottom the do it
                scrollTo(scrollToPosition)
            } else {
                lifecycleScope.launch {
                    updateBottomFloatingButton()
                }
            }
        }
        if (isFirstShow) {
            mScrollToPosition = scrollToPosition
            binding.recyclerViewMessage.post {
                doAfterFirstRender()
            }
            isFirstShow = false
            L.i { "[${chatViewModel.forWhat.id}] First time render messages cost ${System.currentTimeMillis() - chatViewModel.viewModelCreateTime}" }
        }
    }

    private fun isClick(event: MotionEvent): Boolean {
        // 这里可以根据需要设置阈值，判断是否为单击
        return event.eventTime - event.downTime < 200
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
        if (chatMessageAdapter.currentList.size - 1 == lastPos || chatMessageAdapter.currentList.size == 0) {
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
        val currentMessageCount = chatViewModel.chatMessageListUIState.value.chatMessages.size

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
        binding.clAt.visibility = View.GONE
        binding.clToBottom.visibility = View.GONE
        binding.recyclerViewMessage.noSmoothScrollToBottom()
        viewLifecycleOwner.lifecycleScope.launch {
            chatViewModel.jumpToBottom()
        }
    }

    override fun onDestroy() {
        keyboardVisibilityEventListener?.unregister()
        AudioMessageManager.releasePlayer()
        AudioAmplitudesHelper.release()
        super.onDestroy()
    }

    private var resendPopupWindow: PopupWindow? = null
    private var resendPopupMessageId: String? = null

    private fun showResendPop(rootView: View, data: ChatMessage) {
        if (resendPopupWindow?.isShowing == true && data.id == resendPopupMessageId) {
            return
        }
        resendPopupWindow?.dismiss()
        resendPopupWindow = null
        resendPopupMessageId = data.id

        val itemList = mutableListOf<ChativePopupView.Item>().apply {
            add(
                ChativePopupView.Item(
                    ResUtils.getDrawable(R.drawable.chat_message_action_resend),
                    requireActivity().getString(R.string.chat_message_action_resend)
                ) {
                    chatViewModel.reSendMessage(data)
                    resendPopupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    ResUtils.getDrawable(R.drawable.chat_message_action_delete),
                    requireActivity().getString(R.string.chat_message_action_delete),
                    ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.error)
                ) {
                    chatViewModel.deleteMessage(data.id)
                    resendPopupWindow?.dismiss()
                })
        }
        val view = rootView.findViewById<ViewGroup>(R.id.contentContainer) ?: rootView
        resendPopupWindow = ChativePopupWindow.showAsDropDown(view, itemList)
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
                chatViewModel.deleteMessage(message.id)
            }
        }
        currentAudioMessage = null
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
                chatViewModel.deleteMessage(message.id)
            }
        }
        currentTextMessage = null
    }

    fun isScrollViewScrollable(scrollView: ScrollView): Boolean {
        return scrollView.canScrollVertically(-1) || scrollView.canScrollVertically(1)
    }

    fun setConfidentialRecipient(message: ChatMessage) {
        if (!message.isMine && message.isConfidential()) {
            chatViewModel.setConfidentialRecipient(message)
            chatViewModel.deleteMessage(message.id)
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

        val conversationReactionStub =
            Stub<ConversationReactionOverlay>(binding.conversationReactionScrubberStub)
        reactionDelegate = ConversationReactionDelegate(conversationReactionStub)
        reactionDelegate.setOnReactionSelectedListener(OnReactionsSelectedListener())
        motionEventRelay.setDrain(MotionEventRelayDrain(this))
    }

    private fun handleReaction(
        rootView: View,
        conversationMessage: TextChatMessage,
        onActionSelectedListener: ConversationReactionOverlay.OnActionSelectedListener,
        selectedConversationModel: SelectedConversationModel,
        onHideListener: ConversationReactionOverlay.OnHideListener
    ) {
        reactionDelegate.setOnActionSelectedListener(onActionSelectedListener)
        reactionDelegate.setOnHideListener(onHideListener)
        reactionDelegate.show(
            requireActivity(),
            rootView,
            conversationMessage,
            false,
            selectedConversationModel
        )
    }

    private inner class OnReactionsSelectedListener :
        ConversationReactionOverlay.OnReactionSelectedListener {
        override fun onReactionSelected(messageRecord: ChatMessage, emoji: String, remove: Boolean) {
            reactionDelegate.hide()
            chatViewModel.emojiReaction(
                EmojiReactionEvent(
                    messageRecord,
                    emoji,
                    remove,
                    0L,
                    EmojiReactionFrom.EMOJI_DIALOG
                )
            )
        }
    }

    private inner class MotionEventRelayDrain(lifecycleOwner: LifecycleOwner) :
        MotionEventRelay.Drain {
        private val lifecycle = lifecycleOwner.lifecycle

        override fun accept(motionEvent: MotionEvent): Boolean {
            return if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                reactionDelegate.applyTouchEvent(motionEvent)
            } else {
                false
            }
        }
    }

    private inner class ReactionsToolbarListener(
        private val data: TextChatMessage
    ) : ConversationReactionOverlay.OnActionSelectedListener {
        override fun onActionSelected(action: ConversationReactionOverlay.Action, rootView: View) {
            when (action) {
                ConversationReactionOverlay.Action.FORWARD -> chatViewModel.forwardMessage(data, false)
                ConversationReactionOverlay.Action.COPY -> copyMessageContent()
                ConversationReactionOverlay.Action.QUOTE -> chatViewModel.quoteMessage(data)
                ConversationReactionOverlay.Action.RECALL -> chatViewModel.recallMessage(data)

                ConversationReactionOverlay.Action.TRANSLATE -> {
                    showTranslateDialog(data)
                }

                ConversationReactionOverlay.Action.TRANSLATE_OFF -> {
                    chatViewModel.translateOff(data)
                }

                ConversationReactionOverlay.Action.SPEECH_TO_TEXT_OFF -> {
                    chatViewModel.speechToTextOff(data)
                }

                ConversationReactionOverlay.Action.SAVE -> {
                    if (StorageUtil.canWriteToMediaStore()) {
                        saveAttachment(data)
                    } else {
                        pendingSaveAttachmentMessage = data
                        mediaPermission.launchMultiplePermission(PermissionUtil.picturePermissions)
                    }
                }

                ConversationReactionOverlay.Action.MULTISELECT -> {
                    chatViewModel.selectModel(true)
                    chatViewModel.selectedMessage(data.id, true) //auto select the lone clicked message
                }

                ConversationReactionOverlay.Action.SAVE_TO_NOTE -> {
                    chatViewModel.forwardMessage(data, true)
                }

                ConversationReactionOverlay.Action.MORE_INFO -> {
                    gotoDetailPage(rootView, data)
                }

                ConversationReactionOverlay.Action.SPEECH_TO_TEXT -> {
                    chatViewModel.speechToText(requireActivity(), data)
                }

                ConversationReactionOverlay.Action.DELETE_SAVED -> {
                    deleteSaved(data)
                }

                else -> {}
            }
        }

        private fun copyMessageContent() {
            // Check if it's a file attachment that can be copied
            if (data.canDownloadOrCopyFile()) {
                copyFileToClipboard()
            } else {
                // Copy text content as before
                val content = data.forwardContext?.forwards?.let { forwards ->
                    if (forwards.size == 1) {
                        forwards.firstOrNull()?.let { forward ->
                            forward.card?.content.takeUnless { it.isNullOrEmpty() }
                                ?: forward.text.takeUnless { it.isNullOrEmpty() }
                        }
                    } else null
                } ?: data.card?.content.takeUnless { it.isNullOrEmpty() }
                ?: data.message.takeUnless { it.isNullOrEmpty() }

                content?.let { Util.copyToClipboard(requireContext(), it) }
            }
        }


        private fun copyFileToClipboard() {
            val attachment = when {
                data.isAttachmentMessage() -> {
                    data.attachment
                }

                data.forwardContext?.forwards?.size == 1 -> {
                    data.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()
                }

                else -> null
            }

            attachment?.let { attach ->
                val messageId = if (data.isAttachmentMessage()) data.id else attach.authorityId.toString()
                val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + attach.fileName
                val file = File(filePath)

                if (file.exists()) {
                    // 使用 FileProvider 生成安全的 URI
                    val uri = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.provider",
                        file
                    )
                    // Copy file to clipboard
                    val clipboard = ServiceUtil.getClipboardManager(requireContext())
                    val clipData = ClipData.newUri(
                        requireContext().contentResolver,
                        attach.fileName ?: "file",
                        uri
                    )
                    clipboard.setPrimaryClip(clipData)
                    ToastUtil.show(getString(R.string.chat_message_action_copied))
                }
            }
        }
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
            if (reactionDelegate.isShowing) {
                reactionDelegate.hide()
            } else {
                requireActivity().finish()
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
        ApplicationDependencies.getJobManager().add(
            DownloadAttachmentJob(
                messageId,
                attachment.id,
                filePath,
                attachment.authorityId,
                attachment.key ?: byteArrayOf(),
                !attachment.isAudioMessage(),
                !message.isConfidential()
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
            chatViewModel.setConfidentialRecipient(message)
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
                .isAutoVideoPlay(false)
                .isVideoPauseResumePlay(true)
                .setAttachViewLifecycle(object : IBridgeViewLifecycle {
                    override fun onViewCreated(fragment: Fragment?, view: View?, savedInstanceState: Bundle?) {
                        if (message.isConfidential()) {
                            ScreenShotUtil.setScreenShotEnable(fragment?.requireActivity(), false)
                        }
                    }

                    override fun onDestroy(fragment: Fragment?) {
                        if (message.isConfidential()) {
                            ScreenShotUtil.setScreenShotEnable(fragment?.requireActivity(), true)
                            if (!message.isMine) {
                                chatViewModel.deleteMessage(message.id)
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
            }, { it.printStackTrace() })
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
            binding.tvDayTime.visibility = View.GONE
            return
        }

        val data = chatMessageAdapter.currentList.getOrNull(firstVisibleItemPosition) ?: return
        binding.tvDayTime.visibility = View.VISIBLE
        binding.tvDayTime.text = TimeFormatter.getConversationDateHeaderString(
            requireContext(), language, data.systemShowTimestamp
        )
    }

    private var dayTimeHideJob: Job? = null

    private fun hideDayTime() {
        dayTimeHideJob?.cancel()
        dayTimeHideJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
            binding.tvDayTime.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.tvDayTime.visibility = View.GONE
                    binding.tvDayTime.alpha = 1f
                }
                .start()
        }
    }

    private fun getLastVisibleMessage(): ChatMessage? {
        val layoutManager = binding.recyclerViewMessage.layoutManager as? LinearLayoutManager ?: return null
        val lastPos = layoutManager.findLastVisibleItemPosition()
        return chatMessageAdapter.currentList.getOrNull(lastPos)
    }

    private fun setAudioObservers() {
        AudioMessageManager.playStatusUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                val message = it.first
                when (it.second) {
                    AudioMessageManager.PLAY_STATUS_START -> {
                    }

                    AudioMessageManager.PLAY_STATUS_PAUSED -> {
                    }

                    AudioMessageManager.PLAY_STATUS_COMPLETE -> {
                        if (message.playStatus == AudioMessageManager.PLAY_STATUS_NOT_PLAY) {
                            chatViewModel.updatePlayStatus(message, AudioMessageManager.PLAY_STATUS_PLAYED)
                        }
                        //找到下一个自动播放的消息
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
                            val pos = chatMessageAdapter.currentList.indexOfFirst { msg -> next.id == msg.id }
                            chatMessageAdapter.notifyItemChanged(pos)
                        }
                    }
                }

                val pos = chatMessageAdapter.currentList.indexOfFirst { msg ->
                    message.id == msg.id ||
                            (msg is TextChatMessage && msg.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()?.authorityId.toString() == message.id)
                }
                chatMessageAdapter.notifyItemChanged(pos)

                voiceMessageView?.let { view ->
                    val playButton = view.findViewById<AppCompatImageView>(R.id.play_button)
                    val progressBar = view.findViewById<AudioWaveProgressBar>(R.id.audioWaveProgressBar)
                    if (AudioMessageManager.currentPlayingMessage?.id == message.id) {
                        if (AudioMessageManager.isPaused) {
                            playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
                        } else {
                            playButton.setImageResource(R.drawable.ic_chat_audio_item_pause)
                        }
                    } else {
                        playButton.setImageResource(R.drawable.ic_chat_audio_item_play)
                        progressBar.setProgress(0f)
                    }
                }
            }, { it.printStackTrace() })

        viewLifecycleOwner.lifecycleScope.launch {
            AudioMessageManager.progressUpdate
                .collect { (message, progress) ->
                    val pos = chatMessageAdapter.currentList.indexOfFirst { msg ->
                        message.id == msg.id ||
                                (msg is TextChatMessage && msg.forwardContext?.forwards?.firstOrNull()?.attachments?.firstOrNull()?.authorityId.toString() == message.id)
                    }
                    chatMessageAdapter.notifyItemChanged(pos)

                    voiceMessageView?.let { view ->
                        val progressBar = view.findViewById<AudioWaveProgressBar>(R.id.audioWaveProgressBar)
                        progressBar.setProgress(progress)
                    }
                }

        }

        viewLifecycleOwner.lifecycleScope.launch {
            AudioAmplitudesHelper.amplitudeExtractionComplete.collect { message ->
                val currentList = chatMessageAdapter.currentList.toMutableList()
                val pos = currentList.indexOfFirst { it.id == message.id }

                if (pos != -1) {
                    currentList[pos] = message
                    chatMessageAdapter.submitList(currentList)
                    chatMessageAdapter.notifyItemChanged(pos)
                }
            }
        }
    }

    private fun registerCallStatusViewListener() {
        LCallManager.chatHeaderCallVisibility
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ isVisible ->
                if (isVisible) {
                    if (mScrollToPosition != -1) scrollTo(mScrollToPosition)
                    mScrollToPosition = -1
                }
            }, {
                L.e { "[Call] ChatMessageListFragment callStatusView listener error = ${it.message}" }
                it.printStackTrace()
            })
    }

    /**
     * 机密音频消息对话框
     */
    class ConfidentialAudioBottomSheetFragment : BottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialAudioBottomSheetFragment {
                return ConfidentialAudioBottomSheetFragment()
            }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            return inflater.inflate(R.layout.chat_layout_confidential_audio_dialog, container, false)
        }

        override fun onStart() {
            super.onStart()

            // 设置为不可手动关闭
            isCancelable = false

            // 设置底部弹窗为全屏显示
            val dialog = dialog
            if (dialog != null) {
                // 禁用点击外部区域关闭
                dialog.setCanceledOnTouchOutside(false)

                val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet != null) {
                    val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true

                    // 禁用手势滑动关闭
                    behavior.isHideable = false
                    // 禁用拖拽
                    behavior.isDraggable = false

                    // 设置底部弹窗高度为全屏
                    val layoutParams = bottomSheet.layoutParams
                    layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    bottomSheet.layoutParams = layoutParams
                }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // 设置背景色
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg2))

            view.findViewById<ImageView>(R.id.iv_close).setOnClickListener {
                dismiss()
            }

            // 通过接口获取 ChatMessageListFragment
            val chatFragment = (requireActivity() as? ChatMessageListProvider)?.getChatMessageListFragment() ?: return
            val currentMessage = chatFragment.currentAudioMessage ?: return

            chatFragment.voiceMessageView = view.findViewById(R.id.voice_message_view)
            chatFragment.voiceMessageView?.setAudioMessage(currentMessage)

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
            }
        }
    }

    /**
     * 机密文本消息对话框
     */
    class ConfidentialTextBottomSheetFragment : BottomSheetDialogFragment() {

        companion object {
            fun newInstance(): ConfidentialTextBottomSheetFragment {
                return ConfidentialTextBottomSheetFragment()
            }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View? {
            return inflater.inflate(R.layout.chat_layout_confidential_text_dialog, container, false)
        }

        override fun onStart() {
            super.onStart()

            // 设置为不可手动关闭
            isCancelable = false

            // 设置底部弹窗为全屏显示
            val dialog = dialog
            if (dialog != null) {
                // 禁用点击外部区域关闭
                dialog.setCanceledOnTouchOutside(false)

                val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet != null) {
                    val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet)
                    behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                    behavior.skipCollapsed = true

                    // 禁用手势滑动关闭
                    behavior.isHideable = false
                    // 禁用拖拽
                    behavior.isDraggable = false

                    // 设置底部弹窗高度为全屏
                    val layoutParams = bottomSheet.layoutParams
                    layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    bottomSheet.layoutParams = layoutParams
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            // 设置背景色
            view.setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bg2))

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
                                    chatFragment.setConfidentialRecipient(currentMessage)
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
            }
        }
    }
}