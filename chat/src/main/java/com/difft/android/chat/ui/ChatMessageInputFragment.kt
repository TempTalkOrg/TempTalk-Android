package com.difft.android.chat.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.text.getSpans
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import autodispose2.androidx.lifecycle.autoDispose
import com.difft.android.PushReadReceiptSendJobFactory
import com.difft.android.PushTextSendJobFactory
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchMultiplePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.fragment.DisposableManageFragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DEFAULT_DEVICE_ID
import com.difft.android.base.utils.FileUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.utf8Substring
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.common.SendType
import com.difft.android.chat.compose.CombineForwardBar
import com.difft.android.chat.contacts.contactsall.PinyinComparator
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatFragmentInputBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.message.ChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.isAttachmentMessage
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import com.difft.android.chat.widget.AudioMessageManager
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.GetConversationShareRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import com.luck.picture.lib.language.LanguageConfig
import com.luck.picture.lib.pictureselector.GlideEngine
import com.luck.picture.lib.pictureselector.PictureSelectorUtils
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
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
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import net.yslibrary.android.keyboardvisibilityevent.Unregistrar
import org.difft.app.database.convertToContactorModels
import org.difft.app.database.convertToTextMessage
import org.difft.app.database.forwardContext
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.members
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBMessageModel
import org.difft.app.database.models.MessageModel
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class ChatMessageInputFragment : DisposableManageFragment() {

    private val chatViewModel by activityViewModels<ChatMessageViewModel>()

    private val chatSettingViewModel by activityViewModels<ChatSettingViewModel>()

    private val draftViewModel by activityViewModels<DraftViewModel>()

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

    private var disappearingTime: Int = 0

    private var conversationSet: ConversationSetResponseBody? = null

    private var currentDraft = Draft()   // hold in-memory copy

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

    private var keyboardVisibilityEventListener: Unregistrar? = null

    private val onPicturePermissionForMessage = registerPermission {
        onPicturePermissionForMessageResult(it)
    }

    companion object {
        const val MAX_BYTES_LIMIT = 4096
        const val MAX_BYTES_LIMIT_FOR_CUT_STRING = 2048
    }

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

        fileActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

            val uri = result.data?.data
            if (uri == null) {
                ToastUtil.showLong(R.string.unsupported_file_type)
                return@registerForActivityResult
            }

            viewLifecycleOwner.lifecycleScope.launch {
                // 优先判断文件大小是否超过200MB
                val fileSize = withContext(Dispatchers.IO) { FileUtil.getFileSize(uri) }
                if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                    ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                    return@launch
                }

                val file = withContext(Dispatchers.IO) {
                    runCatching { FileUtil.copyUriToFile(uri) }
                        .onFailure { L.e { "copyUriToFile failed: ${it.stackTraceToString()}" } }
                        .getOrNull()
                }

                if (file == null) {
                    ToastUtil.showLong(R.string.unsupported_file_type)
                    return@launch
                }

                val path = file.absolutePath
                val mimeType = FileUtil.getMimeTypeType(uri)

                if (MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)) {
                    val localMedia = LocalMedia().apply {
                        this.realPath = path
                        this.mimeType = mimeType
                        this.fileName = FileUtils.getFileName(path)
                    }
                    val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                        putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, arrayListOf(localMedia))
                    }
                    mediaSelectActivityLauncher.launch(intent)
                } else {
                    prepareSendAttachmentPush(path.toUri(), mimeType ?: "")
                }
            }
        }

        mediaSelectActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                it.data?.let { intent ->
                    val result = MediaSendActivityResult.fromData(intent)
                    val list = result.media
                    val body = result.body
                    Observable.interval(0, 300, TimeUnit.MILLISECONDS)
                        .compose(RxUtil.getSchedulerComposer())
                        .takeUntil { number -> number >= list.size - 1 }
                        .doOnNext { index ->
                            val media = list[index.toInt()]
                            prepareSendAttachmentPush(media.realPath.toUri(), media.mimeType)
                        }
                        .ignoreElements()
                        .delay(500, TimeUnit.MILLISECONDS)
                        .andThen(Completable.fromAction {
                            if (body.isNotEmpty()) {
                                sendTextPush(body)
                            }
                        })
                        .autoDispose(this@ChatMessageInputFragment, Lifecycle.Event.ON_DESTROY)
                        .subscribe({}, { error -> error.printStackTrace() })
                }
            }
        }

        chatViewModel.chatUIData.filterNotNull().onEach { data ->
            chatUIData = data
            data.group?.let {
                if (it.status == 0) {
                    binding.root.visibility = View.VISIBLE
                } else {
                    binding.root.visibility = View.GONE
                }
            }
        }.launchIn(viewLifecycleOwner.lifecycleScope)

        chatSettingViewModel.conversationSet
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                conversationSet = it
                updateConfidential()
                updateBottomView()
            }, { it.printStackTrace() })

        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contains(chatViewModel.forWhat.id)) {
                    updateViewByFriendCheck()
                }
            }, { it.printStackTrace() })

        chatViewModel.messageQuoted
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                val message = it as? TextChatMessage ?: return@subscribe
                val text = createQuoteContent(message)
                quote = Quote(it.timeStamp, it.authorId, text, null, it.nickname?.toString())
                binding.quoteZone.visibility = View.VISIBLE
                binding.author.text = it.nickname
                binding.quoteText.text = text
                binding.edittextInput.requestFocus()
                ServiceUtil.getInputMethodManager(activity)
                    .showSoftInput(binding.edittextInput, InputMethodManager.SHOW_IMPLICIT)
                currentDraft = currentDraft.copy(quote = quote)
                draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
            }, {})

        chatViewModel.messageForward
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                val messageToForward = it.first as? TextChatMessage ?: return@subscribe
                val saveToNote = it.second
                Single.fromCallable {
                    wcdb.message.getFirstObject(DBMessageModel.id.eq(messageToForward.id))?.let { listOf(it) }.orEmpty()
                }
                    .compose(RxUtil.getSingleSchedulerComposer<List<MessageModel>>())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({ messages: List<MessageModel>? ->
                        if (!messages.isNullOrEmpty()) {
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
                                                messageToForward.card,
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
                    }) { error: Throwable -> error.printStackTrace() }
            }, { it.printStackTrace() })

        chatViewModel.messageRecall
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                showRecallDialog(it)
            }, {})


        chatViewModel.messageEmojiReaction
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                sendEmojiReaction(it)
                if (!it.remove && it.actionFrom == EmojiReactionFrom.EMOJI_DIALOG) {
                    globalConfigsManager.updateMostUseEmoji(it.emoji)
                }
            }, {})

        chatViewModel.messageReEdit
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ message ->
                message.message?.let {
                    binding.edittextInput.setText(it)
                    binding.edittextInput.setSelection(it.length)
                    binding.edittextInput.requestFocus()
                    ServiceUtil.getInputMethodManager(activity)
                        .showSoftInput(binding.edittextInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }, {})

        chatViewModel.messageResend
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ message ->
                resendMessage(message.id)
            }, {})

        MessageArchiveUtil.archiveTimeUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.first == chatViewModel.forWhat.id) {
                    disappearingTime = it.second.toInt()
                }
            }, { it.printStackTrace() })

        chatViewModel.showReactionShade
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) binding.reactionsShade.visibility = View.VISIBLE else binding.reactionsShade.visibility = View.GONE
            }, { it.printStackTrace() })

        SendMessageUtils.sendMessage
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contactId == chatViewModel.forWhat.id) {
                    if (it.isPrefill) {
                        binding.edittextInput.requestFocus()
                        binding.edittextInput.setText(it.text)
                        binding.edittextInput.setSelection(binding.edittextInput.text?.length ?: 0)

                        if (it.isForSpooky && !it.mentions.isNullOrEmpty()) {
                            mentionsSelectedContacts.addAll(it.mentions)
                            Observable.timer(500, TimeUnit.MILLISECONDS)
                                .compose(RxUtil.getSchedulerComposer())
                                .to(RxUtil.autoDispose(this))
                                .subscribe({
                                    updateMentions(false)
                                }, { e -> e.printStackTrace() })
                        }
                    } else {
                        sendTextPush(it.text)
                    }
                }
            }, { it.printStackTrace() })

        chatViewModel.confidentialRecipient
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
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
            }, { e -> e.printStackTrace() })

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

        keyboardVisibilityEventListener = KeyboardVisibilityEvent.registerEventListener(activity) {
            if (it) { //键盘弹出
                binding.llChatActions.visibility = View.GONE
                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE
            }
//            else {
//                binding.edittextInput.apply {
//                    isFocusable = false
//                    isFocusableInTouchMode = false
//                }
//            }

            updateSubmitButtonView()
        }

        chatViewModel.listClick
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                binding.llChatActions.visibility = View.GONE

                binding.buttonMoreActions.visibility = View.VISIBLE
                binding.buttonMoreActionsClose.visibility = View.GONE

                updateSubmitButtonView()
            }, { e -> e.printStackTrace() })

        chatViewModel.voiceMessageSend
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ path ->
                val mimeType = MediaUtil.getMimeType(requireContext(), path.toUri()) ?: ""
                prepareSendAttachmentPush(path.toUri(), mimeType, isAudioMessage = true)
            }, { it.printStackTrace() })

        chatViewModel.showOrHideFullInput
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (!it.first) {
                    binding.edittextInput.apply {
                        requestFocus()
                        setText(it.second)
                        setSelection(it.second.length)
                        ViewUtil.focusAndShowKeyboard(this)
                    }
                }
            }, { it.printStackTrace() })

        loadDraftOneTime(chatViewModel.forWhat.id)

        chatViewModel.avatarLongClicked
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ contact ->
                mentionsSelectedContacts.add(contact)
                insertTextToEdittext("@${contact.getDisplayNameWithoutRemarkForUI()} ")
                updateMentions(false)
                ViewUtil.focusAndShowKeyboard(binding.edittextInput)
            }, { it.printStackTrace() })
    }

    private fun updateInputViewSize() {
        val message: String = binding.edittextInput.text.toString().trim()
        if (message.isEmpty()) {
            binding.edittextInput.textSize = 12f
        } else {
            if (TextSizeUtil.isLager()) {
                binding.edittextInput.textSize = 21f
            } else {
                binding.edittextInput.textSize = 16f
            }
        }
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
                    binding.buttonMedia.visibility = View.GONE
                } else {
                    binding.buttonSubmit.visibility = View.GONE
                    binding.buttonVoice.visibility = View.VISIBLE
                    binding.buttonKeyboard.visibility = View.GONE
                    binding.buttonMedia.visibility = View.VISIBLE
                }
            }
        }
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

                val utf8Bytes = text.toString().toByteArray(Charsets.UTF_8)
                if (utf8Bytes.size > MAX_BYTES_LIMIT) {
                    val truncatedString = text.toString().utf8Substring(MAX_BYTES_LIMIT_FOR_CUT_STRING)
                    ToastUtil.show(requireContext().getString(R.string.chat_exceed_bytes_limit))
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
            Observable.timer(100, TimeUnit.MILLISECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    binding.edittextInput.apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                    ViewUtil.focusAndShowKeyboard(binding.edittextInput)
                }, { e -> e.printStackTrace() })
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
            selectChatsUtils.showContactSelectDialog(requireActivity()) {
                it?.let { contact ->
                    val phones = mutableListOf<SharedContactPhone>().apply {
                        this.add(SharedContactPhone(contact.id, 3, null))
                    }
                    sharedContacts.add(SharedContact(SharedContactName(null, null, null, null, null, contact.name), phones, null, null, null, null))
                    sendTextPush(null)
                }
            }
        }

        binding.buttonSubmit.setOnClickListener {
            if (isGroup) { //判断是否仅协调人可发言
                val group = chatUIData?.group
                if (group != null && !GroupUtil.canSpeak(group, globalServices.myId)) {
                    ToastUtil.show(getString(R.string.group_only_moderators_can_speak_tip))
                    return@setOnClickListener
                }
            }
            val message: String = binding.edittextInput.text.toString().trim()
            if (!TextUtils.isEmpty(message)) {
                sendTextPush(message)
                binding.edittextInput.setText("")
                binding.quoteZone.visibility = View.GONE
            }
        }

        binding.buttonMoreActions.setOnClickListener {
            ViewUtil.hideKeyboard(requireContext(), binding.edittextInput)

            Observable.timer(100, TimeUnit.MILLISECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    binding.llChatActions.visibility = View.VISIBLE

                    binding.buttonMoreActions.visibility = View.GONE
                    binding.buttonMoreActionsClose.visibility = View.VISIBLE
                    chatViewModel.setVoiceVisibility(false)
                    chatViewModel.showChatActions()

                    isVoiceMode = false
                    updateSubmitButtonView()
                }, { e -> e.printStackTrace() })
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

            Observable.timer(100, TimeUnit.MILLISECONDS)
                .compose(RxUtil.getSchedulerComposer())
                .to(RxUtil.autoDispose(this))
                .subscribe({
                    isVoiceMode = true
                    updateSubmitButtonView()

                    chatViewModel.setVoiceVisibility(true)

                    binding.llChatActions.visibility = View.GONE

                    binding.buttonMoreActions.visibility = View.VISIBLE
                    binding.buttonMoreActionsClose.visibility = View.GONE
                }, { e -> e.printStackTrace() })
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

        var lastContentLength = 0
        binding.edittextInput.viewTreeObserver.addOnGlobalLayoutListener {
            val currentLength = binding.edittextInput.text?.length ?: 0
            if (currentLength != lastContentLength) {
                chatViewModel.emitInputHeightChanged()
                lastContentLength = currentLength
                //防止edittextInput行数变化抖动
                binding.edittextInput.postDelayed({
                    if (binding.edittextInput.lineCount > 3) {
                        binding.ivConfidential.visibility = View.GONE
                        binding.ivConfidentialRight.visibility = View.VISIBLE
                        binding.ivFullInputOpen.visibility = View.VISIBLE
                    } else {
                        binding.ivConfidential.visibility = View.VISIBLE
                        binding.ivConfidentialRight.visibility = View.GONE
                        binding.ivFullInputOpen.visibility = View.GONE
                    }
                }, 200)
            }
        }

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
//                    if (!GroupUtil.canSpeak(group, globalServices.myId)) {
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

                    // Example: show who’s being quoted (author ID or name)
                    binding.author.text = quote.authorName

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
                    updateSubmitButtonView()
                    updateBottomView()
                    if (!isFriend) {
                        checkAndUpdateFriendRequestStatus()
                    }
                }
            }
        }
    }

    private lateinit var mediaSelectActivityLauncher: ActivityResultLauncher<Intent>
    private lateinit var fileActivityLauncher: ActivityResultLauncher<Intent>


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
                e.printStackTrace()
                L.e { "update mentions span error: ${e.stackTraceToString()}" }
            }
        }

        currentDraft = currentDraft.copy(mentions = mentions.toList())
        draftViewModel.updateDraft(chatViewModel.forWhat.id, currentDraft)
    }

    private val pinyinComparator: PinyinComparator by lazy {
        PinyinComparator()
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
//            DBContactsStore.queryContactWithName(key)
//                .compose(RxUtil.getSingleSchedulerComposer())
//                .to(RxUtil.autoDispose(this))
//                .subscribe({ contactsOther ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {

                val contacts = mutableListOf<ContactsAtAdapter.Item>()

                val contactsOfGroup = chatUIData?.group?.members?.convertToContactorModels()?.mapNotNull { member ->
                    if (key == null || member.getDisplayNameForUI().contains(key, true)) member else null
                }?.sortedWith(pinyinComparator) ?: emptyList()

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
                    if (contacts.isEmpty()) {
                        binding.llAt.visibility = View.GONE
                        contactsAtAdapter.submitList(emptyList())
                    } else {
                        binding.llAt.visibility = View.VISIBLE
                        contactsAtAdapter.submitList(contacts)
                    }
                }
            }
//                }, { it.printStackTrace() })
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
                binding.ivConfidential.visibility = View.VISIBLE
            } else {
                val isReceivedFriendRequest = ContactorUtil.hasContactRequest(chatViewModel.forWhat.id)

                if (isReceivedFriendRequest) {
                    binding.clSendMessage.visibility = View.GONE
                    binding.clFriends.visibility = View.VISIBLE
//                    binding.llFriends.visibility = View.VISIBLE
                    setFoundYouText(conversationSet?.findyouDescribe)
                } else {
                    binding.clSendMessage.visibility = View.VISIBLE
                    binding.clFriends.visibility = View.GONE
                }

                binding.clLeftActions.visibility = View.GONE
                binding.ivConfidential.visibility = View.GONE
            }
//            }

            if (conversationSet?.blockStatus == 1) {
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
        chatHttpClient.httpService
            .fetchShareConversationConfig(
                SecureSharedPrefsUtil.getToken(),
                GetConversationShareRequestBody(
                    listOf(messageArchiveManager.conversationParams(chatViewModel.forWhat.id)),
                    true
                )
            )
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.status == 0) {
                    it.data?.conversations?.firstOrNull()?.let { config ->
                        if (config.askedVersion > 0) {
                            ContactorUtil.updateContactRequestStatus(chatViewModel.forWhat.id)
                            updateBottomView()
                        } else {
                            ContactorUtil.updateContactRequestStatus(chatViewModel.forWhat.id, true)
                        }
                    }
                }
            }, { it.printStackTrace() })

    }

    private fun setFoundYouText(text: String?) {
        if (!TextUtils.isEmpty(text)) {
            binding.tvFoundYou.visibility = View.VISIBLE
            binding.tvFoundYou.text = text
        } else {
            binding.tvFoundYou.visibility = View.GONE
        }
    }

    private fun updateConfidential() {
        listOf(binding.ivConfidential, binding.ivConfidentialRight).forEach { view ->
            if (conversationSet?.confidentialMode == 1) {
                val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_enable)
                view.setImageDrawable(drawable)
                view.tag = 1
                binding.edittextInput.hint = getString(R.string.chat_message_input_hint_confidential)
                binding.vConfidentialLine.visibility = View.VISIBLE
            } else {
                val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable).apply {
                    this.setTint(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
                }
                view.setImageDrawable(drawable)
                view.tag = 0
                binding.edittextInput.hint = getString(R.string.chat_message_input_hint)
                binding.vConfidentialLine.visibility = View.GONE
            }
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

    private fun sendTextPush(content: String?, isScreenShot: Boolean = false, timeStamp: Long = System.currentTimeMillis()) {
        val forWhat = if (isGroup) For.Group(chatViewModel.forWhat.id) else For.Account(chatViewModel.forWhat.id)
        val messageId = "${timeStamp}${globalServices.myId.replace("+", "")}${DEFAULT_DEVICE_ID}"

        var screenShot: ScreenShot? = null
        if (isScreenShot) {
            screenShot = ScreenShot(RealSource(globalServices.myId, 1, timeStamp, timeStamp))
            ContactorUtil.createScreenShotMessage(messageId, if (isGroup) globalServices.myId else chatViewModel.forWhat.id, if (isGroup) chatViewModel.forWhat.id else null, disappearingTime, 0, 0, true)
        }

        var atPersonsString: String? = null
        if (mentions.isNotEmpty()) {
            val atPersons = StringBuilder()
            mentions.forEach { mention ->
                atPersons.append(mention.uid)
                atPersons.append(";")
            }
            atPersonsString = if (atPersons.isNotEmpty()) atPersons.substring(0, atPersons.length - 1) else null
        }

        val textMessage = TextMessage(
            messageId,
            For.Account(globalServices.myId),
            forWhat,
            timeStamp,
            timeStamp,
            System.currentTimeMillis(),
            SendType.Sending.rawValue,
            disappearingTime,
            0,
            0,
            conversationSet?.confidentialMode ?: 0,
            content,
            null,
            quote,
            forwardContext,
            recall,
            null,
            mentions.toMutableList(),
            atPersonsString,
            reactions.toMutableList(),
            screenShot,
            sharedContacts.toMutableList()
        )
        ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, textMessage))

        if (reactions.isEmpty() && recall == null) {
            chatViewModel.addOneMessage(textMessage)
        }

        checkAndSendAddFriendRequest()

        resetData()
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
        ContactorUtil.fetchAddFriendRequest(requireContext(), SecureSharedPrefsUtil.getToken(), chatViewModel.forWhat.id, sourceType, source, action)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    if (action == "accept") {
                        isFriend = true
                        updateSubmitButtonView()
                        updateBottomView()
                        ContactorUtil.emitFriendStatusUpdate(chatViewModel.forWhat.id, true)
                    }
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
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
        val fileName = originalFileName ?: FileUtils.getFileName(attachmentUri.path).replace(" ", "")
        val filePath = FileUtil.getMessageAttachmentFilePath(messageId) + fileName

        Single.fromCallable { FileUtils.copy(attachmentUri.path, filePath) }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this@ChatMessageInputFragment))
            .subscribe({
//                MyBlobProvider.getInstance().delete(attachmentUri)
                FileUtil.deleteTempFile(FileUtils.getFileName(attachmentUri.path))
                sendAttachmentPush(timeStamp, messageId, filePath, fileName, mimeType, isAudioMessage)
            }, { it.printStackTrace() })
    }

    private fun sendAttachmentPush(
        timeStamp: Long,
        messageId: String,
        filePath: String,
        fileName: String,
        mimeType: String,
        isAudioMessage: Boolean = false
    ) {
        val forWhat = if (isGroup) For.Group(chatViewModel.forWhat.id) else For.Account(chatViewModel.forWhat.id)

        Single.fromCallable {
            val mediaWidthAndHeight = MediaUtil.getMediaWidthAndHeight(filePath, mimeType)
            val fileSize = FileUtils.getLength(filePath)

            val attachment = Attachment(
                messageId,
                0,
                mimeType,
                "".toByteArray(),
                fileSize.toInt(),
                "".toByteArray(),
                "".toByteArray(),
                fileName,
                if (isAudioMessage) 1 else 0,
                mediaWidthAndHeight.first,
                mediaWidthAndHeight.second,
                filePath,
                AttachmentStatus.LOADING.code
            )

            attachment
        }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ attachment ->
                val attachmentMessage = TextMessage(
                    messageId,
                    For.Account(globalServices.myId),
                    forWhat,
                    timeStamp,
                    timeStamp,
                    System.currentTimeMillis(),
                    SendType.Sending.rawValue,
                    disappearingTime,
                    0,
                    0,
                    conversationSet?.confidentialMode ?: 0,
                    "",
                    mutableListOf(attachment),
                    quote = quote,
                    playStatus = AudioMessageManager.PLAY_STATUS_NOT_PLAY
                )
                ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, attachmentMessage))

                chatViewModel.addOneMessage(attachmentMessage)

                checkAndSendAddFriendRequest()

                resetData()
            }, { it.printStackTrace() })
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
        reactions.add(reaction)

        sendTextPush(null, timeStamp = timeStamp)
    }

    private fun recallMessage(messageID: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val originMessage = wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))
            if (originMessage == null) {
                L.w { "Recall message failed, original message not found: $messageID" }
                resetData()
                return@launch
            }
            if (originMessage.fromWho != globalServices.myId) {
                L.w { "Recall message failed, the sender does not match, realSource:${originMessage.fromWho}" }
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

            resetData()
        }
    }

    private fun resendMessage(messageID: String) {
        Single.fromCallable {
            wcdb.message.getFirstObject(DBMessageModel.id.eq(messageID))?.convertToTextMessage()?.let { listOf(it) }.orEmpty()
        }
            .compose(RxUtil.getSingleSchedulerComposer<List<TextMessage>>())
            .to(RxUtil.autoDispose(this))
            .subscribe({ messages: List<TextMessage>? ->
                messages?.firstOrNull()?.let {
                    ApplicationDependencies.getJobManager().add(pushTextSendJobFactory.create(null, it))
                }
            }) { error: Throwable -> error.printStackTrace() }
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
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    val list = result.filter { it.size < FileUtil.MAX_SUPPORT_FILE_SIZE }
                    if (list.isNotEmpty()) {
                        val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                            putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, ArrayList(list))
                        }
                        mediaSelectActivityLauncher.launch(intent)
                    }
                    if (list.size < result.size) {
                        ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                    }
                }

                override fun onCancel() {
                }
            })
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
        // Handle file paste - similar to fileActivityLauncher callback
        viewLifecycleOwner.lifecycleScope.launch {
            // 优先判断文件大小是否超过200MB
            val fileSize = withContext(Dispatchers.IO) { FileUtil.getFileSize(uri) }
            if (fileSize >= FileUtil.MAX_SUPPORT_FILE_SIZE) {
                ToastUtil.showLong(getString(R.string.max_support_file_size_limit))
                return@launch
            }

            val file = withContext(Dispatchers.IO) {
                runCatching { FileUtil.copyUriToFile(uri) }
                    .onFailure { L.e { "copyUriToFile failed: ${it.stackTraceToString()}" } }
                    .getOrNull()
            }

            if (file == null) {
                ToastUtil.showLong(R.string.unsupported_file_type)
                return@launch
            }

            val path = file.absolutePath

            if (MediaUtil.isImageType(mimeType) || MediaUtil.isVideoType(mimeType)) {
                val localMedia = LocalMedia().apply {
                    this.realPath = path
                    this.mimeType = mimeType
                    this.fileName = FileUtils.getFileName(path)
                }
                val intent = Intent(requireContext(), MediaSelectionActivity::class.java).apply {
                    putParcelableArrayListExtra(MediaSelectionActivity.MEDIA, arrayListOf(localMedia))
                }
                mediaSelectActivityLauncher.launch(intent)
            } else {
                prepareSendAttachmentPush(path.toUri(), mimeType)
            }
        }
    }

}