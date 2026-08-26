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
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.InsetAwareConstraintLayout
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
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

/**
 * Chat activity displayed as a popup bottom sheet.
 * Used when opening chat from ContactDetail popup.
 */
@AndroidEntryPoint
class ChatPopupActivity : BaseActivity(), ChatMessageListProvider, KeyboardPanelHost {

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
            // Launch in a new task to isolate keyboard events from background Activity
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
    lateinit var globalConfigsManager: GlobalConfigsManager

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    private lateinit var backPressedCallback: OnBackPressedCallback

    // Constructed eagerly in onCreate rather than by lazy: a child fragment hosted here can call
    // back into the KeyboardPanelHost overrides below, and a lazy initializer would leave a
    // reentrancy window where those callbacks touch a half-built controller.
    private lateinit var sheetController: PopupChatSheetController

    // Disable BaseActivity auto padding - this Activity handles insets itself
    override fun shouldApplySystemBarsPadding(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force initialize ViewModels before child fragments are created.
        // Fragments declared in layout via android:name will be created when mBinding is accessed,
        // and they use this Activity's ViewModelStore. We must ensure ViewModels exist first.
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

    /**
     * Maximize to full ChatActivity and close this popup.
     * Animates the bottom sheet to full screen height before transitioning.
     */
    fun maximizeToFullActivity() {
        // Get current scroll position before animation
        val scrollPosition = getChatMessageListFragment()?.getFirstVisibleMessageTimestamp()

        sheetController.maximize {
            // Use scroll position if available, otherwise fallback to original jumpMessageTimeStamp
            ChatActivity.startActivity(
                this,
                chatViewModel.forWhat.id,
                sourceType = intent.sourceType,
                source = intent.source,
                jumpMessageTimeStamp = scrollPosition ?: intent.jumpMessageTimeStamp
            )
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, 0)
        }
    }

    /** Cache: whether confidential toggle should be hidden (group member limit or bot chat) */
    private var shouldHideConfidential = false

    private fun onCreateForShowingMessages(savedInstanceState: Bundle?) {
//        ScreenShotUtil.forceDisable(this)

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

        chatViewModel.voiceVisibilityChange
            .onEach {
                mBinding.clVoiceRecord.visibility = if (it) View.VISIBLE else View.GONE
            }
            .catch { L.w { "[ChatPopupActivity] observe voiceVisibilityChange error: ${it.stackTraceToString()}" } }
            .launchIn(lifecycleScope)

        mBinding.vVoiceRecorder.onRecordingDismissed = {
            mBinding.vVoiceRecordBg.visibility = View.GONE
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
                    chatSettingViewModel.setConversationConfigs(
                        this,
                        chatViewModel.forWhat.id,
                        null,
                        null,
                        null,
                        confidentialMode
                    )
                }
            } else {
                chatSettingViewModel.setConversationConfigs(
                    this,
                    chatViewModel.forWhat.id,
                    null,
                    null,
                    null,
                    confidentialMode
                )
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

    /**
     * Show confidential message first-use tip dialog
     */
    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        // Mark as shown when dialog is displayed
        globalServices.userManager.update { hasShownConfidentialTip = true }
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
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

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        if (chatViewModel.forWhat.id.isOfficialAccount()) {
            shouldHideConfidential = true
            updateConfidentialUI(conversationSet)
            return
        }
        if (chatViewModel.forWhat is For.Group) {
            // Check group member count asynchronously
            lifecycleScope.launch(Dispatchers.IO) {
                val memberCount = wcdb.getGroupMemberCount(chatViewModel.forWhat.id)
                val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
                shouldHideConfidential = memberCount >= limit

                withContext(Dispatchers.Main) {
                    updateConfidentialUI(conversationSet)
                }
            }
        } else {
            // 1-on-1 chat, no limit
            shouldHideConfidential = false
            updateConfidentialUI(conversationSet)
        }
    }

    private fun updateConfidentialUI(conversationSet: ConversationSetResponseBody? = null) {
        if (shouldHideConfidential) {
            // Hide confidential toggle when group member limit exceeded
            mBinding.includeFullInput.ivFullInputConfidential.visibility = View.GONE
            return
        }

        mBinding.includeFullInput.ivFullInputConfidential.visibility = View.VISIBLE
        if (conversationSet?.confidentialMode == 1) {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_enable)
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 1
        } else {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable)
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

    // --- KeyboardPanelHost ------------------------------------------------------------------
    // ChatMessageInputFragment is a direct FragmentContainerView child here, so it has no parent
    // fragment and resolves its keyboard/panel host to this Activity. Every method forwards to the
    // sheet controller, the single owner of the sheet's height and bottom padding.

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
        // Guard: onCreate may abort before the controller is built (e.g. super.onCreate throws).
        if (::sheetController.isInitialized) {
            sheetController.release()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        messageNotificationUtil.cancelNotificationsByConversation(chatViewModel.forWhat.id)
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)
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

    /**
     * Fetch latest contact info from server, compare with local data,
     * and refresh UI + message contact cache if changed.
     * Bypasses the global event bus to avoid circular refresh.
     */
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