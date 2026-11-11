package com.difft.android.chat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.ConversationOpener
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.messages.PriorConversationReporter
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE_TYPE
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatActivityChatPopupBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.popup.PopupChatSheetController
import com.difft.android.chat.ui.popup.PopupSheetViews
import com.difft.android.chat.widget.RecordingState
import com.difft.android.chat.widget.VoiceRecorderView
import com.difft.android.create
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.responses.ConversationSetResponseBody
import com.hi.dhl.binding.viewbind
import com.difft.android.selector.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.wcdb
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.ViewUtil
import javax.inject.Inject





@AndroidEntryPoint
class ChatPopupActivity : BaseActivity(), ChatMessageListProvider, KeyboardPanelHost, VoiceRecordHost {

    override val voiceRecorderView: VoiceRecorderView?
        get() = mBinding.vVoiceRecorder

    override fun setVoiceRecordOverlayVisible(visible: Boolean) {
        mBinding.clVoiceRecord.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"
        const val JUMP_TO_MESSAGE_ID = "JUMP_TO_MESSAGE_ID"

        fun startActivity(
            activity: Context,
            contactID: String,
            sourceType: String? = null,
            source: String? = null,
            jumpMessageTimeStamp: Long? = null
        ) {
            val intent = Intent(activity, ChatPopupActivity::class.java)
            intent.contactorID = contactID
            intent.sourceType = sourceType
            intent.source = source
            intent.jumpMessageTimeStamp = jumpMessageTimeStamp
            
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }

        var Intent.contactorID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }

        var Intent.sourceType: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE_TYPE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE_TYPE, value)
            }

        var Intent.source: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE, value)
            }

        var Intent.jumpMessageTimeStamp: Long?
            get() = getLongExtra(JUMP_TO_MESSAGE_ID, 0L)
            set(value) {
                putExtra(JUMP_TO_MESSAGE_ID, value)
            }
    }

    private val mBinding: ChatActivityChatPopupBinding by viewbind()

    private val chatViewModel: ChatMessageViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatMessageViewModelFactory> {
            it.create(intent)
        }
    })
    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(chatViewModel.forWhat)
        }
    })

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var priorConversationReporter: PriorConversationReporter

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    private lateinit var backPressedCallback: OnBackPressedCallback

    
    
    
    private lateinit var sheetController: PopupChatSheetController

    
    override fun shouldApplySystemBarsPadding(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        
        
        
        chatViewModel
        chatSettingViewModel

        sheetController = PopupChatSheetController(
            activity = this,
            views = PopupSheetViews(
                coordinatorRoot = mBinding.coordinatorRoot,
                bottomSheet = mBinding.bottomSheet,
                scrim = mBinding.scrim,
                dragHandle = mBinding.dragHandle,
            ),
        )
        sheetController.setup()
        setupBackPressedCallback()

        lifecycleScope.launch {
            onCreateForShowingMessages(savedInstanceState)
        }
    }

    private fun setupBackPressedCallback() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chatViewModel.selectMessagesState.value.editModel) {
                    chatViewModel.selectMessagesState.value =
                        chatViewModel.selectMessagesState.value.copy(editModel = false)
                } else if (!sheetController.dismiss()) {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    



    fun maximizeToFullActivity() {
        
        val scrollPosition = getChatMessageListFragment()?.getFirstVisibleMessageTimestamp()

        
        val open = {
            ChatActivity.startActivity(
                this,
                chatViewModel.forWhat.id,
                sourceType = intent.sourceType,
                source = intent.source,
                jumpMessageTimeStamp = scrollPosition ?: intent.jumpMessageTimeStamp
            )
        }
        if ((applicationContext as? ConversationOpener)?.opensInDetailPane(this) == true) {
            sheetController.handOffToDetailPane {
                open()
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, android.R.anim.fade_out)
            }
            return
        }
        sheetController.maximize {
            open()
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, 0)
        }
    }

    
    private var shouldHideConfidential = false

    private fun onCreateForShowingMessages(savedInstanceState: Bundle?) {


        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No contactID detected. Finishing Activity.")
            finish()
            return
        }

        loadContactInfo()
        fetchContactInfoFromServer()

        ContactorUtil.contactsUpdate
            .onEach {
                if (it.contains(chatViewModel.forWhat.id)) {
                    loadContactInfo()
                }
            }
            .catch { L.w { "[ChatPopupActivity] observe contactsUpdate error: ${it.stackTraceToString()}" } }
            .launchIn(lifecycleScope)

        mBinding.vVoiceRecorder.onRecordingDismissed = {
            mBinding.vVoiceRecordBg.visibility = View.GONE
            mBinding.clVoiceRecord.visibility = View.GONE
        }
        mBinding.vVoiceRecorder.recordingCallback = { state ->
            when (state) {
                is RecordingState.Started -> {
                    L.i { "[VoiceRecorder] Recording started" }
                    mBinding.vVoiceRecordBg.visibility = View.VISIBLE
                }

                is RecordingState.StoppedWithCandidates -> {
                    val file = java.io.File(state.pickedFilePath)
                    if (file.exists() && file.length() > 0) {
                        L.i { "[VoiceRecorder] Recording stopped. size=${file.length()}" }
                        chatViewModel.sendVoiceMessage(state.pickedFilePath)
                    } else {
                        L.w { "[VoiceRecorder] Stopped emitted with invalid file." }
                        ToastUtil.showLong(R.string.chat_voice_record_failed)
                        runCatching { file.delete() }
                    }
                }

                is RecordingState.TooShort -> {
                    L.i { "[VoiceRecorder] Recording too short" }
                    ToastUtil.showLong(R.string.chat_voice_recording_too_short)
                }

                is RecordingState.Cancelled -> {
                    L.i { "[VoiceRecorder] Recording cancelled" }
                }

                is RecordingState.RecordPermissionRequired -> {
                    onAudioPermissionForMessage.launchSinglePermission(Manifest.permission.RECORD_AUDIO)
                }

                is RecordingState.TooLarge -> {
                    L.i { "[VoiceRecorder] Recording file too large" }
                    ToastUtil.showLong(R.string.chat_voice_max_size_limit)
                }

                is RecordingState.RecordFailed -> {
                    L.w { "[VoiceRecorder] Recording failed: reason=${state.reason}" }
                    val msgRes = when (state.reason) {
                        RecordingState.Reason.AUDIO_FOCUS_DENIED -> R.string.chat_voice_focus_denied_hint
                        RecordingState.Reason.RECORDER_INIT_FAILED -> R.string.chat_voice_record_failed
                    }
                    ToastUtil.showLong(msgRes)
                }
            }
        }

        chatViewModel.showOrHideFullInput
            .onEach {
                if (it.first) {
                    mBinding.includeFullInput.clFullInput.visibility = View.VISIBLE
                    mBinding.includeFullInput.edittextFullInput.apply {
                        requestFocus()
                        setText(it.second)
                        setSelection(it.second.length)
                        ViewUtil.focusAndShowKeyboard(this)
                    }
                } else {
                    mBinding.includeFullInput.clFullInput.visibility = View.GONE
                }
            }
            .catch { L.w { "[ChatPopupActivity] observe showOrHideFullInput error: ${it.stackTraceToString()}" } }
            .launchIn(lifecycleScope)

        mBinding.includeFullInput.ivFullInputClose.setOnClickListener {
            mBinding.includeFullInput.edittextFullInput.clearFocus()
            ViewUtil.hideKeyboard(this, mBinding.includeFullInput.edittextFullInput)
            chatViewModel.showOrHideFullInput(false, mBinding.includeFullInput.edittextFullInput.text.toString().trim())
        }
        mBinding.includeFullInput.ivFullInputConfidential.setOnClickListener { view ->
            val confidentialMode = if (view.tag == 0) 1 else 0
            if (confidentialMode == 1 && globalServices.userManager.getUserData()?.hasShownConfidentialTip != true) {
                showConfidentialTipDialog {
                    chatSettingViewModel.setConfidentialMode(confidentialMode)
                }
            } else {
                chatSettingViewModel.setConfidentialMode(confidentialMode)
            }
        }

        updateConfidential()
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateConfidential(it)
            }
            .launchIn(lifecycleScope)
    }

    


    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
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
        }
    }

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        if (chatViewModel.forWhat.id.isOfficialAccount()) {
            shouldHideConfidential = true
            updateConfidentialUI(conversationSet)
            return
        }
        if (chatViewModel.forWhat is For.Group) {
            
            lifecycleScope.launch(Dispatchers.IO) {
                val memberCount = wcdb.getGroupMemberCount(chatViewModel.forWhat.id)
                val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                shouldHideConfidential = memberCount >= limit

                withContext(Dispatchers.Main) {
                    updateConfidentialUI(conversationSet)
                }
            }
        } else {
            
            shouldHideConfidential = false
            updateConfidentialUI(conversationSet)
        }
    }

    private fun updateConfidentialUI(conversationSet: ConversationSetResponseBody? = null) {
        if (shouldHideConfidential) {
            
            mBinding.includeFullInput.ivFullInputConfidential.visibility = View.GONE
            return
        }

        mBinding.includeFullInput.ivFullInputConfidential.visibility = View.VISIBLE
        if (conversationSet?.confidentialMode == 1) {
            mBinding.includeFullInput.ivFullInputConfidential.setImageResource(R.drawable.chat_btn_confidential_mode_enable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 1
        } else {
            mBinding.includeFullInput.ivFullInputConfidential.setImageResource(R.drawable.chat_btn_confidential_mode_disable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

    
    
    
    

    override fun addKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        sheetController.addKeyboardStateListener(listener)
    }

    override fun removeKeyboardStateListener(listener: InsetAwareConstraintLayout.KeyboardStateListener) {
        sheetController.removeKeyboardStateListener(listener)
    }

    override fun freezeKeyboardPadding() {
        sheetController.freezeKeyboardPadding()
    }

    override fun releaseKeyboardPaddingFreeze() {
        sheetController.releaseKeyboardPaddingFreeze()
    }

    override fun onChatPanelVisibilityChanged(visible: Boolean, panelHeightPx: Int) {
        sheetController.onChatPanelVisibilityChanged(visible, panelHeightPx)
    }

    override fun onDestroy() {
        
        if (::sheetController.isInitialized) {
            sheetController.release()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        chatViewModel.onShown()
        messageNotificationUtil.cancelNotificationsByConversation(chatViewModel.forWhat.id)
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)
        priorConversationReporter.onConversationOpened(chatViewModel.forWhat)
        messageNotificationUtil.cancelCriticalAlertNotification(chatViewModel.forWhat.id)
    }

    override fun onPause() {
        super.onPause()
        SendMessageUtils.removeFromCurrentChat(chatViewModel.forWhat.id)
    }

    private fun loadContactInfo() {
        lifecycleScope.launch {
            try {
                val result = ContactorUtil.getContactWithID(this@ChatPopupActivity, chatViewModel.forWhat.id)
                val contact = if (result.isPresent) result.get()
                              else ContactorModel().apply { id = chatViewModel.forWhat.id }
                chatViewModel.setChatUIData(ChatUIData(contact, null))
            } catch (e: Exception) {
                L.w { "[ChatPopupActivity] loadContactInfo error: ${e.message}" }
            }
        }
    }

    




    private fun fetchContactInfoFromServer() {
        lifecycleScope.launch {
            try {
                val contacts = ContactorUtil.fetchContactors(
                    listOf(chatViewModel.forWhat.id), this@ChatPopupActivity
                )
                if (isFinishing) return@launch
                val serverContact = contacts.firstOrNull() ?: return@launch
                val currentContact = chatViewModel.chatUIData.value.contact
                if (currentContact != serverContact) {
                    L.i { "[ChatPopupActivity] Contact info changed for ${chatViewModel.forWhat.id}, update UI" }
                    chatViewModel.setChatUIData(ChatUIData(serverContact, null))
                    chatViewModel.refreshContactorInCache(serverContact)
                    RoomChangeTracker.trackRoom(chatViewModel.forWhat.id, RoomChangeType.CONTACT)
                } else {
                    L.d { "[ChatPopupActivity] Contact info unchanged for ${chatViewModel.forWhat.id}, skip refresh" }
                }
            } catch (e: Exception) {
                L.w { "[ChatPopupActivity] fetchContactInfoFromServer error: ${e.message}" }
            }
        }
    }

    private fun onAudioPermissionForMessageResult(permissionState: PermissionUtil.PermissionState) {
        when (permissionState) {
            PermissionUtil.PermissionState.Denied -> {
                L.d { "onAudioPermissionForMessageResult: Denied" }
                showAudioPermissionDialog()
            }

            PermissionUtil.PermissionState.Granted -> {
                L.d { "onAudioPermissionForMessageResult: Granted" }
            }

            PermissionUtil.PermissionState.PermanentlyDenied -> {
                L.d { "onAudioPermissionForMessageResult: PermanentlyDenied" }
                showAudioPermissionDialog()
            }
        }
    }

    private fun showAudioPermissionDialog() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.tip),
            message = getString(R.string.no_permission_voice_tip),
            confirmText = getString(R.string.notification_go_to_settings),
            cancelText = getString(R.string.notification_ignore),
            cancelable = false,
            onConfirm = {
                PermissionUtil.launchSettings(this)
            },
            onCancel = {
                ToastUtils.showToast(
                    this, getString(R.string.not_granted_necessary_permissions)
                )
            }
        )
    }

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        return supportFragmentManager.findFragmentById(R.id.fragment_container_view_contents) as? ChatMessageListFragment
    }
}