package com.difft.android.chat.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.common.ScreenShotUtil
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE
import com.difft.android.chat.contacts.contactsdetail.BUNDLE_KEY_SOURCE_TYPE
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.ChatActivityChatBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.setting.ChatSettingUtils
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.widget.RecordingState
import com.difft.android.create
import com.difft.android.network.responses.ConversationSetResponseBody
import com.hi.dhl.binding.viewbind
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.launch
import org.difft.app.database.models.ContactorModel
import org.thoughtcrime.securesms.components.reaction.MotionEventRelay
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.ViewUtil
import javax.inject.Inject

@AndroidEntryPoint
class ChatActivity : BaseActivity(), ChatMessageListProvider {

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
            val intent = Intent(activity, ChatActivity::class.java)
            intent.contactorID = contactID
            intent.sourceType = sourceType
            intent.source = source
            intent.jumpMessageTimeStamp = jumpMessageTimeStamp
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

    private val mBinding: ChatActivityChatBinding by viewbind()

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

    private val motionEventRelay: MotionEventRelay by viewModels()

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ChatBackgroundDrawable(this, mBinding.root, true))

        lifecycleScope.launch {
            onCreatForShowingMessages(savedInstanceState)
        }
        registerCallStatusViewListener()
    }

    private fun onCreatForShowingMessages(savedInstanceState: Bundle?) {

        ScreenShotUtil.setScreenShotEnable(this, false)
        //        setContentView(R.layout.chat_activity_chat)
        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No contactID detected. Finishing Activity.")
            finish()
            return
        }
        // chatSettingViewModel.setCurrentTarget(chatViewModel.forWhat) - 不再需要，因为通过构造方法传递

        refreshContact()

        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contains(chatViewModel.forWhat.id)) {
                    refreshContact()
                }
            }, {
                it.printStackTrace()
            })

        ChatSettingUtils.conversationSettingUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it == chatViewModel.forWhat.id) {
                    refreshConversationSetting()
                }
            }, {
                it.printStackTrace()
            })

        messageArchiveManager.getMessageArchiveTime(chatViewModel.forWhat, true)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({}, { it.printStackTrace() })

        chatViewModel.voiceVisibilityChange
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) {
                    mBinding.clVoiceRecord.visibility = View.VISIBLE
                } else {
                    mBinding.clVoiceRecord.visibility = View.GONE
                }
            }, { it.printStackTrace() })

        mBinding.vVoiceRecorder.recordingCallback = { state ->
            when (state) {
                is RecordingState.Started -> {
                    L.i { "[VoiceRecorder] Recording started" }
                    mBinding.vVoiceRecordBg.visibility = View.VISIBLE
                }

                is RecordingState.Stopped -> {
                    L.i { "[VoiceRecorder] Recording stopped. File saved at:${state.filePath}" }
                    mBinding.vVoiceRecordBg.visibility = View.GONE
                    chatViewModel.sendVoiceMessage(state.filePath)
                }

                is RecordingState.TooShort -> {
                    L.i { "[VoiceRecorder] Recording too short" }
                    ToastUtil.showLong(R.string.chat_voice_recording_too_short)
                    mBinding.vVoiceRecordBg.visibility = View.GONE
                }

                is RecordingState.Cancelled -> {
                    L.i { "[VoiceRecorder] Recording cancelled" }
                    mBinding.vVoiceRecordBg.visibility = View.GONE
                }

                is RecordingState.RecordPermissionRequired -> {
                    onAudioPermissionForMessage.launchSinglePermission(Manifest.permission.RECORD_AUDIO)
                }

                is RecordingState.TooLarge -> {
                    L.i { "[VoiceRecorder] Recording file too large" }
                    ToastUtil.showLong(R.string.chat_voice_max_size_limit)
                    mBinding.vVoiceRecordBg.visibility = View.GONE
                }
            }
        }

        chatViewModel.showReactionShade
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) mBinding.reactionsShade.visibility = View.VISIBLE else mBinding.reactionsShade.visibility = View.GONE
            }, { it.printStackTrace() })

        chatViewModel.showOrHideFullInput
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
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
            }, { it.printStackTrace() })

        mBinding.includeFullInput.ivFullInputClose.setOnClickListener {
            mBinding.includeFullInput.edittextFullInput.clearFocus()
            ViewUtil.hideKeyboard(this, mBinding.includeFullInput.edittextFullInput)
            chatViewModel.showOrHideFullInput(false, mBinding.includeFullInput.edittextFullInput.text.toString().trim())
        }
        mBinding.includeFullInput.ivFullInputConfidential.setOnClickListener { view ->
            val confidentialMode = if (view.tag == 0) 1 else 0
            chatSettingViewModel.setConversationConfigs(
                this,
                chatViewModel.forWhat.id,
                null,
                null,
                null,
                confidentialMode
            )
        }

        updateConfidential()
        chatSettingViewModel.conversationSet
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                updateConfidential(it)
            }, { it.printStackTrace() })
    }

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        if (conversationSet?.confidentialMode == 1) {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_enable)
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 1
        } else {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable).apply {
                this.setTint(ContextCompat.getColor(this@ChatActivity, com.difft.android.base.R.color.icon))
            }
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

    override fun onBackPressed() {
        if (chatViewModel.selectMessagesState.value.editModel) {
            chatViewModel.selectMessagesState.value =
                chatViewModel.selectMessagesState.value.copy(editModel = false)

        } else
            return super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()

        refreshConversationSetting()

        messageNotificationUtil.cancelNotificationsByConversation(chatViewModel.forWhat.id)
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)
        messageNotificationUtil.cancelCriticalAlertNotification(chatViewModel.forWhat.id)
    }

    override fun onPause() {
        super.onPause()
        SendMessageUtils.removeFromCurrentChat(chatViewModel.forWhat.id)
    }

    private fun refreshConversationSetting() {
        chatSettingViewModel.getConversationConfigs(this, listOf(chatViewModel.forWhat.id))
    }

    private fun refreshContact() {
        ContactorUtil.getContactWithID(this, chatViewModel.forWhat.id)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    chatViewModel.setChatUIData(
                        ChatUIData(
                            it.get(),
                            null
                        )
                    )
                } else {
                    chatViewModel.setChatUIData(
                        ChatUIData(
                            ContactorModel().apply { id = chatViewModel.forWhat.id },
                            null
                        )
                    )
                }
            }) { it.printStackTrace() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        return motionEventRelay.offer(ev) || super.dispatchTouchEvent(ev)
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

    private fun registerCallStatusViewListener() {
        LCallManager.chatHeaderCallVisibility
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ isVisible ->
                mBinding.fragmentContainerViewCall.visibility = if (isVisible) View.VISIBLE else View.GONE
            }, {
                L.e { "[Call] ChatActivity callStatusView listener error = ${it.message}" }
                it.printStackTrace()
            })
    }

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        return supportFragmentManager.findFragmentById(R.id.fragment_container_view_contents) as? ChatMessageListFragment
    }
}