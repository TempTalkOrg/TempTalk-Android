package com.difft.android.chat.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import autodispose2.androidx.lifecycle.autoDispose
import com.difft.android.ChatSettingViewModelFactory
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
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
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatActivitySettingBinding
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.setting.ChatArchiveSettingsActivity
import com.difft.android.chat.setting.ChatSettingUtils
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.chat.setting.archive.MessageArchiveUtil
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.chat.setting.viewmodel.ChatSettingViewModel
import difft.android.messageserialization.For
import com.difft.android.messageserialization.db.store.DBMessageStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.responses.MuteStatus
import com.hi.dhl.binding.viewbind
import com.kongzue.dialogx.dialogs.BottomDialog
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.PopTip
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.interfaces.OnBindView
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.withCreationCallback
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import java.util.Optional
import javax.inject.Inject

@AndroidEntryPoint
class SingleChatSettingActivity : BaseActivity() {

    @Inject
    lateinit var messageArchiveManager: MessageArchiveManager

    @Inject
    lateinit var dbMessageStore: DBMessageStore

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    private val chatSettingViewModel: ChatSettingViewModel by viewModels(extrasProducer = {
        defaultViewModelCreationExtras.withCreationCallback<ChatSettingViewModelFactory> {
            it.create(For.Account(contactId))
        }
    })

    private val contactId: String by lazy {
        intent.getStringExtra("id") ?: ""
    }

    private var mContact: ContactorModel? = null

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

        mBinding.ibBack.setOnClickListener { finish() }

        if (TextUtils.isEmpty(contactId)) return

        // chatSettingViewModel.setCurrentTarget(For.Account(contactId)) - 不再需要，因为通过构造方法传递

        if (contactId.isBotId()) {
            mBinding.relMember.visibility = View.GONE
        } else {
            mBinding.relMember.visibility = View.VISIBLE
            mBinding.relMember.setOnClickListener {
                Single.fromCallable {
                    Optional.ofNullable(wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)))
                }
                    .compose(RxUtil.getSingleSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({ contact ->
                        if (contact.isPresent) {
                            CreateGroupActivity.startActivity(this, arrayListOf(contact.get().id))
                        } else {
                            TipDialog.show(getString(R.string.contact_not_in_list))
                        }
                    }, { error ->
                        error.printStackTrace()
                    })
            }
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

        ContactorUtil.getContactWithID(this, contactId)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({ contact ->
                if (contact.isPresent) {
                    val contactor = contact.get()
                    mContact = contactor
                    mBinding.avatar.setAvatar(contactor)
                    mBinding.tvName.text = contactor.getDisplayNameForUI()
                    mBinding.title.text = contactor.getDisplayNameForUI()

                    mBinding.vInfo.setOnClickListener {
                        ContactDetailActivity.startActivity(this, contactId)
                    }
                }
            }, { error ->
                error.printStackTrace()
            })

        refreshConversationSetting()

        chatSettingViewModel.conversationSet
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.blockStatus == 1) {
                    mBinding.tvUnblock.visibility = View.VISIBLE
                    mBinding.tvBlock.visibility = View.GONE
                } else {
                    mBinding.tvUnblock.visibility = View.GONE
                    mBinding.tvBlock.visibility = View.VISIBLE
                }
            }, {
                it.printStackTrace()
            })

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
        } else if (contactId.isBotId()) {
            mBinding.clMute.visibility = View.VISIBLE
            mBinding.relRemove.visibility = View.GONE
        } else {
            mBinding.clMute.visibility = View.VISIBLE
            if (wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)) != null) {
                mBinding.relRemove.visibility = View.VISIBLE
                mBinding.tvRemove.setOnClickListener {
                    showRemoveDialog()
                }
            }
        }
        // 20230803@w --------------------------------------------------------->
        // Purpose: Setup mute switch for conversation
        chatSettingViewModel.conversationSet.observeOn(AndroidSchedulers.mainThread())
            .autoDispose(this)
            .subscribe {
                L.i { "BH_Lin: settings of conversation=$it" }
                mBinding.switch2mute1on1.isChecked = it.isMuted == true
            }
        mBinding.switch2mute1on1.setOnClickListener {
            var muteStatus = MuteStatus.UNMUTED.value
            if (mBinding.switch2mute1on1.isChecked) {
                muteStatus = MuteStatus.MUTED.value
            }
            chatSettingViewModel.setConversationConfigs(
                activity = this,
                conversation = contactId,
                muteStatus = muteStatus
            )
        }
        // 20230803@w ---------------------------------------------------------<

        ChatSettingUtils.conversationSettingUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it == contactId) {
                    refreshConversationSetting()
                }
            }, {
                it.printStackTrace()
            })

        mBinding.switchStick.isChecked = isPined()
        mBinding.switchStick.setOnClickListener {
            if (isPined()) {
                pinChattingRoom(false)
            } else {
                pinChattingRoom(true)
            }
        }

        messageArchiveManager.getMessageArchiveTime(For.Account(contactId))
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                mBinding.disappearingTimeText.text = it.toArchiveTimeDisplayText()
            }, { it.printStackTrace() })

        MessageArchiveUtil.archiveTimeUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.first == contactId) {
                    mBinding.disappearingTimeText.text = it.second.toArchiveTimeDisplayText()
                }
            }, { it.printStackTrace() })
    }

    private fun refreshConversationSetting() {
        chatSettingViewModel.getConversationConfigs(this, listOf(contactId))
    }

    override fun onResume() {
        super.onResume()
        handleCommonGroupsDisplay()
    }

    /**
     * Handle the display and query of common groups between current user and contact
     */
    private fun handleCommonGroupsDisplay() {
        if (contactId == globalServices.myId) {
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
                    e.printStackTrace()
                    // Fallback: hide the section on error
                    mBinding.relGroupInCommon.visibility = View.GONE
                }
            }
        }
    }

    val token: String by lazy {
        SecureSharedPrefsUtil.getToken()
    }

    private fun showRemoveDialog() {
        MessageDialog.show(getString(R.string.contact_remove_dialog_title), getString(R.string.contact_remove_dialog_tips), getString(R.string.contact_remove_dialog_yes), getString(R.string.contact_remove_dialog_cancel))
            .setOkButton { _, _ ->
                ContactorUtil.fetchRemoveFriend(this@SingleChatSettingActivity, token, contactId)
                    .compose(RxUtil.getSingleSchedulerComposer())
                    .to(RxUtil.autoDispose(this))
                    .subscribe({
                        if (it.status == 0) {
                            wcdb.contactor.deleteObjects(DBContactorModel.id.eq(contactId))
                            dbMessageStore.removeRoomAndMessages(contactId)
                            PopTip.show(getString(R.string.contact_removed))
                            ContactorUtil.emitContactsUpdate(listOf(contactId))
                            mBinding.relRemove.visibility = View.GONE
                            startActivity(Intent(this@SingleChatSettingActivity, activityProvider.getActivityClass(ActivityType.INDEX)))
                        } else {
                            PopTip.show(it.reason)
                        }
                    }) {
                        PopTip.show(it.message)
                        it.printStackTrace()
                    }
                false
            }
    }

    private fun showBlockDialog() {
        BottomDialog.build()
            .setBackgroundColor(ContextCompat.getColor(this, com.difft.android.base.R.color.bg3))
            .setCustomView(object : OnBindView<BottomDialog?>(R.layout.layout_block_dialog) {
                override fun onBind(dialog: BottomDialog?, v: View) {
                    val tvBlockTips = v.findViewById<AppCompatTextView>(R.id.tv_block_tips)
                    val tvBlock = v.findViewById<AppCompatTextView>(R.id.tv_block)
//                    val tvBlockAndReport = v.findViewById<AppCompatTextView>(R.id.tv_block_and_report)
                    val tvCancel = v.findViewById<AppCompatTextView>(R.id.tv_cancel)

                    tvBlockTips.text = getString(R.string.contact_block_users_tips, PackageUtil.getAppName())

                    tvBlock.setOnClickListener {
                        chatSettingViewModel.setConversationConfigs(this@SingleChatSettingActivity, contactId, null, null, 1, null, false, getString(R.string.contact_blocked))
                        dialog?.dismiss()
                    }

//                    tvBlockAndReport.setOnClickListener {
//                        chatSettingViewModel.setConversationConfigs(this@SingleChatSettingActivity, contactId, null, null, 1, null, false, getString(R.string.contact_blocked))
////                        chatSettingViewModel.reportContact(this@SingleChatSettingActivity, contactId)
//                        dialog?.dismiss()
//                    }

                    tvCancel.setOnClickListener {
                        dialog?.dismiss()
                    }
                }
            })
            .show()
    }

    private fun pinChattingRoom(isPinned: Boolean) {
        dbRoomStore.updatePinnedTime(
            For.Account(contactId),
            if (isPinned) System.currentTimeMillis() else null
        )
            .compose(RxUtil.getCompletableTransformer())
            .autoDispose(this, Lifecycle.Event.ON_DESTROY)
            .subscribe({}, { it.printStackTrace() })
    }

    private fun isPined(): Boolean {
        return dbRoomStore.getPinnedTime(For.Account(contactId)).blockingGet().isPresent
    }
}