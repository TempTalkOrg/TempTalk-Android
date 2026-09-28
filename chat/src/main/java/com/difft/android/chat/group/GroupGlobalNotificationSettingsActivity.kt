package com.difft.android.chat.group

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.requests.PrivateConfigsRequestBody
import com.difft.android.network.requests.ProfileRequestBody
import com.difft.android.base.widget.ComposeDialogManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.difft.android.base.widget.ToastUtil

@AndroidEntryPoint
class GroupGlobalNotificationSettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    @ChativeHttpClientModule.Chat
    lateinit var httpClient: ChativeHttpClient

    companion object {
        fun start(activity: Activity) {
            val intent = Intent(activity, GroupGlobalNotificationSettingsActivity::class.java)
            activity.startActivityForResult(intent, REQUEST_CODE)
        }

        const val REQUEST_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                MainContent()
            }
        }
        setContentView(composeView)
    }

    @Composable
    private fun MainContent() {
        val currentType = remember {
            mutableIntStateOf(userManager.getUserData()?.globalNotification ?: 0)
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.notification),
                onBackClick = { finish() }
            )

            OptionList(currentType.intValue) { newType ->
                lifecycleScope.launch {
                    try {
                        val profileRequestBody = ProfileRequestBody(
                            privateConfigs = PrivateConfigsRequestBody(globalNotification = newType)
                        )

                        ComposeDialogManager.showWait(this@GroupGlobalNotificationSettingsActivity, "")
                        val response = withContext(Dispatchers.IO) {
                            httpClient.httpService.fetchSetProfile(
                                profileRequestBody = profileRequestBody
                            )
                        }

                        ComposeDialogManager.dismissWait()
                        if (response.isSuccess()) {
                            userManager.update {
                                this.globalNotification = newType
                            }
                            currentType.intValue = newType

                            setResult(Activity.RESULT_OK)
                        } else {
                            ToastUtil.show(response.reason ?: getString(com.difft.android.chat.R.string.operation_failed))
                        }
                    } catch (e: Exception) {
                        L.e { "[GlobalNotificationSettingsActivity] set global notification failed: ${e.stackTraceToString()}" }
                        ComposeDialogManager.dismissWait()
                        ToastUtil.show(getString(com.difft.android.chat.R.string.operation_failed))
                    }
                }
            }
        }
    }

    @Composable
    private fun OptionList(selected: Int, onNewOptionSelected: (Int) -> Unit) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = DifftSettingsDefaults.PageInset),
        ) {
            Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
            DifftSettingsGroup {
                GlobalNotificationOptionRows(selected, onNewOptionSelected)
            }
            Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
        }
    }
}
