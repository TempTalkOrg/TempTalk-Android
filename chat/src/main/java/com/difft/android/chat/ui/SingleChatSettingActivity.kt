package com.difft.android.chat.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.common.compose.CollapsingTitleBar
import com.difft.android.chat.common.compose.ContactAvatar
import com.difft.android.chat.common.compose.IdentityHeader
import com.difft.android.chat.common.compose.IdentityHeaderMode
import com.difft.android.chat.common.compose.TitleBarAction
import com.difft.android.chat.common.compose.TitleCollapseTracker
import androidx.appcompat.widget.AppCompatTextView
import androidx.lifecycle.lifecycleScope
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.messageserialization.db.store.formatBase58Id
import org.difft.app.database.getCommonGroupsCount
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatActivitySettingBinding
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.setting.ChatArchiveSettingsActivity
import com.difft.android.chat.setting.SaveToPhotosSettingsActivity
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.requests.MuteStatus
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ComposeDialog
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.cache.OfficialAccountCache
import org.difft.app.database.getContactorFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import com.difft.android.chat.util.MessageNotificationUtil
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
@AndroidEntryPoint
class SingleChatSettingActivity : BaseActivity() {

    @Inject
    lateinit var dbMessageStore: DBMessageStore

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var userManager: com.difft.android.base.user.UserManager

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Account(contactId))
        }
    })

    private val contactId: String by lazy {
        intent.getStringExtra("id") ?: ""
    }

    private var mContact: ContactorModel? = null

    /** Null only while the local row is still loading; see [loadAndRenderContact]. */
    private var headerContact by mutableStateOf<ContactorModel?>(null)
    private val collapseTracker by lazy { TitleCollapseTracker(mBinding.composeTitleBar) }

    /**
     * Peer with no local row (fetch failed / deregistered): the header still needs an entry to the
     * card. Applied only after the DB has answered, so the id never flashes while loading.
     */
    private val fallbackContact by lazy { ContactorModel().apply { id = contactId } }

    companion object {
        fun startActivity(activity: Activity, contactId: String) {
            val intent = Intent(activity, SingleChatSettingActivity::class.java)
            intent.putExtra("id", contactId)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ChatActivitySettingBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (TextUtils.isEmpty(contactId)) {
            finish()
            return
        }

        setupHeaderCompose()

        mBinding.saveToPhotosContainer.setOnClickListener {
            SaveToPhotosSettingsActivity.start(this, For.Account(contactId))
        }

        mBinding.disappearingTimeContainer.setOnClickListener {
            ChatArchiveSettingsActivity.start(this, For.Account(contactId))
        }

        if (messageNotificationUtil.supportConversationNotification()) {
            mBinding.relNotificationSound.visibility = View.VISIBLE
            mBinding.relNotificationSound.setOnClickListener {
                messageNotificationUtil.createChannelForConversation(contactId, mContact?.getDisplayNameForUI() ?: contactId.formatBase58Id())
                messageNotificationUtil.openMessageNotificationChannelSettings(this, contactId)
            }
        } else {
            mBinding.relNotificationSound.visibility = View.GONE
        }

        mBinding.llSearchChatHistory.setOnClickListener {
            SearchMessageActivity.startActivity(this@SingleChatSettingActivity, contactId, false, null)
        }

        loadAndRenderContact()

        // Re-render title/avatar after contact edits (remark name/avatar, profile, etc.).
        ContactorUtil.contactsUpdate
            .filter { it.contains(contactId) }
            .onEach { loadAndRenderContact() }
            .launchIn(lifecycleScope)

        // Cold-start race: the official-account cache may populate after onCreate ran the one-shot
        // gating that hides the write actions (remove-friend / block). This is the only screen where
        // the window enables a WRITE against the official account, so re-apply the gating by
        // recreating on a membership flip. Fires at most once per entry (NORMAL->OFFICIAL only, in
        // the sub-second preload window); drop(1) skips the current value so a warm cache no-ops.
        OfficialAccountCache.state
            .map { contactId in it }
            .distinctUntilChanged()
            .drop(1)
            .onEach { recreate() }
            .launchIn(lifecycleScope)

        // Subscribe to conversationSet for all config-related UI updates
        chatSettingViewModel.conversationSet
            .filterNotNull()
            .onEach { conversationSet ->
                // Update block status (hidden for bots)
                if (contactId.isOfficialAccount()) {
                    mBinding.clBlock.visibility = View.GONE
                } else if (conversationSet.blockStatus == 1) {
                    mBinding.clBlock.visibility = View.VISIBLE
                    mBinding.tvUnblock.visibility = View.VISIBLE
                    mBinding.tvBlock.visibility = View.GONE
                } else {
                    mBinding.clBlock.visibility = View.VISIBLE
                    mBinding.tvUnblock.visibility = View.GONE
                    mBinding.tvBlock.visibility = View.VISIBLE
                }
                // Update mute status
                mBinding.switch2mute1on1.isChecked = conversationSet.isMuted
                // Update save to photos
                mBinding.saveToPhotosText.text = getSaveToPhotosDisplayText(conversationSet.saveToPhotos)
                // Update disappearing time
                mBinding.disappearingTimeText.text = conversationSet.messageExpiry.toArchiveTimeDisplayText()
            }
            .launchIn(lifecycleScope)

        mBinding.tvUnblock.setOnClickListener {
            chatSettingViewModel.setConversationConfigs(this, contactId, null, null, 0, null, false, getString(R.string.contact_unblocked))
        }
        mBinding.tvBlock.setOnClickListener {
            showBlockDialog()
        }

        if (contactId == globalServices.myId) {
            mBinding.clMute.visibility = View.GONE
            mBinding.relRemove.visibility = View.GONE
            mBinding.disappearingTimeContainer.visibility = View.GONE
        } else if (contactId.isOfficialAccount()) {
            mBinding.clMute.visibility = View.VISIBLE
            mBinding.relRemove.visibility = View.GONE
            mBinding.clBlock.visibility = View.GONE
        } else {
            mBinding.clMute.visibility = View.VISIBLE
            if (wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)) != null) {
                mBinding.relRemove.visibility = View.VISIBLE
                mBinding.tvRemove.setOnClickListener {
                    showRemoveDialog()
                }
            }
        }
        // Controlled: the conversationSet flow moves the switch once the server accepted, so a
        // failed request simply leaves it alone.
        mBinding.switch2mute1on1.setOnToggleRequestListener { view, requested ->
            ComposeDialogManager.showWait(this, "")
            view.guardWhile(
                chatSettingViewModel.setConversationConfigs(
                    activity = this,
                    conversation = contactId,
                    muteStatus = MuteStatus.of(requested).value,
                )
            ) {
                ComposeDialogManager.dismissWait(this)
            }
        }

        // 异步加载置顶状态
        lifecycleScope.launch(Dispatchers.IO) {
            val isPinned = isPined()
            withContext(Dispatchers.Main) {
                mBinding.switchStick.isChecked = isPinned
            }
        }
        // Controlled and DB-only: the switch moves once the write landed, no wait dialog needed.
        mBinding.switchStick.setOnToggleRequestListener { view, requested ->
            view.guardWhile(pinChattingRoom(requested))
        }
    }

    override fun onResume() {
        super.onResume()
        handleCommonGroupsDisplay()
    }

    // region identity header (title bar + centered header)

    private fun setupHeaderCompose() {
        mBinding.composeTitleBar.setContent {
            DifftTheme(applyWindowBackground = false) {
                CollapsingTitleBar(
                    title = headerContact?.getDisplayNameForUI().orEmpty(),
                    collapsed = collapseTracker.collapsed,
                    onBack = { finish() },
                    action = if (contactId.isOfficialAccount()) {
                        null
                    } else {
                        TitleBarAction.Icon(R.drawable.chat_ic_users_plus) { createGroupWithContact() }
                    }
                )
            }
        }
        mBinding.composeIdentityHeader.setContent {
            DifftTheme(applyWindowBackground = false) {
                val contact = headerContact ?: return@DifftTheme
                IdentityHeader(
                    name = contact.getDisplayNameForUI(),
                    mode = IdentityHeaderMode.Browse,
                    avatar = { avatarModifier ->
                        ContactAvatar(contactor = contact, size = DifftTheme.spacing.avatarLarge, modifier = avatarModifier)
                    },
                    onAvatarClick = { openContactCard() },
                    onHeaderClick = { openContactCard() },
                    onAvatarBottomChanged = collapseTracker::onAvatarBottomChanged,
                )
            }
        }
    }

    private fun openContactCard() {
        ContactDetailActivity.startActivity(this, contactId)
    }

    private fun createGroupWithContact() {
        lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId))
                }
                if (contact != null) {
                    CreateGroupActivity.startActivity(this@SingleChatSettingActivity, arrayListOf(contact.id))
                } else {
                    ToastUtil.showLong(getString(R.string.contact_not_in_list))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSettingActivity] createGroup error: ${e.stackTraceToString()}" }
            }
        }
    }

    // endregion

    private fun loadAndRenderContact() {
        lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    wcdb.getContactorFromAllTable(contactId)
                }
                mContact = contact
                headerContact = contact ?: fallbackContact
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSettingActivity] getContactWithID error: ${e.stackTraceToString()}" }
                headerContact = headerContact ?: fallbackContact
            }
        }
    }

    /**
     * Handle the display and query of common groups between current user and contact
     */
    // Numeric-only display (common-group count); no English text to translate.
    @SuppressLint("SetTextI18n")
    private fun handleCommonGroupsDisplay() {
        if (contactId == globalServices.myId || contactId.isOfficialAccount()) {
            mBinding.relGroupInCommon.visibility = View.GONE
        } else {
            mBinding.relGroupInCommon.visibility = View.VISIBLE

            // Query and display common groups
            lifecycleScope.launch {
                try {
                    val commonGroupsCount = withContext(Dispatchers.IO) {
                        wcdb.getCommonGroupsCount(contactId, globalServices.myId)
                    }

                    // Update UI on main thread
                    mBinding.tvCommonGroupCount.text = commonGroupsCount.toString()
                    if (commonGroupsCount > 0) {
                        mBinding.arrow1.visibility = View.VISIBLE
                        mBinding.relGroupInCommon.setOnClickListener {
                            GroupInCommonActivity.startActivity(this@SingleChatSettingActivity, contactId)
                        }
                    } else {
                        mBinding.arrow1.visibility = View.INVISIBLE
                        mBinding.relGroupInCommon.setOnClickListener(null)
                    }
                } catch (e: Exception) {
                    L.e { "[SingleChatSettingActivity] Error querying common groups: ${e.stackTraceToString()}" }
                    // Fallback: hide the section on error
                    mBinding.relGroupInCommon.visibility = View.GONE
                }
            }
        }
    }

    val token: String by lazy {
        (userManager.getUserData()?.microToken ?: "")
    }

    private fun showRemoveDialog() {
        ComposeDialogManager.showMessageDialog(
            context = this,
            title = getString(R.string.contact_remove_dialog_title),
            message = getString(R.string.contact_remove_dialog_tips),
            confirmText = getString(R.string.contact_remove_dialog_yes),
            cancelText = getString(R.string.contact_remove_dialog_cancel),
            onConfirm = {
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            ContactorUtil.fetchRemoveFriend(this@SingleChatSettingActivity, token, contactId)
                        }
                        if (result.status == 0) {
                            withContext(Dispatchers.IO) {
                                wcdb.contactor.deleteObjects(DBContactorModel.id.eq(contactId))
                                dbMessageStore.removeRoomAndMessages(contactId)
                            }
                            ToastUtil.show(getString(R.string.contact_removed))
                            ContactorUtil.emitContactsUpdate(listOf(contactId))
                            mBinding.relRemove.visibility = View.GONE
                            startActivity(Intent(this@SingleChatSettingActivity, activityProvider.getActivityClass(ActivityType.INDEX)))
                        } else {
                            result.reason?.let { message -> ToastUtil.show(message) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message?.let { message -> ToastUtil.show(message) }
                        L.w { "[SingleChatSettingActivity] removeFriend error: ${e.stackTraceToString()}" }
                    }
                }
            }
        )
    }

    private fun showBlockDialog() {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(
            activity = this,
            layoutId = R.layout.layout_block_dialog,
            onDismiss = { /* Dialog dismissed */ },
            onViewCreated = { v ->
                val tvBlockTips = v.findViewById<AppCompatTextView>(R.id.tv_block_tips)
                val tvBlock = v.findViewById<AppCompatTextView>(R.id.tv_block)
//                val tvBlockAndReport = v.findViewById<AppCompatTextView>(R.id.tv_block_and_report)
                val tvCancel = v.findViewById<AppCompatTextView>(R.id.tv_cancel)

                tvBlockTips.text = getString(R.string.contact_block_users_tips, PackageUtil.getAppName())

                tvBlock.setOnClickListener {
                    dialog?.dismiss()
                    chatSettingViewModel.setConversationConfigs(this@SingleChatSettingActivity, contactId, null, null, 1, null, false, getString(R.string.contact_blocked))
                }

//                tvBlockAndReport.setOnClickListener {
//                    dialog?.dismiss()
//                    chatSettingViewModel.setConversationConfigs(this@SingleChatSettingActivity, contactId, null, null, 1, null, false, getString(R.string.contact_blocked))
////                    chatSettingViewModel.reportContact(this@SingleChatSettingActivity, contactId)
//                }

                tvCancel.setOnClickListener {
                    dialog?.dismiss()
                }
            }
        )
    }

    /** @return the write's job, so the toggle can gate itself on it (see `DifftToggleView.guardWhile`). */
    private fun pinChattingRoom(isPinned: Boolean): Job {
        return lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    dbRoomStore.updatePinnedTime(
                        For.Account(contactId),
                        if (isPinned) System.currentTimeMillis() else null
                    )
                }
                mBinding.switchStick.isChecked = isPinned
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[SingleChatSettingActivity] pinChattingRoom error: ${e.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    private suspend fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Account(contactId)).isPresent
    }

    /**
     * Get the display text for save to photos setting
     * @param saveToPhotos null: follow global, 0: never, 1: always
     */
    private fun getSaveToPhotosDisplayText(saveToPhotos: Int?): String {
        return when (saveToPhotos) {
            1 -> getString(R.string.save_to_photos_always)
            0 -> getString(R.string.save_to_photos_never)
            else -> {
                // Default - show dynamic status based on global setting
                val globalEnabled = userManager.getUserData()?.saveToPhotos == true
                val statusText = if (globalEnabled) {
                    getString(R.string.save_to_photos_on)
                } else {
                    getString(R.string.save_to_photos_off)
                }
                getString(R.string.save_to_photos_default, statusText)
            }
        }
    }
}