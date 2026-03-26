package com.difft.android.chat.ui

import android.app.Activity
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
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
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
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
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
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
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
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.jobs.create
import org.thoughtcrime.securesms.mediasend.MediaSendActivityResult
import org.thoughtcrime.securesms.mediasend.v2.MediaSelectionActivity
import org.thoughtcrime.securesms.util.MediaUtil
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.ViewUtil
import org.thoughtcrime.securesms.util.visible
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
    lateinit var pushReadReceiptSendJobFactory: PushReadReceiptSendJobFactory

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var localMessageCreator: LocalMessageCreator

    // Keyboard visibility listener is managed by KeyboardVisibilityEvent with viewLifecycleOwner
    private var isKeyboardListenerRegistered = false

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
        const val OVERSIZED_TEXT_THRESHOLD = 4096  // 4KB - when to convert text to file
        const val OVERSIZED_TEXT_BODY_LENGTH = 2048  // 2KB - truncated text in message body
        const val MAX_TEXT_FILE_SIZE = 10 * 1024 * 1024  // 10MB - maximum text file size

        // If focus was lost more than this before the screenshot callback, the notification
        // panel was likely open. The callback has ~944ms system delay (Pixel Android 14+),
        // so 2000ms safely covers ROM variation while staying below the notification-panel minimum.
        private const val SCREENSHOT_NOTIFICATION_PANEL_THRESHOLD_MS = 2000L
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

                quote = Quote(it.timeStamp, it.authorId, text, null)
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
                        val sharedContactList = message.sharedContacts()
                        if (message.type == 0 || message.type == 1) {
                            val content: String?
                            if (sharedContactList.isNotEmpty()) {
                                content = ResUtils.getString(R.string.chat_contact_card)

                                val sharedContactId = sharedContactList.getOrNull(0)?.phone?.getOrNull(0)?.value
                                val sharedContactName = sharedContactList.getOrNull(0)?.name?.displayName
                                forwardContext = ForwardContext(emptyList(), false, sharedContactId, sharedContactName)
                            } else if (message.forwardContextDatabaseId != null) {
                                content = ResUtils.getString(R.string.chat_history)
                                forwardContext = message.forwardContext()
                                forwardContext?.forwards?.forEach { forward ->
                                    changeAttachmentStatus(forward)
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

                            if (saveToNote) {
                                selectChatsUtils.saveToNotes(
                                    requireActivity(),
                                    content,
                                    forwardContext
                                )
                            } else {
                                selectChatsUtils.showChatSelectAndSendDialog(
                                    requireActivity(),
                                    content,
                                    null,
                                    null,
                                    forwardContext?.let { listOf(it) },
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
            if (binding.combineForward.isVisible) {
                binding.combineForward.setContent {
                    val state = chatViewModel.selectMessagesState.collectAsState()
                    CombineForwardBar(
                        stateData = state.value,
                        onForwardClick = {
                            chatViewModel.onForwardClick()
                        },
                        onCombineClick = {
                            chatViewModel.onCombineClick()
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
            selectChatsUtils.showChatSelectAndSendDialog(requireActivity(), it.content, null, null, it.forwardContexts)
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.saveMultiMessageToNote.filterNotNull().onEach {
            selectChatsUtils.saveToNotes(
                requireActivity(),
                it.content,
                it.forwardContexts.first()
            )
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        registerKeyboardVisibilityListener()

        chatViewModel.listClick
            .onEach {
                binding.llChatActions.visibility = View.GONE

                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE

                updateSubmitButtonView()
            }
            .catch { e -> L.w { "[ChatMessageInputFragment] observe recordState error: ${e.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        chatViewModel.voiceMessageSend
            .onEach { path ->
                val mimeType = MediaUtil.getMimeType(requireContext(), path.toUri()) ?: ""
                prepareSendAttachmentPush(path.toUri(), mimeType, isAudioMessage = true)
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
            binding.llChatActions.visibility = View.GONE
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
            // check permission
            // callback to select picture in onPicturePermissionForMessageResult
            onPicturePermissionForMessage.launchMultiplePermission(PermissionUtil.picturePermissions)
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)
            binding.edittextInput.clearFocus()
        }

        binding.buttonAttachment.setOnClickListener {
            ScreenLockUtil.temporarilyDisabled = true
            fileActivityLauncher.launch(
                Intent(Intent.ACTION_GET_CONTENT).apply {
                    setType("*/*")
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
            )
        }

        binding.buttonContact.setOnClickListener {
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
            if (isGroup) { //判断是否仅协调人可发言
                val group = chatUIData?.group
                if (group != null && !groupUtil.canSpeak(group, globalServices.myId)) {
                    ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
                    return@setOnClickListener
                }
            }
            val message: String = binding.edittextInput.text.toString().trim()
            if (!TextUtils.isEmpty(message)) {
                // Check if text exceeds maximum file size (10MB) before processing
                val messageBytes = message.toByteArray(Charsets.UTF_8)
                if (messageBytes.size > MAX_TEXT_FILE_SIZE) {
                    ToastUtil.show(getString(R.string.text_file_exceeds_10mb_limit))
                    L.w { "Text message exceeds MAX_TEXT_FILE_SIZE (${messageBytes.size} bytes), send blocked." }
                    return@setOnClickListener
                }

                // Check if text is oversized (>= 4KB) and needs to be converted to file attachment
                if (messageBytes.size >= OVERSIZED_TEXT_THRESHOLD) {
                    // Generate file name and create text file in background thread
                    val timeStamp = System.currentTimeMillis()
                    val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"
                    val fileName = generateOversizedTextFileName()
                    val truncatedText = message.utf8Substring(OVERSIZED_TEXT_BODY_LENGTH)

                    L.i { "Text message oversized (${messageBytes.size} bytes), converting to file attachment. Body truncated to $OVERSIZED_TEXT_BODY_LENGTH bytes." }

                    // Create file and send in background thread using coroutines
                    viewLifecycleOwner.lifecycleScope.launch {
                        val filePath = withContext(Dispatchers.IO) {
                            createTextFile(messageId, fileName, message)
                        }

                        // Check if fragment is still in valid state before UI updates
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
                            // Clear UI only after successful send
                            binding.edittextInput.setText("")
                            binding.quoteZone.visibility = View.GONE
                        } else {
                            // Keep user input when file creation fails
                            L.e { "Failed to create text file: file creation exception" }
                            ToastUtil.show(getString(R.string.chat_status_fail))
                        }
                    }
                } else {
                    // Send as normal text message
                    sendTextPush(message)
                    binding.edittextInput.setText("")
                    binding.quoteZone.visibility = View.GONE
                }
            }
        }

        binding.buttonMoreActions.setOnClickListener {
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)

            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                if (!isAdded || view == null) return@launch
                binding.llChatActions.visibility = View.VISIBLE

                binding.buttonMoreActions.visibility = View.GONE
                binding.buttonMoreActionsClose.visibility = View.VISIBLE
                chatViewModel.setVoiceVisibility(false)
                chatViewModel.showChatActions()

                isVoiceMode = false
                updateSubmitButtonView()
            }
        }

        binding.buttonMoreActionsClose.setOnClickListener {
            binding.llChatActions.visibility = View.GONE

            binding.buttonMoreActions.visibility = View.VISIBLE
            binding.buttonMoreActionsClose.visibility = View.GONE

            isVoiceMode = false
            updateSubmitButtonView()
        }

        binding.buttonVoice.setOnClickListener {
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)

            viewLifecycleOwner.lifecycleScope.launch {
                delay(100)
                if (!isAdded || view == null) return@launch
                isVoiceMode = true
                updateSubmitButtonView()

                chatViewModel.setVoiceVisibility(true)

                binding.llChatActions.visibility = View.GONE

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

                binding.llChatActions.visibility = View.GONE
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
                            sendTextPush(body)
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

    private val fileActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!isAdded || view == null) return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val uri = result.data?.data
        if (uri == null) {
            ToastUtil.showLong(R.string.unsupported_file_type)
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
                ToastUtil.showLong(R.string.unsupported_file_type)
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
                            sendTextPush(body)
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
                    getString(R.string.chat_message_attachment)
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
                            SecureSharedPrefsUtil.getToken(),
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

        var atPersonsString: String? = null
        if (mentions.isNotEmpty()) {
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
                mentions.toMutableList(),
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

            resetData()
        }
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
                    ContactorUtil.fetchAddFriendRequest(requireContext(), SecureSharedPrefsUtil.getToken(), chatViewModel.forWhat.id, sourceType, source, action)
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


    private fun resetData() {
        mentionsSelectedContacts.clear()
        mentions.clear()
        prevInputTextLength = 0

        quote = null
        forwardContext = null
        recall = null
        reactions.clear()
        sharedContacts.clear()
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

    /**
     * Sends a reaction-only message without clearing the composing input.
     * This is separate from sendTextPush() to avoid clearing the user's draft message
     * when they react to a message while composing.
     */
    private fun sendReactionPush(reaction: Reaction, timeStamp: Long) {
        val forWhat = chatViewModel.forWhat
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"

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
        ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))
    }

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
                    resetData()
                    return@launch
                }
                if (originMessage.fromWho != globalServices.myId) {
                    L.w { "Recall message failed, the sender does not match, realSource:${originMessage.fromWho}" }
                    resultJob.cancel()
                    ComposeDialogManager.dismissWait()
                    ToastUtil.show(R.string.operation_failed)
                    resetData()
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

                resetData()
            } catch (e: Exception) {
                L.e { "Recall message failed: ${e.stackTraceToString()}" }
                ComposeDialogManager.dismissWait()
                ToastUtil.show(R.string.operation_failed)
                resetData()
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
                ToastUtil.showLong(R.string.unsupported_file_type)
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
        // 如果键盘监听器因 SOFT_INPUT_ADJUST_NOTHING 注册失败，在 resume 时重新注册
        if (!isKeyboardListenerRegistered) {
            registerKeyboardVisibilityListener()
        }
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
        super.onDestroyView()
        screenshotDetector?.release()
        screenshotDetector = null
        // KeyboardVisibilityEvent with viewLifecycleOwner will auto-unregister
        isKeyboardListenerRegistered = false
    }

    /**
     * 注册键盘可见性监听器
     * 使用 setEventListener 绑定 viewLifecycleOwner，自动管理生命周期，避免内存泄漏
     * 如果 Activity 的 softInputMode 为 SOFT_INPUT_ADJUST_NOTHING 会注册失败，
     * 这种情况下会在 onResume 时重新尝试注册
     */
    private fun registerKeyboardVisibilityListener() {
        if (activity == null || !isAdded || view == null) return
        try {
            // Use setEventListener with viewLifecycleOwner to auto-manage lifecycle
            // This prevents memory leaks when Fragment is destroyed but Activity is still alive (dual-pane mode)
            KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) {
                if (it) { // 键盘弹出
                    binding.llChatActions.visibility = View.GONE
                    binding.buttonMoreActions.visibility = View.VISIBLE
                    binding.buttonMoreActionsClose.visibility = View.GONE
                }
                updateSubmitButtonView()
            }
            isKeyboardListenerRegistered = true
        } catch (e: IllegalArgumentException) {
            // Activity 的 softInputMode 为 SOFT_INPUT_ADJUST_NOTHING 时会抛异常
            // 这种情况会在 onResume 时重新尝试注册
            L.w { "[ChatMessageInputFragment] keyboard listener register failed: ${e.stackTraceToString()}" }
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