package com.difft.android.chat.group

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.utils.DualPaneUtils.isInDualPaneMode
import com.difft.android.ChatMessageViewModelFactory
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.android.permission.PermissionUtil.registerPermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.R
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.databinding.ChatFragmentGroupChatBinding
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatHeaderCallFragment
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
import com.difft.android.chat.ui.ChatMessageViewModel
import com.difft.android.chat.widget.RecordingState
import com.difft.android.network.group.GroupRepo
import com.difft.android.network.responses.ConversationSetResponseBody
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.getGroupMemberCount
import org.difft.app.database.wcdb
import org.difft.app.database.models.GroupModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.ViewUtil
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * GroupChatFragment - 群聊页面 Fragment
 * 用于支持大屏双栏布局
 */
@AndroidEntryPoint
class GroupChatFragment : Fragment(), ChatMessageListProvider {

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
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

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
        onCreateForShowingMessages()
        registerCallStatusViewListener()
    }

    override fun onResume() {
        super.onResume()

        // Handle IME insets in Fragment
        // GroupChatContentActivity has disabled bottom padding, so this Fragment is responsible
        setupImeInsets()

        messageNotificationUtil.cancelNotificationsByConversation(chatViewModel.forWhat.id)
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)
        messageNotificationUtil.cancelCriticalAlertNotification(chatViewModel.forWhat.id)
    }

    override fun onPause() {
        super.onPause()

        // Clear IME insets listener to prevent background Fragment from responding to keyboard
        // Note: Don't reset padding here to avoid visual jump during page transitions
        // The correct padding will be restored via requestApplyInsets() in onResume()
        _binding?.root?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }

        SendMessageUtils.removeFromCurrentChat(chatViewModel.forWhat.id)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.root?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
        _binding = null
    }

    /**
     * Setup IME insets handling.
     * Adjusts Fragment root view's bottom padding when keyboard appears to push up the input box.
     */
    private fun setupImeInsets() {
        val rootView = _binding?.root ?: return
        val isDualPane = isInDualPaneMode()
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            // Dual-pane: IndexActivity handles nav bar padding, Fragment only handles IME portion above nav bar
            // Normal mode: GroupChatContentActivity disabled bottom padding, Fragment handles both nav bar and IME
            val bottomPadding = if (isDualPane) {
                // Dual-pane: subtract nav bar height (already handled by Activity) to avoid double padding
                // When keyboard hidden: imeHeight=0, result=0 (no extra padding)
                // When keyboard shown: only add the portion above nav bar
                maxOf(0, imeHeight - navigationBarHeight)
            } else {
                // Normal mode: use IME height or nav bar height (whichever applies)
                if (imeHeight > 0) imeHeight else navigationBarHeight
            }
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                bottomPadding
            )
            insets
        }
        rootView.requestApplyInsets()
    }

    private fun onCreateForShowingMessages() {
//        ScreenShotUtil.forceDisable(requireActivity())

        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No groupID detected. Finishing Activity.")
            requireActivity().finish()
            return
        }
        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)

        getGroupInfo(false)

        Observable.just(Unit)
            .delay(2000, TimeUnit.MILLISECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
                getGroupInfo(true)
            }, {})

        GroupUtil.singleGroupsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
                if (it.gid == chatViewModel.forWhat.id) {
                    chatViewModel.setChatUIData(
                        ChatUIData(null, it)
                    )
                    // Refresh confidential toggle when group members change
                    updateConfidential()
                }
            }, {
                L.w { "[GroupChatFragment] observe singleGroupsUpdate error: ${it.stackTraceToString()}" }
            })

        chatViewModel.voiceVisibilityChange
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
                if (it) {
                    binding.clVoiceRecord.visibility = View.VISIBLE
                } else {
                    binding.clVoiceRecord.visibility = View.GONE
                }
            }, { L.w { "[GroupChatFragment] observe voiceVisibilityChange error: ${it.stackTraceToString()}" } })

        binding.vVoiceRecorder.recordingCallback = { state ->
            when (state) {
                is RecordingState.Started -> {
                    L.i { "[VoiceRecorder] Recording started" }
                    binding.vVoiceRecordBg.visibility = View.VISIBLE
                }

                is RecordingState.Stopped -> {
                    L.i { "[VoiceRecorder] Recording stopped. File saved at:${state.filePath}" }
                    binding.vVoiceRecordBg.visibility = View.GONE
                    chatViewModel.sendVoiceMessage(state.filePath)
                }

                is RecordingState.TooShort -> {
                    L.i { "[VoiceRecorder] Recording too short" }
                    ToastUtil.showLong(R.string.chat_voice_recording_too_short)
                    binding.vVoiceRecordBg.visibility = View.GONE
                }

                is RecordingState.Cancelled -> {
                    L.i { "[VoiceRecorder] Recording cancelled" }
                    binding.vVoiceRecordBg.visibility = View.GONE
                }

                is RecordingState.RecordPermissionRequired -> {
                    onAudioPermissionForMessage.launchSinglePermission(Manifest.permission.RECORD_AUDIO)
                }

                is RecordingState.TooLarge -> {
                    L.i { "[VoiceRecorder] Recording file too large" }
                    ToastUtil.showLong(R.string.chat_voice_max_size_limit)
                    binding.vVoiceRecordBg.visibility = View.GONE
                }
            }
        }

        chatViewModel.showOrHideFullInput
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
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
            }, { L.w { "[GroupChatFragment] observe showOrHideFullInput error: ${it.stackTraceToString()}" } })

        binding.includeFullInput.ivFullInputClose.setOnClickListener {
            binding.includeFullInput.edittextFullInput.clearFocus()
            ViewUtil.hideKeyboard(requireActivity(), binding.includeFullInput.edittextFullInput)
            chatViewModel.showOrHideFullInput(false, binding.includeFullInput.edittextFullInput.text.toString().trim())
        }
        binding.includeFullInput.ivFullInputConfidential.setOnClickListener { view ->
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
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable).apply {
                this.setTint(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.icon))
            }
            binding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            binding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

    private fun getGroupInfo(forceUpdate: Boolean = false) {
        GroupUtil.getSingleGroupInfo(requireContext(), chatViewModel.forWhat.id, forceUpdate)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
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
                L.w { "[GroupChatFragment] getGroupInfo error: ${it.stackTraceToString()}" }
            })
    }

    fun openGroupInfoActivity() {
        groupInfoActivityLauncher.launch(
            Intent(requireContext(), GroupInfoActivity::class.java).putExtra(KEY_GROUP_ID, chatViewModel.forWhat.id)
        )
    }

    private val groupInfoActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            requireActivity().finish()
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
        )
    }

    private fun registerCallStatusViewListener() {
        // Ensure call header fragment is initialized
        ensureCallHeaderFragment()

        // Set initial state
        binding.fragmentContainerViewCall.visibility =
            if (onGoingCallStateManager.chatHeaderCallVisibility.value) View.VISIBLE else View.GONE

        // Observe state changes
        onGoingCallStateManager.chatHeaderCallVisibility
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { isVisible ->
                binding.fragmentContainerViewCall.visibility = if (isVisible) View.VISIBLE else View.GONE
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