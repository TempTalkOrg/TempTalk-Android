package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.test.MessageTestUtil
import dagger.hilt.android.AndroidEntryPoint
import org.difft.app.database.wcdb
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme(useSecondaryBackground = true) {
                TestScreen(
                    onNavigateBack = { finish() },
                    onCreateGroups = { memberIds, count ->
                        messageTestUtil.createTestGroups(
                            this@TestActivity,
                            lifecycleScope,
                            if (memberIds.isEmpty()) null else memberIds.split(","),
                            count
                        )
                    },
                    onDisbandGroups = {
                        messageTestUtil.disbandOrLeaveTestGroups(this@TestActivity, lifecycleScope)
                    },
                    onSendMessageToAllGroups = {
                        messageTestUtil.sendTestMessageToAllTestGroups(this@TestActivity, lifecycleScope)
                    },
                    onSendMessageToSingleGroup = { count ->
                        messageTestUtil.sendTestMessageToSingleGroup(this@TestActivity, lifecycleScope, count)
                    },
                    onCorruptDatabase = {
                        wcdb.testCorruptDatabase()
                    },
                    onBackupDatabase = {
                        wcdb.testBackupManually()
                    },
                    onSendRecoveryEvent = {
                        wcdb.testRecoveryEvent()
                    },
                    onDialogTest = {
                        DialogTestActivity.startActivity(this@TestActivity)
                    }
                )
            }
        }
        setContentView(composeView)
    }
}