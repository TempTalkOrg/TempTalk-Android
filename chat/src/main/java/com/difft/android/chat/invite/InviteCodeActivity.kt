package com.difft.android.chat.invite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.utils.RxUtil
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.databinding.ActivityEnterCodeBinding
import com.hi.dhl.binding.viewbind
import com.difft.android.base.widget.ComposeDialogManager
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

        mBinding.tvMyCode.setOnClickListener {
            inviteUtils.showInviteDialog(this)
        }

        mBinding.etCode.setOnTextChangeListener { text, isComplete ->
            mBinding.tvError.text = ""
            if (isComplete) {
                queryByInviteCode(text)
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
                    mBinding.tvError.text = it.reason
                }
            }, {
                ComposeDialogManager.dismissWait()
                it.printStackTrace()
                mBinding.tvError.text = it.message
            })
    }
}