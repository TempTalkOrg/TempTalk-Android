package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.RxUtil
import com.difft.android.base.utils.SecureSharedPrefsUtil
import org.difft.app.database.convertToContactorModel
import com.difft.android.messageserialization.db.store.formatBase58Id
import org.difft.app.database.getCommonGroupsCount
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.base.utils.globalServices
import org.difft.app.database.wcdb
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.LChatToCallController
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.contacts.contactsremark.ContactSetRemarkActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.contacts.data.isBotId
import com.difft.android.chat.databinding.ChatActivityContactDetailBinding
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.GroupInCommonActivity
import com.difft.android.chat.ui.SelectChatsUtils
import com.difft.android.chat.ui.SingleChatSettingActivity
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.NetworkException
import com.difft.android.network.di.ChativeHttpClientModule
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.rxjava3.core.Single
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.DBContactorModel
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.thoughtcrime.securesms.util.Util
import java.util.Optional
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil
const val BUNDLE_KEY_SOURCE_TYPE = "BUNDLE_KEY_CONTACT_SOURCE_TYPE"
const val BUNDLE_KEY_SOURCE = "BUNDLE_KEY_CONTACT_SOURCE"

@AndroidEntryPoint
class ContactDetailActivity : BaseActivity() {

    companion object {
        private const val BUNDLE_KEY_CONTACT_ID = "BUNDLE_KEY_CONTACT_ID"
        private const val BUNDLE_KEY_CONTACT_NAME = "BUNDLE_KEY_CONTACT_NAME"
        private const val BUNDLE_KEY_CONTACT_AVATAR = "BUNDLE_KEY_CONTACT_AVATAR"
        private const val BUNDLE_KEY_CONTACT_JOINED_AT = "BUNDLE_KEY_CONTACT_JOINED_AT"

        fun startActivity(
            context: Context,
            contactID: String?,
            contactName: String? = null,
            sourceType: String? = null,
            source: String? = null,
            avatar: String? = null,
            joinedAt: String? = null
        ) {
            val intent = Intent(context, ContactDetailActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.contactID = contactID
            intent.contactName = contactName
            intent.sourceType = sourceType
            intent.source = source
            intent.avatar = avatar
            intent.joinedAt = joinedAt
            context.startActivity(intent)
        }

        private var Intent.contactID: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_ID)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_ID, value)
            }
        private var Intent.contactName: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_NAME)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_NAME, value)
            }
        private var Intent.avatar: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_AVATAR)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_AVATAR, value)
            }
        private var Intent.joinedAt: String?
            get() = getStringExtra(BUNDLE_KEY_CONTACT_JOINED_AT)
            set(value) {
                putExtra(BUNDLE_KEY_CONTACT_JOINED_AT, value)
            }
        private var Intent.sourceType: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE_TYPE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE_TYPE, value)
            }
        private var Intent.source: String?
            get() = getStringExtra(BUNDLE_KEY_SOURCE)
            set(value) {
                putExtra(BUNDLE_KEY_SOURCE, value)
            }
    }

    private val mBinding: ChatActivityContactDetailBinding by viewbind()

    //    private val mViewModel: ContactDetailViewModel by viewModels()
    private var mContactor: ContactorModel? = null

    private val contactId: String by lazy {
        intent.contactID ?: ""
    }

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var chatHttpClient: ChativeHttpClient

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var callManager: LChatToCallController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        if (TextUtils.isEmpty(contactId)) return

        initView()
        initData()

        observeContactsUpdate()
    }

    private fun initData() {
        lifecycleScope.launch {
            try {
                val contact = withContext(Dispatchers.IO) {
                    Optional.ofNullable(wcdb.contactor.getFirstObject(DBContactorModel.id.eq(contactId)))
                }
                
                val finalContact = if (contact.isPresent) {
                    isFriend = true
                    contact
                } else {
                    isFriend = false
                    withContext(Dispatchers.IO) {
                        Optional.ofNullable(wcdb.groupMemberContactor.getFirstObject(DBGroupMemberContactorModel.id.eq(contactId))?.convertToContactorModel())
                    }
                }
                
                if (finalContact.isPresent) {
                    initContactView(finalContact.get())
                }
                getContactorInfoFromServer()
            } catch (e: Exception) {
                L.e { "[ContactDetailActivity] Error in initData: ${e.stackTraceToString()}" }
                e.printStackTrace()
            }
        }
    }

    private fun getContactorInfoFromServer() {
        ContactorUtil.fetchContactors(listOf(contactId), this)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                it.firstOrNull()?.let { contact ->
                    initContactView(contact)
                }
            }) { it.printStackTrace() }
    }

    private fun observeContactsUpdate() =
        ContactorUtil.contactsUpdate
            .compose(RxUtil.getSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                if (it.contains(contactId)) {
                    initData()
                }
            }, { it.printStackTrace() })

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
            val commonGroupText = "${getString(R.string.chat_group_in_common)}:"
            mBinding.tvGroupInCommon.text = commonGroupText

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
                            GroupInCommonActivity.startActivity(this@ContactDetailActivity, contactId)
                        }
                    } else {
                        mBinding.arrow1.visibility = View.INVISIBLE
                        mBinding.relGroupInCommon.setOnClickListener(null)
                    }
                } catch (e: Exception) {
                    L.e { "[ContactDetailActivity] Error querying common groups: ${e.stackTraceToString()}" }
                    e.printStackTrace()
                    // Fallback: hide the section on error
                    mBinding.relGroupInCommon.visibility = View.GONE
                }
            }
        }
    }


    private fun initView() {
        mBinding.ibMore.setOnClickListener {
            SingleChatSettingActivity.startActivity(this@ContactDetailActivity, contactId)
        }

        mBinding.ibShare.setOnClickListener {
            shareContact()
        }

        mBinding.ivNameEdit.setOnClickListener {
            ContactSetRemarkActivity.startActivity(this, contactId)
        }

        mBinding.cvCall.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val chatRoomName = withContext(Dispatchers.IO) {
                        LCallManager.getDisplayName(contactId) ?: ""
                    }
                    if (LCallActivity.isInCalling()) {
                        if (LCallActivity.getConversationId() == contactId) {
                            L.i { "[call] ContactDetailActivity bringing back the current call." }
                            LCallManager.bringInCallScreenBack(this@ContactDetailActivity)
                        } else {
                            ToastUtil.show(R.string.call_is_calling_tip)
                        }
                    } else {
                        //判断当前是否有livekit会议，有则join会议
                        val callData = LCallManager.getCallDataByConversationId(contactId)
                        if (callData != null) {
                            L.i { "[call] ContactDetailActivity join call, roomId:${callData.roomId}." }
                            LCallManager.joinCall(this@ContactDetailActivity, callData) { status ->
                                if(!status) {
                                    L.e { "[Call] ContactDetailActivity join call failed." }
                                    ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                                }
                            }
                            return@launch
                        }
                        //否则发起livekit call通话
                        L.i { "[call] ContactDetailActivity start call." }
                        callManager.startCall(this@ContactDetailActivity, For.Account(contactId), chatRoomName) { status, message ->
                            if(!status) {
                                L.e { "[Call] ContactDetailActivity start call failed." }
                                message?.let { ToastUtil.show(it) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    L.e { "[call] ContactDetailActivity start call error:${e.message}" }
                    ToastUtil.show("start call error")
                }
            }
        }

        mBinding.cvShare.setOnClickListener {
            shareContact()
        }

        mBinding.cvMessage.setOnClickListener {
            ChatActivity.startActivity(this, contactId, sourceType = intent.sourceType, source = intent.source)
        }

        mBinding.cvAdd.setOnClickListener {
            requestAddFriend()
        }

        initContactView(ContactorModel().apply {
            this.id = contactId
            this.name = intent.contactName
        })
    }

    @Inject
    lateinit var selectChatsUtils: SelectChatsUtils

    private fun shareContact() {
        selectChatsUtils.showChatSelectAndSendDialog(
            this,
            getString(R.string.chat_contact_card),
            forwardContexts = listOf(ForwardContext(emptyList(), false, contactId, mContactor?.getDisplayNameWithoutRemarkForUI()))
        )
    }

    private fun initContactView(contactor: ContactorModel?) {
        if (contactor == null || contactor == mContactor) {
            L.d { "[ContactDetailActivity] Contact data is null or same, skip refresh" }
            return
        }
        mContactor = contactor

        //通过邀请码查找联系人，部分信息是直接带过来的
        if (!intent.avatar.isNullOrEmpty()) {
            contactor.avatar = intent.avatar
        }
        if (!intent.joinedAt.isNullOrEmpty()) {
            contactor.joinedAt = intent.joinedAt
        }

        if (!TextUtils.isEmpty(contactor.remark)) {
            mBinding.tvRemark.text = contactor.remark
            mBinding.tvName.text = contactor.name ?: contactor.publicName ?: contactor.id
            mBinding.tvName.visibility = View.VISIBLE
        } else {
            mBinding.tvRemark.text = contactor.getDisplayNameForUI()
            mBinding.tvName.visibility = View.GONE
        }

        mBinding.tvRemark.setOnLongClickListener {
            Util.copyToClipboard(this, mBinding.tvRemark.text)
            true
        }

        mBinding.tvName.setOnLongClickListener {
            Util.copyToClipboard(this, mBinding.tvName.text)
            true
        }

        if (TextUtils.isEmpty(contactor.id)) {
            mBinding.clUid.root.visibility = View.GONE
        } else {
            mBinding.clUid.root.visibility = View.VISIBLE
            mBinding.clUid.tvKey.text = getString(R.string.contact_uid)
            mBinding.clUid.tvValue.text = contactor.id.formatBase58Id(false)

            mBinding.clUid.tvValue.setOnLongClickListener {
                Util.copyToClipboard(this, mBinding.clUid.tvValue.text)
                true
            }
        }

        if (TextUtils.isEmpty(contactor.joinedAt)) {
            mBinding.clJoinedAt.root.visibility = View.GONE
        } else {
            mBinding.clJoinedAt.root.visibility = View.VISIBLE
            mBinding.clJoinedAt.tvKey.text = getString(R.string.contact_join_at)
            mBinding.clJoinedAt.tvValue.text = contactor.joinedAt
        }

        if (contactId == globalServices.myId) {
            mBinding.clMet.root.visibility = View.GONE
        } else {
            if (!TextUtils.isEmpty(contactor.sourceDescribe)) {
                mBinding.clMet.root.visibility = View.VISIBLE
                mBinding.clMet.tvKey.text = getString(R.string.contact_how_you_met)
                mBinding.clMet.tvValue.text = contactor.sourceDescribe
            } else {
                mBinding.clMet.root.visibility = View.GONE
            }
        }

        mBinding.ibMore.visibility = View.GONE
        mBinding.ibShare.visibility = View.GONE
        mBinding.ivNameEdit.visibility = View.GONE
        mBinding.cvCall.visibility = View.GONE
        mBinding.cvShare.visibility = View.GONE
        mBinding.cvAdd.visibility = View.GONE

        if (globalServices.myId == contactId) {//本人
            mBinding.cvShare.visibility = View.VISIBLE
        } else if (isFriend) {//好友

            mBinding.cvCall.visibility = View.VISIBLE
            mBinding.cvShare.visibility = View.VISIBLE

            mBinding.ivNameEdit.visibility = View.VISIBLE
            mBinding.ibMore.visibility = View.VISIBLE
        } else {//非好友
            mBinding.cvAdd.visibility = View.VISIBLE
            mBinding.ivNameEdit.visibility = View.VISIBLE
        }

        if (contactor.id.isBotId()) {
            mBinding.cvCall.visibility = View.GONE
        }

        setAvatar(contactor)
    }

    private fun setAvatar(it: ContactorModel) {
        mBinding.tvLetter.setBackgroundColor(AvatarUtil.getBgColorResId(it.id))
        mBinding.tvLetter.text = it.getDisplayNameForUI().getFirstLetter()

        val avatarData = it.avatar?.getContactAvatarData()
        val avatarUrl = avatarData?.getContactAvatarUrl()
        val avatarEncKey = avatarData?.encKey

        if (!avatarUrl.isNullOrEmpty() && !avatarEncKey.isNullOrEmpty()) {
            setAvatar(avatarUrl, avatarEncKey)
        }
    }

    private fun setAvatar(url: String, key: String) {
        lifecycleScope.launch {
            AvatarUtil.loadAvatar(this@ContactDetailActivity, url, key, mBinding.ivHeadImage, mBinding.tvLetter, AvatarUtil.AvatarCacheSize.BIG)
        }
    }

    private var isFriend = true

    private fun requestAddFriend() {
        ComposeDialogManager.showWait(this@ContactDetailActivity, "")
        ContactorUtil.fetchAddFriendRequest(this, SecureSharedPrefsUtil.getToken(), contactId, intent.sourceType, intent.source)
            .concatMap {
                if (it.status == 0) {
                    ContactorUtil.sendFriendRequestMessage(this, For.Account(contactId))
                    Single.just(it)
                } else {
                    Single.error(NetworkException(message = it.reason ?: ""))
                }
            }
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    ToastUtil.show(R.string.contact_request_sent)
                } else {
                    it.reason?.let { message -> ToastUtil.show(message) }
                }
            }) {
                ComposeDialogManager.dismissWait()
                it.message?.let { message -> ToastUtil.show(message) }
            }
    }
}