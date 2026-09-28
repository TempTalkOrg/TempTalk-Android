package com.difft.android.setting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SetPasscodeTimeoutActivity : BaseActivity() {

    companion object {
        fun startActivity(activity: Activity) {
            val intent = Intent(activity, SetPasscodeTimeoutActivity::class.java)
            activity.startActivity(intent)
        }
    }

    @Inject
    lateinit var userManager: UserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme { MainContent() }
        }
        setContentView(composeView)
    }

    // BaseActivity skips its automatic inset padding for a Compose root, so this page pads itself.
    @Composable
    private fun MainContent() {
        val selected = remember { mutableStateOf(userManager.getUserData()?.passcodeTimeout) }

        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            TitleBar(
                titleText = getString(R.string.settings_screen_lock_timeout),
                onBackClick = { finish() },
            )
            PasscodeTimeoutContent(
                selected = selected.value,
                onSelect = { seconds ->
                    userManager.update { this.passcodeTimeout = seconds }
                    selected.value = seconds
                },
            )
        }
    }
}
