package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.BaseActivity
import com.difft.android.base.user.UserManager
import org.difft.app.database.wcdb
import com.difft.android.databinding.ActivityTestBinding
import com.difft.android.test.MessageTestUtil
import com.hi.dhl.binding.viewbind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class TestActivity : BaseActivity() {

    @Inject
    lateinit var messageTestUtil: MessageTestUtil

    @Inject
    lateinit var userManager: UserManager

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, TestActivity::class.java)
            activity.startActivity(intent)
        }
    }

    private val mBinding: ActivityTestBinding by viewbind()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mBinding.ibBack.setOnClickListener { finish() }


        mBinding.clCreateGroups.setOnClickListener {
            val idsString = mBinding.etMember.text.toString().trim()
            messageTestUtil.createTestGroups(this@TestActivity, if (idsString.isEmpty()) null else idsString.split(","))
        }

        mBinding.clDisbandGroups.setOnClickListener {
            messageTestUtil.disbandOrLeaveTestGroups(this@TestActivity)
        }

        mBinding.clSendMessage.setOnClickListener {
            messageTestUtil.sendTestMessageToAllTestGroups(this@TestActivity)
        }

        mBinding.clCorruptDatabase.setOnClickListener {
            wcdb.testCorruptDatabase()
        }

        mBinding.clBackupDatabase.setOnClickListener {
            wcdb.testBackupManually()
        }

        mBinding.clSendRecoveryEvent.setOnClickListener {
            wcdb.testRecoveryEvent()
        }

        mBinding.clDialogTest.setOnClickListener {
            DialogTestActivity.startActivity(this@TestActivity)
        }
    }
}