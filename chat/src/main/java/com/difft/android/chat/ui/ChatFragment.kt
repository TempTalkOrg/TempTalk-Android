package com.difft.android.chat.ui

import android.Manifest
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.R
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.compose.ConfidentialTipDialogContent
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatFragmentChatBinding
import com.difft.android.chat.group.ChatUIData
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.widget.RecordingState
import com.difft.android.network.responses.ConversationSetResponseBody
import com.luck.picture.lib.utils.ToastUtils
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.await
import org.difft.app.database.models.ContactorModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.util.ViewUtil
import javax.inject.Inject

/**
 * ChatFragment - 单聊页面 Fragment
 * 用于支持大屏双栏布局
 */
@AndroidEntryPoint
class ChatFragment : Fragment(), ChatMessageListProvider {

    companion object {
        const val ARG_CONTACT_ID = "ARG_CONTACT_ID"
        const val ARG_SOURCE_TYPE = "ARG_SOURCE_TYPE"
        const val ARG_SOURCE = "ARG_SOURCE"
        const val ARG_JUMP_MESSAGE_TIMESTAMP = "ARG_JUMP_MESSAGE_TIMESTAMP"

        fun newInstance(
            contactId: String,
            sourceType: String? = null,
            source: String? = null,
            jumpMessageTimestamp: Long? = null
        ): ChatFragment {
            return ChatFragment().apply {
                arguments = bundleOf(
                    ARG_CONTACT_ID to contactId,
                    ARG_SOURCE_TYPE to sourceType,
                    ARG_SOURCE to source,
                    ARG_JUMP_MESSAGE_TIMESTAMP to (jumpMessageTimestamp ?: 0L)
                )
            }
        }
    }

    private var _binding: ChatFragmentChatBinding? = null
    private val binding get() = _binding!!

    private val contactId: String by lazy {
        arguments?.getString(ARG_CONTACT_ID) ?: ""
    }

    private val chatViewModel: ChatMessageViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatMessageViewModelFactory> {
            it.create(
                difft.android.messageserialization.For.Account(contactId),
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
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    private val onAudioPermissionForMessage = registerPermission {
        onAudioPermissionForMessageResult(it)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ChatFragmentChatBinding.inflate(inflater, container, false)
        
        // Force initialize ViewModels before child fragments are created.
        // Child fragments (ChatHeaderFragment, etc.) use this fragment as ViewModel owner,
        // and their onCreateView runs before our onViewCreated.
        // By accessing ViewModels here, we ensure they exist when children access them.
        chatViewModel
        chatSettingViewModel
        
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            onCreateForShowingMessages(savedInstanceState)
        }
        registerCallStatusViewListener()
    }

    override fun onResume() {
        super.onResume()

        // Handle IME insets in Fragment
        // ChatActivity has disabled bottom padding, so this Fragment is responsible
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
            // Normal mode: ChatActivity disabled bottom padding, Fragment handles both nav bar and IME
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

    private fun onCreateForShowingMessages(savedInstanceState: Bundle?) {
//        ScreenShotUtil.forceDisable(requireActivity())

        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No contactID detected. Finishing Activity.")
            requireActivity().finish()
            return
        }

        refreshContact()
        fetchContactInfoFromServer()

        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
            .subscribe({
                if (it.contains(chatViewModel.forWhat.id)) {
                    refreshContact()
                }
            }, {
                L.w { "[ChatFragment] observe contactsUpdate error: ${it.stackTraceToString()}" }
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
            }, { L.w { "[ChatFragment] observe voiceVisibilityChange error: ${it.stackTraceToString()}" } })

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
            }, { L.w { "[ChatFragment] observe showOrHideFullInput error: ${it.stackTraceToString()}" } })

        binding.includeFullInput.ivFullInputClose.setOnClickListener {
            binding.includeFullInput.edittextFullInput.clearFocus()
            ViewUtil.hideKeyboard(requireActivity(), binding.includeFullInput.edittextFullInput)
            chatViewModel.showOrHideFullInput(false, binding.includeFullInput.edittextFullInput.text.toString().trim())
        }
        binding.includeFullInput.ivFullInputConfidential.setOnClickListener { view ->
            val confidentialMode = if (view.tag == 0) 1 else 0
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

        updateConfidential()
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach {
                updateConfidential(it)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    /**
     * Show confidential message first-use tip dialog
     */
    private fun showConfidentialTipDialog(onConfirm: () -> Unit) {
        // Mark as shown when dialog is displayed
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

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        if (chatViewModel.forWhat.id.isBotId()) {
            binding.includeFullInput.ivFullInputConfidential.visibility = View.GONE
            return
        }
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

    private fun refreshContact() {
        ContactorUtil.getContactWithID(requireContext(), chatViewModel.forWhat.id)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(viewLifecycleOwner))
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
            }) { L.w { "[ChatFragment] refreshContact error: ${it.stackTraceToString()}" } }
    }

    /**
     * Fetch latest contact info from server, compare with local data,
     * and refresh UI + message contact cache if changed.
     * Bypasses the global event bus to avoid circular refresh.
     */
    private fun fetchContactInfoFromServer() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val contacts = ContactorUtil.fetchContactors(
                    listOf(chatViewModel.forWhat.id), requireContext()
                ).await()
                if (!isAdded || view == null) return@launch
                val serverContact = contacts.firstOrNull() ?: return@launch
                val currentContact = chatViewModel.chatUIData.value.contact
                if (currentContact != serverContact) {
                    L.i { "[ChatFragment] Contact info changed for ${chatViewModel.forWhat.id}, update UI" }
                    chatViewModel.setChatUIData(ChatUIData(serverContact, null))
                    chatViewModel.refreshContactorInCache(serverContact)
                } else {
                    L.d { "[ChatFragment] Contact info unchanged for ${chatViewModel.forWhat.id}, skip refresh" }
                }
            } catch (e: Exception) {
                L.w { "[ChatFragment] fetchContactInfoFromServer error: ${e.message}" }
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
        // 确保通话栏 Fragment 初始化，避免状态由 Fragment 决定时无法触发
        ensureCallHeaderFragment()

        // 先设置初始状态，确保页面进入时立即显示正确的可见性
        binding.fragmentContainerViewCall.visibility =
            if (onGoingCallStateManager.chatHeaderCallVisibility.value) View.VISIBLE else View.GONE

        // 监听后续的状态变化
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

    /**
     * Focus the input field and show keyboard.
     * Used when returning from contact detail popup.
     */
    fun focusInputAndShowKeyboard() {
        val inputFragment = childFragmentManager.findFragmentById(R.id.fragment_container_view_input) as? ChatMessageInputFragment
        inputFragment?.focusInputAndShowKeyboard()
    }
}