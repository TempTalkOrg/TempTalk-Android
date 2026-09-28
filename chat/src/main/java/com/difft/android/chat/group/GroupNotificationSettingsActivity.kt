package com.difft.android.chat.group

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb
import javax.inject.Inject

@AndroidEntryPoint
class GroupNotificationSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var groupRepo: GroupRepo

    private var refreshTrigger = mutableIntStateOf(0)

    companion object {
        fun start(activity: Activity, groupId: String) {
            val intent = Intent(activity, GroupNotificationSettingsActivity::class.java)
            intent.putExtra(KEY_GROUP_ID, groupId)
            activity.startActivity(intent)
        }

        private const val KEY_GROUP_ID = "group_id"
    }

    private lateinit var groupId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = intent.getStringExtra(KEY_GROUP_ID) ?: ""
        if (groupId.isEmpty()) {
            finish()
            return
        }

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GroupGlobalNotificationSettingsActivity.REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            // Returning from the global page: re-read so the displayed global type is current.
            refreshTrigger.intValue++
        }
    }

    /**
     * @return true when the server accepted, so the caller flips the switch only then and it never
     * shows a value the server rejected.
     */
    private suspend fun updateGroupNotificationSettings(useGlobal: Boolean): Boolean {
        val currentUserId = globalServices.myId
        return try {
            val request = ChangeSelfSettingsInGroupReq(
                notification = null, // keep the current notification setting
                useGlobal = useGlobal
            )
            ComposeDialogManager.showWait(this@GroupNotificationSettingsActivity, "")
            val response = withContext(Dispatchers.IO) {
                groupRepo.changeSelfSettingsInGroup(groupId, currentUserId, request)
            }
            if (response.status == 0) {
                L.i { "[GroupNotificationSettings] update useGlobal=$useGlobal result=success" }
                true
            } else {
                ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                false
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            L.e { "[GroupNotificationSettings] update useGlobal=$useGlobal failed: ${e.stackTraceToString()}" }
            ToastUtil.show(getString(R.string.operation_failed))
            false
        } finally {
            // finally, so the rethrown cancellation cannot leave the dialog behind.
            ComposeDialogManager.dismissWait(this@GroupNotificationSettingsActivity)
        }
    }

    private fun updateGroupNotificationSettingsWithNotification(notification: Int, onSuccess: () -> Unit) {
        val currentUserId = globalServices.myId

        lifecycleScope.launch {
            try {
                val request = ChangeSelfSettingsInGroupReq(
                    notification = notification,
                    useGlobal = false // picking an explicit option always leaves the global setting
                )

                ComposeDialogManager.showWait(this@GroupNotificationSettingsActivity, "")
                val response = withContext(Dispatchers.IO) {
                    groupRepo.changeSelfSettingsInGroup(groupId, currentUserId, request)
                }
                ComposeDialogManager.dismissWait()

                if (response.status == 0) {
                    // The selection moves only once the server accepted it.
                    onSuccess()
                    L.i { "[GroupNotificationSettings] update notification=$notification result=success" }
                } else {
                    ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                }
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettings] update notification=$notification failed: ${e.stackTraceToString()}" }
                ComposeDialogManager.dismissWait()
                ToastUtil.show(getString(R.string.operation_failed))
            }
        }
    }

    @Composable
    private fun MainContent() {
        // Global default, shown next to the switch label; must be re-readable after a round trip
        // to the global settings page.
        var globalNotificationType by remember {
            mutableIntStateOf(userManager.getUserData()?.globalNotification ?: 0)
        }
        var currentType by remember { mutableIntStateOf(0) }
        var isDefaultEnabled by remember { mutableStateOf(true) }

        // Blocks a second tap while the request is in flight: alternating taps would otherwise
        // issue opposite values whose responses can land out of order.
        var switchRequestInFlight by remember { mutableStateOf(false) }

        LaunchedEffect(refreshTrigger.intValue) {
            globalNotificationType = userManager.getUserData()?.globalNotification ?: 0
            try {
                val groupMember = wcdb.groupMemberContactor.getFirstObject(
                    (DBGroupMemberContactorModel.gid.eq(groupId))
                        .and(DBGroupMemberContactorModel.id.eq(globalServices.myId))
                )
                isDefaultEnabled = groupMember?.useGlobal ?: false
                currentType = groupMember?.notification ?: 0
            } catch (e: Exception) {
                L.e { "[GroupNotificationSettings] read group member failed: ${e.stackTraceToString()}" }
                // Keep the defaults on failure.
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(R.string.notification),
                onBackClick = { finish() }
            )

            GroupNotificationSettingsContent(
                globalNotificationType = globalNotificationType,
                isDefaultEnabled = isDefaultEnabled,
                currentType = currentType,
                onDefaultSettingClick = {
                    GroupGlobalNotificationSettingsActivity.start(this@GroupNotificationSettingsActivity)
                },
                onSwitchChanged = { newValue ->
                    if (!switchRequestInFlight) {
                        lifecycleScope.launch {
                            switchRequestInFlight = true
                            try {
                                // The switch flips only once the server accepted.
                                if (updateGroupNotificationSettings(newValue)) {
                                    isDefaultEnabled = newValue
                                }
                            } finally {
                                switchRequestInFlight = false
                            }
                        }
                    }
                },
                onOptionSelected = { option ->
                    updateGroupNotificationSettingsWithNotification(option) {
                        currentType = option
                    }
                },
            )
        }
    }
}
