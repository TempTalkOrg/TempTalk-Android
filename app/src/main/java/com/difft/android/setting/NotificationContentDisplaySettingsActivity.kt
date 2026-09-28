package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class NotificationContentDisplaySettingsActivity : BaseActivity() {

    @Inject
    lateinit var userManager: UserManager

    companion object {
        fun start(activity: Activity) {
            val intent = Intent(activity, NotificationContentDisplaySettingsActivity::class.java)
            activity.startActivity(intent)
        }
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
            mutableIntStateOf(userManager.getUserData()?.notificationContentDisplayType ?: 0)
        }

        Column(
            Modifier.fillMaxSize().systemBarsPadding()
        ) {
            TitleBar(
                titleText = getString(com.difft.android.chat.R.string.notification_content),
                onBackClick = { finish() }
            )

            NotificationContentDisplaySettingsContent(
                selected = currentType.intValue,
                onSelect = { newType ->
                    userManager.update {
                        this.notificationContentDisplayType = newType
                    }
                    // Keeps the checkmark on the tapped row for the frame drawn while closing.
                    currentType.intValue = newType
                    finish()
                },
            )
        }
    }
}
