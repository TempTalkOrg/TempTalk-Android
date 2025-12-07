package com.difft.android.chat.group

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
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
import com.difft.android.chat.R
import com.difft.android.chat.common.ScreenShotUtil
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.databinding.ChatActivityGroupChatContentBinding
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatMessageViewModel
import com.difft.android.chat.widget.RecordingState
import com.difft.android.create
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.responses.ConversationSetResponseBody
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.launch
import org.difft.app.database.models.GroupModel
import org.thoughtcrime.securesms.components.reaction.MotionEventRelay
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.ViewUtil
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
@AndroidEntryPoint
class GroupChatContentActivity : BaseActivity(), ChatMessageListProvider {
    companion object {
        const val INTENT_EXTRA_GROUP_ID = "INTENT_EXTRA_GROUP_ID"

        var Intent.groupID: String?
            get() = getStringExtra(INTENT_EXTRA_GROUP_ID)
            set(value) {
                putExtra(INTENT_EXTRA_GROUP_ID, value)
            }

        fun startActivity(
            activity: Context,
            groupID: String,
            jumpMessageTimeStamp: Long? = null
        ) {
            val intent = Intent(activity, GroupChatContentActivity::class.java)
            intent.groupID = groupID
            intent.jumpMessageTimeStamp = jumpMessageTimeStamp
            activity.startActivity(intent)
        }
    }

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
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private val mBinding: ChatActivityGroupChatContentBinding by viewbind()

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ChatBackgroundDrawable(this, mBinding.root, true))

        lifecycleScope.launch {
            onCreateForShowingMessages()
        }
    }

    private fun onCreateForShowingMessages() {
        ScreenShotUtil.setScreenShotEnable(this, false)

        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No groupID detected. Finishing Activity.")
            finish()
            return
        }
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)

        // chatSettingViewModel.setCurrentTarget(chatViewModel.forWhat) - 不再需要，因为通过构造方法传递

        getGroupInfo(false)

        Observable.just(Unit)
            .delay(2000, TimeUnit.MILLISECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this@GroupChatContentActivity))
            .subscribe({
                getGroupInfo(true)
            }, {})

        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.gid == chatViewModel.forWhat.id) {
                    chatViewModel.setChatUIData(
                        ChatUIData(null, it)
                    )
                }
            }, {
                it.printStackTrace()
            })

        chatSettingViewModel.getConversationConfigs(this, listOf(chatViewModel.forWhat.id))

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
                this.setTint(ContextCompat.getColor(this@GroupChatContentActivity, com.difft.android.base.R.color.icon))
            }
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 0
        }
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

    override fun onBackPressed() {
        if (chatViewModel.selectMessagesState.value.editModel) {
            chatViewModel.selectMessagesState.value =
                chatViewModel.selectMessagesState.value.copy(editModel = false)

        } else
            return super.onBackPressed()
    }
//    private var defaultArchiveTimeCreated = false
//    private fun createDefaultArchiveTime() {
//        if (defaultArchiveTimeCreated) return
//        chatSettingViewModel
//            .loadSelectedOption()
//            .compose(RxUtil.getSingleSchedulerComposer())
//            .to(RxUtil.autoDispose(this))
//            .subscribe({
//                ContactorUtil.createDefaultArchiveTimeNotify(groupID, it.toDuration().inWholeMilliseconds, true)
//            }, {
//                it.printStackTrace()
//            })
//        defaultArchiveTimeCreated = true
//    }

    private fun getGroupInfo(forceUpdate: Boolean = false) {
        GroupUtil.getSingleGroupInfo(this, chatViewModel.forWhat.id, forceUpdate)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.isPresent) {
                    chatViewModel.setChatUIData(ChatUIData(null, it.get()))
                } else {
                    if (!forceUpdate) {
                        chatViewModel.setChatUIData(
                            ChatUIData(
                                null, GroupModel().apply {
                                    gid = chatViewModel.forWhat.id
                                }
                            )
                        )
                    }
                }
            }, {
                it.printStackTrace()
            })
    }

    fun openGroupInfoActivity() {
        groupInfoActivityLauncher.launch(
            Intent(this, GroupInfoActivity::class.java).putExtra(KEY_GROUP_ID, chatViewModel.forWhat.id)
        )
    }

    private val groupInfoActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            finish()
        }
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

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        // 在NavHostFragment中查找GroupChatFragment，然后获取其中的ChatMessageListFragment
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.fragment_container_view_content)
        val groupChatFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
        return groupChatFragment?.childFragmentManager?.findFragmentById(R.id.fragment_container_view_message_list) as? ChatMessageListFragment
    }
}