package com.difft.android.chat.group

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.base.utils.SecureModeUtil
import com.difft.android.chat.common.SendMessageUtils
import com.difft.android.chat.databinding.ChatActivityGroupChatPopupBinding
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatMessageListFragment
import com.difft.android.chat.ui.ChatMessageListProvider
import com.difft.android.chat.ui.ChatMessageViewModel
import com.difft.android.chat.widget.RecordingState
import com.difft.android.create
import com.difft.android.network.responses.ConversationSetResponseBody
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.hi.dhl.binding.viewbind
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
 * Group chat activity displayed as a popup bottom sheet.
 * Used when opening group chat from group member popup.
 */
@AndroidEntryPoint
class GroupChatPopupActivity : BaseActivity(), ChatMessageListProvider {

    companion object {
        const val INTENT_EXTRA_GROUP_ID = "INTENT_EXTRA_GROUP_ID"
        const val JUMP_TO_MESSAGE_ID = "JUMP_TO_MESSAGE_ID"

        fun startActivity(
            activity: Context,
            groupID: String,
            jumpMessageTimeStamp: Long? = null
        ) {
            val intent = Intent(activity, GroupChatPopupActivity::class.java)
            intent.groupID = groupID
            intent.jumpMessageTimeStamp = jumpMessageTimeStamp
            // Launch in a new task to isolate keyboard events from background Activity
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        }

        var Intent.groupID: String?
            get() = getStringExtra(INTENT_EXTRA_GROUP_ID)
            set(value) {
                putExtra(INTENT_EXTRA_GROUP_ID, value)
            }

        var Intent.jumpMessageTimeStamp: Long?
            get() = getLongExtra(JUMP_TO_MESSAGE_ID, 0L)
            set(value) {
                putExtra(JUMP_TO_MESSAGE_ID, value)
            }
    }

    private val mBinding: ChatActivityGroupChatPopupBinding by viewbind()

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

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>
    private lateinit var bottomSheetCallback: BottomSheetBehavior.BottomSheetCallback
    private lateinit var backPressedCallback: OnBackPressedCallback

    // Disable BaseActivity auto padding - this Activity handles insets itself
    override fun shouldApplySystemBarsPadding(): Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Force initialize ViewModels before child fragments are created.
        // Fragments declared in layout via android:name will be created when mBinding is accessed,
        // and they use this Activity's ViewModelStore. We must ensure ViewModels exist first.
        chatViewModel
        chatSettingViewModel

        setupBottomSheet()
        setupBackPressedCallback()

        lifecycleScope.launch {
            onCreateForShowingMessages(savedInstanceState)
        }
    }

    private var isBottomSheetConfigured = false
    private var baseHeight = 0
    private var navigationBarHeight = 0
    private var statusBarHeight = 0

    private fun setupBottomSheet() {
        val bottomSheet = mBinding.bottomSheet
        val scrim = mBinding.scrim

        // Handle window insets for navigation bar and IME (keyboard)
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.coordinatorRoot) { _, insets ->
            navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val isImeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())

            // Configure bottom sheet behavior only once (to get base height)
            if (!isBottomSheetConfigured) {
                configureBottomSheetBehavior(bottomSheet, scrim)
                isBottomSheetConfigured = true
            }

            // Handle keyboard visibility (skip during maximize animation)
            if (!isMaximizeAnimating) {
                if (isImeVisible && imeHeight > 0) {
                    // Keyboard is visible - expand bottom sheet height and add keyboard padding
                    val newHeight = baseHeight + imeHeight
                    bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                        height = newHeight
                    }
                    bottomSheet.setPadding(0, 0, 0, imeHeight)
                    bottomSheetBehavior.peekHeight = newHeight
                } else {
                    // Keyboard is hidden - only adjust padding, keep height to avoid jump during drag
                    bottomSheet.setPadding(0, 0, 0, navigationBarHeight)
                }
            }

            insets
        }
        mBinding.coordinatorRoot.requestApplyInsets()
    }

    private fun configureBottomSheetBehavior(bottomSheet: View, scrim: View) {
        val screenHeight = WindowSizeClassUtil.getWindowHeightPx(this)
        baseHeight = (screenHeight * 0.5).toInt()

        // Set fixed height for bottom sheet
        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = baseHeight
        }

        // Apply navigation bar padding initially
        bottomSheet.setPadding(0, 0, 0, navigationBarHeight)

        // Start scrim transparent, will fade in with bottom sheet animation
        scrim.alpha = 0f

        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.apply {
            peekHeight = baseHeight
            isFitToContents = true
            isHideable = true
            isDraggable = true  // Enable drag to dismiss
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_HIDDEN  // Start hidden for animation
        }

        // Show bottom sheet after layout is complete
        bottomSheet.post {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Scrim click handler - close popup
        scrim.setOnClickListener {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        }

        // Bottom sheet callback - handle state changes and scrim animation
        bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
            private var hasOpened = false  // Track if bottom sheet has finished opening
            private var isClosing = false  // Track if bottom sheet is being closed
            private var keyboardDismissed = false  // Track if keyboard has been dismissed during close

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        // Fade in scrim after bottom sheet is fully visible (first time only)
                        if (!hasOpened) {
                            hasOpened = true
                            scrim.animate()
                                .alpha(0.5f)
                                .setDuration(150)
                                .start()
                        }
                        isClosing = false
                        keyboardDismissed = false
                    }
                    BottomSheetBehavior.STATE_SETTLING -> {
                        // If already opened and now settling, it means closing
                        if (hasOpened) {
                            isClosing = true
                        }
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        finish()
                        @Suppress("DEPRECATION")
                        overridePendingTransition(0, 0)
                    }
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                // slideOffset: -1 (hidden) to 0 (expanded)
                // Hide keyboard when starting to close (covers all close scenarios)
                if (hasOpened && slideOffset < 0 && !keyboardDismissed) {
                    currentFocus?.let { ViewUtil.hideKeyboard(this@GroupChatPopupActivity, it) }
                    keyboardDismissed = true
                }
                // Fade scrim when closing
                if (hasOpened && isClosing && slideOffset < 0) {
                    // Map -1..0 to 0..0.5
                    scrim.alpha = ((slideOffset + 1f) * 0.5f).coerceIn(0f, 0.5f)
                }
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)
    }

    private fun setupBackPressedCallback() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (chatViewModel.selectMessagesState.value.editModel) {
                    chatViewModel.selectMessagesState.value =
                        chatViewModel.selectMessagesState.value.copy(editModel = false)
                } else if (::bottomSheetBehavior.isInitialized) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
                } else {
                    finish()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    /**
     * Maximize to full GroupChatContentActivity and close this popup.
     * Animates the bottom sheet to full screen height before transitioning.
     */
    fun maximizeToFullActivity() {
        // Prevent double-click
        if (isMaximizing) return
        isMaximizing = true

        // Get current scroll position before animation
        val scrollPosition = getChatMessageListFragment()?.getFirstVisibleMessageTimestamp()

        // Hide keyboard first if visible
        val currentFocus = currentFocus
        if (currentFocus != null) {
            ViewUtil.hideKeyboard(this, currentFocus)
        }

        // Disable inset handling during maximize animation
        isMaximizeAnimating = true

        // Hide drag handle so header aligns to top
        mBinding.dragHandle.visibility = View.GONE

        // Use the actual laid out height of coordinatorRoot which spans the full screen in edge-to-edge mode
        val fullHeight = mBinding.coordinatorRoot.height
        // Target height stops at status bar bottom (aligns with full GroupChatContentActivity's title bar position)
        val targetHeight = fullHeight - statusBarHeight
        // Start from current height (which may include keyboard height)
        val currentHeight = mBinding.bottomSheet.layoutParams.height
        // Check if keyboard is visible (height exceeds base height)
        val hasKeyboard = currentHeight > baseHeight
        // Capture initial padding to animate it out smoothly (only needed when keyboard is visible)
        val initialPadding = if (hasKeyboard) mBinding.bottomSheet.paddingBottom else 0

        // Animate bottom sheet to target height (just below status bar)
        android.animation.ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            duration = 250
            addUpdateListener { animator ->
                val height = animator.animatedValue as Int
                mBinding.bottomSheet.layoutParams = mBinding.bottomSheet.layoutParams.apply {
                    this.height = height
                }
                // Gradually reduce bottom padding as we expand (only when keyboard was visible)
                if (hasKeyboard) {
                    val progress = (height - currentHeight).toFloat() / (targetHeight - currentHeight)
                    val newPadding = (initialPadding * (1 - progress)).toInt()
                    mBinding.bottomSheet.setPadding(0, 0, 0, newPadding)
                }
                bottomSheetBehavior.peekHeight = height
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Start full GroupChatContentActivity after animation completes
                    GroupChatContentActivity.startActivity(
                        this@GroupChatPopupActivity,
                        chatViewModel.forWhat.id,
                        jumpMessageTimeStamp = scrollPosition ?: intent.jumpMessageTimeStamp
                    )
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, 0)
                }
            })
            start()
        }

        // Fade out scrim simultaneously
        mBinding.scrim.animate()
            .alpha(0f)
            .setDuration(250)
            .start()
    }

    private var isMaximizing = false
    private var isMaximizeAnimating = false

    /** Cache: whether confidential toggle should be hidden due to group member limit */
    private var shouldHideConfidentialForGroupLimit = false

    private fun onCreateForShowingMessages(savedInstanceState: Bundle?) {
//        ScreenShotUtil.forceDisable(this)

        if (TextUtils.isEmpty(chatViewModel.forWhat.id)) {
            ToastUtil.show("No groupID detected. Finishing Activity.")
            finish()
            return
        }

        SendMessageUtils.addToCurrentChat(chatViewModel.forWhat.id)

        getGroupInfo(false)

        Observable.just(Unit)
            .delay(2000, TimeUnit.MILLISECONDS)
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this@GroupChatPopupActivity))
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
                    // Refresh confidential toggle when group members change
                    updateConfidential()
                }
            }, {
                L.w { "[GroupChatPopupActivity] observe singleGroupsUpdate error: ${it.stackTraceToString()}" }
            })

        chatViewModel.voiceVisibilityChange
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it) {
                    mBinding.clVoiceRecord.visibility = View.VISIBLE
                } else {
                    mBinding.clVoiceRecord.visibility = View.GONE
                }
            }, { L.w { "[GroupChatPopupActivity] observe voiceVisibilityChange error: ${it.stackTraceToString()}" } })

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
            }, { L.w { "[GroupChatPopupActivity] observe showOrHideFullInput error: ${it.stackTraceToString()}" } })

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
            .filterNotNull()
            .onEach {
                updateConfidential(it)
            }
            .launchIn(lifecycleScope)
    }

    private fun updateConfidential(conversationSet: ConversationSetResponseBody? = null) {
        // Check group member count asynchronously
        lifecycleScope.launch(Dispatchers.IO) {
            val memberCount = wcdb.getGroupMemberCount(chatViewModel.forWhat.id)
            val limit = globalConfigsManager.getGroupConfidentialMemberLimit()
            shouldHideConfidentialForGroupLimit = memberCount >= limit

            withContext(Dispatchers.Main) {
                updateConfidentialUI(conversationSet)
            }
        }
    }

    private fun updateConfidentialUI(conversationSet: ConversationSetResponseBody? = null) {
        if (shouldHideConfidentialForGroupLimit) {
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
            val drawable = ResUtils.getDrawable(R.drawable.chat_btn_confidential_mode_disable).apply {
                this.setTint(ContextCompat.getColor(this@GroupChatPopupActivity, com.difft.android.base.R.color.icon))
            }
            mBinding.includeFullInput.ivFullInputConfidential.setImageDrawable(drawable)
            mBinding.includeFullInput.ivFullInputConfidential.tag = 0
        }
    }

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
                L.w { "[GroupChatPopupActivity] getGroupInfo error: ${it.stackTraceToString()}" }
            })
    }

    override fun onDestroy() {
        if (::bottomSheetBehavior.isInitialized && ::bottomSheetCallback.isInitialized) {
            bottomSheetBehavior.removeBottomSheetCallback(bottomSheetCallback)
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