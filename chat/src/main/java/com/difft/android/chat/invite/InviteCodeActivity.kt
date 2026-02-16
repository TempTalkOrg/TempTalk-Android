package com.difft.android.chat.invite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.databinding.ActivityEnterCodeBinding
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.chat.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class InviteCodeActivity : BaseActivity() {

    @Inject
    lateinit var inviteRepo: InviteRepo

    @Inject
    lateinit var inviteUtils: InviteUtils

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, InviteCodeActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityEnterCodeBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }

        mBinding.ibScanCode.setOnClickListener {
            ScanActivity.startActivity(this@InviteCodeActivity)
        }

        mBinding.addText.setOnClickListener {
            mBinding.tvError.text = ""
            mBinding.customUid.text?.let { text ->
                val textStr = text.toString()
                // 判断text是否为4位数字，如果是则调用queryByInviteCode，否则调用 queryByCustomUid
                if (textStr.length == 4 && textStr.all { it.isDigit() }) {
                    queryByInviteCode(textStr)
                } else {
                    queryByCustomUid(textStr)
                }
            }
        }
    }

    private fun queryByInviteCode(inviteCode: String) {
        ComposeDialogManager.showWait(this@InviteCodeActivity,"")
        inviteRepo.queryByInviteCode(inviteCode)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    it.data?.uid?.let { uid ->
                        ContactDetailActivity.startActivity(this, uid, sourceType = FriendSourceType.RANDOM_CODE, avatar = it.data?.avatar, joinedAt = it.data?.joinedAt)
                        finish()
                    }
                } else {
                    mBinding.tvError.text = ResUtils.getString(R.string.contact_add_contacts_code_not_correct)
                    mBinding.tvError.visibility = View.VISIBLE
                }
            }, {
                ComposeDialogManager.dismissWait()
                L.w { "[InviteCodeActivity] queryByInviteCode error: ${it.stackTraceToString()}" }
                mBinding.tvError.text = it.message
                mBinding.tvError.visibility = View.VISIBLE
            })
    }

    private fun queryByCustomUid(customUid: String) {
        ComposeDialogManager.showWait(this@InviteCodeActivity,"")
        inviteRepo.queryByCustomUid(1, customUid)
            .compose(RxUtil.getSingleSchedulerComposer())
            .to(RxUtil.autoDispose(this))
            .subscribe({
                ComposeDialogManager.dismissWait()
                if (it.status == 0) {
                    val uid = it.data?.uid
                    val customUid = it.data?.customUid
                    if (!uid.isNullOrEmpty() || !customUid.isNullOrEmpty()) {
                        ContactDetailActivity.startActivity(this, uid, customUid, sourceType = FriendSourceType.RANDOM_CODE, avatar = it.data?.avatar, joinedAt = it.data?.joinedAt)
                        finish()
                    }
                } else {
                    L.e {"[Invite] query by customUid status:${it.status}, reason:${it.reason}"}
                    mBinding.tvError.text = ResUtils.getString(R.string.contact_add_contacts_contact_name_not_correct)
                    mBinding.tvError.visibility = View.VISIBLE
                }
            }, {
                ComposeDialogManager.dismissWait()
                L.w { "[InviteCodeActivity] queryByCustomUid error: ${it.stackTraceToString()}" }
                mBinding.tvError.text = it.message
                mBinding.tvError.visibility = View.VISIBLE
            })
    }
}