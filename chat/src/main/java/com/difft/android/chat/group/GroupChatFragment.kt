package com.difft.android.chat.group

import android.Manifest
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import com.difft.android.base.utils.pane.dismissWithView
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.pane.ReopenableConversationPage
import com.difft.android.base.utils.pane.SoftInputModeLease
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.R
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.messages.PriorConversationReporter
import com.difft.android.chat.databinding.ChatFragmentGroupChatBinding
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatHeaderCallFragment
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
import com.difft.android.chat.ui.ChatMessageViewModel
import com.difft.android.chat.ui.VoiceRecordHost
import com.difft.android.chat.widget.RecordingState
import com.difft.android.chat.widget.VoiceRecorderView
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.selector.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.wcdb
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import com.difft.android.chat.util.MessageNotificationUtil
import com.difft.android.chat.util.ViewUtil
import javax.inject.Inject

/**
 * GroupChatFragment - 群聊页面 Fragment
 * 用于支持大屏双栏布局
 */
@AndroidEntryPoint
class GroupChatFragment : Fragment(), ChatMessageListProvider, VoiceRecordHost, ReopenableConversationPage {

    override fun onConversationReopened() {
        chatViewModel.onReopened()
    }

    override val voiceRecorderView: VoiceRecorderView?
        get() = _binding?.vVoiceRecorder

    override fun setVoiceRecordOverlayVisible(visible: Boolean) {
        _binding?.clVoiceRecord?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    companion object {
        const val ARG_GROUP_ID = "ARG_GROUP_ID"
        const val ARG_JUMP_MESSAGE_TIMESTAMP = "ARG_JUMP_MESSAGE_TIMESTAMP"

        fun newInstance(
            groupId: String,
            jumpMessageTimestamp: Long? = null
        ): GroupChatFragment {
            return GroupChatFragment().apply {
                arguments = bundleOf(
                    ARG_GROUP_ID to groupId,
                    ARG_JUMP_MESSAGE_TIMESTAMP to (jumpMessageTimestamp ?: 0L)
                )
            }
        }
    }

    private var _binding: ChatFragmentGroupChatBinding? = null
    private val binding get() = _binding!!

    private val groupId: String by lazy {
        arguments?.getString(ARG_GROUP_ID) ?: ""
    }

    private val chatViewModel: ChatMessageViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatMessageViewModelFactory> {
            it.create(
                difft.android.messageserialization.For.Group(groupId),
                arguments?.getLong(ARG_JUMP_MESSAGE_TIMESTAMP) ?: 0L
            )
        }
    })

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(chatViewModel.forWhat)
        }
    })

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var priorConversationReporter: PriorConversationReporter

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var callDataManager: CallDataManager

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    /** Cache: whether confidential toggle should be hidden due to group member limit */
    private var shouldHideConfidentialForGroupLimit = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChatFragmentGroupChatBinding.inflate(inflater, container, false)
        
        // Force initialize ViewModels before child fragments are created.
        // Child fragments (GroupChatHeaderFragment, etc.) use this fragment as ViewModel owner,
        // and their onCreateView runs before our onViewCreated.
        // By accessing ViewModels here, we ensure they exist when children access them.
        chatViewModel
        chatSettingViewModel
        
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // API < 30: WindowInsetsAnimationCompat doesn't work with adjustNothing, so the window
        // resizes for the keyboard; shared with the other pages of this window (no-op on 30+).
        SoftInputModeLease.acquireResize(requireActivity(), this, conversationRoot = true)

        onCreateForShowingMessages()
        registerCallStatusViewListener()
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

    override fun onDestroyView() {
        // The last page of this window to let go restores the window's mode, so a conversation
        // closed in another tab does not reset it under a page still using the keyboard.
        SoftInputModeLease.release(requireActivity(), this)
        super.onDestroyView()
        _binding = null
    }

    private fun onCreateForShowingMessages() {
//        ScreenShotUtil.forceDisable(requireActivity())

        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No groupID detected. Finishing Activity.")
            detailNavigation().closeConversation(this)
            return
        }

        getGroupInfo(false)
        getGroupInfo(true)

        groupUtil.singleGroupsUpdate
            .onEach {
                if (!isAdded || view == null) return@onEach
                if (it.gid == chatViewModel.forWhat.id) {
                    if (it.status != 0) {
                        // Group is gone (kicked / left / dismissed / destroyed) — close.
                        detailNavigation().closeConversation(this@GroupChatFragment)
                        return@onEach
                    }
                    chatViewModel.setChatUIData(ChatUIData(null, it))
                    // Refresh confidential toggle when group members change
                    updateConfidential()
                }
            }
            .catch { L.w { "[GroupChatFragment] observe singleGroupsUpdate error: ${it.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.vVoiceRecorder.onRecordingDismissed = {
            binding.vVoiceRecordBg.visibility = View.GONE
            binding.clVoiceRecord.visibility = View.GONE
        }
        binding.vVoiceRecorder.recordingCallback = { state ->
            when (state) {
                is RecordingState.Started -> {
                    L.i { "[VoiceRecorder] Recording started" }
                    binding.vVoiceRecordBg.visibility = View.VISIBLE
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
                if (!isAdded || view == null) return@onEach
                if (it.first) {
                    binding.includeFullInput.clFullInput.visibility = View.VISIBLE
                    binding.includeFullInput.edittextFullInput.apply {
                        requestFocus()
                        setText(it.second)
                        setSelection(it.second.length)
                        ViewUtil.focusAndShowKeyboard(this)
                    }
                } else {
                    binding.includeFullInput.clFullInput.visibility = View.GONE
                }
            }
            .catch { L.w { "[GroupChatFragment] observe showOrHideFullInput error: ${it.stackTraceToString()}" } }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        binding.includeFullInput.ivFullInputClose.setOnClickListener {
            binding.includeFullInput.edittextFullInput.clearFocus()
            ViewUtil.hideKeyboard(requireActivity(), binding.includeFullInput.edittextFullInput)
            chatViewModel.showOrHideFullInput(false, binding.includeFullInput.edittextFullInput.text.toString().trim())
        }
        binding.includeFullInput.ivFullInputConfidential.setOnClickListener { view ->
            val confidentialMode = if (view.tag == 0) 1 else 0
            chatSettingViewModel.setConfidentialMode(confidentialMode)
        }

        updateConfidential()
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateConfidential(it)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        // Check group member count asynchronously
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val memberCount = wcdb.getGroupMemberCount(chatViewModel.forWhat.id)
            val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
            shouldHideConfidentialForGroupLimit = memberCount >= limit

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                updateConfidentialUI(conversationSet)
            }
        }
    }

    private fun updateConfidentialUI(conversationSet: ConversationSetResponseBody? = null) {
        if (shouldHideConfidentialForGroupLimit) {
            // Hide confidential toggle when group member limit exceeded
            binding.includeFullInput.ivFullInputConfidential.visibility = View.GONE
            return
        }

        binding.includeFullInput.ivFullInputConfidential.visibility = View.VISIBLE
        if (conversationSet?.confidentialMode == 1) {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_enable)
            binding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            binding.includeFullInput.ivFullInputConfidential.tag = 1
        } else {
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable)
            binding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            binding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

    private fun getGroupInfo(forceUpdate: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Snapshot local version before force-fetching so we can detect server-side
                // changes and emit a room update event. Only the force-update path compares
                // — cache-hit returns local data unchanged and needs no emit.
                val (oldVersion, oldName, oldAvatar) = if (forceUpdate) {
                    withContext(Dispatchers.IO) {
                        val g = wcdb.group.getFirstObject(DBGroupModel.gid.eq(chatViewModel.forWhat.id))
                        Triple(g?.version, g?.name, g?.avatar)
                    }
                } else Triple(null, null, null)

                val result = groupUtil.getSingleGroupInfo(chatViewModel.forWhat.id, forceUpdate)
                if (result != null) {
                    chatViewModel.setChatUIData(ChatUIData(null, result))
                    if (forceUpdate) {
                        val groupChanged = oldVersion != result.version
                            || oldName != result.name
                            || oldAvatar != result.avatar
                        if (groupChanged) {
                            L.i { "[GroupChatFragment] Group info changed for ${result.gid}, notifying room" }
                            groupUtil.emitSingleGroupUpdate(result)
                        } else {
                            // Group info unchanged: independently check Room/Group drift
                            // (fallback for cold-start race that drops trackRoom events).
                            groupUtil.reconcileRoomIfDrifted(result)
                        }
                    }
                } else if (!forceUpdate) {
                    chatViewModel.setChatUIData(
                        ChatUIData(null, GroupModel().apply { gid = chatViewModel.forWhat.id })
                    )
                }
            } catch (e: Exception) {
                L.w { "[GroupChatFragment] getGroupInfo error: ${e.stackTraceToString()}" }
            }
        }
    }


    // Group info stacks above this conversation in the home detail stack (its shell Activity
    // elsewhere). Leaving or losing the group closes the conversation through the navigation.
    fun openGroupInfoActivity() {
        detailNavigation().push(this, GroupInfoDestination(chatViewModel.forWhat.id))
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
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireContext(),
            title = getString(R.string.tip),
            message = getString(R.string.no_permission_voice_tip),
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

    private fun registerCallStatusViewListener() {
        ensureCallHeaderFragment()

        binding.fragmentContainerViewCall.visibility =
            if (onGoingCallStateManager.hasOtherCallData(callDataManager.callingList.value))
                View.VISIBLE else View.GONE

        combine(
            onGoingCallStateManager.chatHeaderCallVisibility,
            callDataManager.callingList,
            onGoingCallStateManager.isInCalling
        ) { _, callingList, _ ->
            onGoingCallStateManager.hasOtherCallData(callingList)
        }
            .distinctUntilChanged()
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { shouldShow ->
                binding.fragmentContainerViewCall.visibility =
                    if (shouldShow) View.VISIBLE else View.GONE
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun ensureCallHeaderFragment() {
        if (childFragmentManager.findFragmentById(R.id.fragment_container_view_call) != null) {
            return
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)
            replace(R.id.fragment_container_view_call, ChatHeaderCallFragment())
        }
    }

    override fun getChatMessageListFragment(): ChatMessageListFragment? {
        return childFragmentManager.findFragmentById(R.id.fragment_container_view_contents) as? ChatMessageListFragment
    }
}