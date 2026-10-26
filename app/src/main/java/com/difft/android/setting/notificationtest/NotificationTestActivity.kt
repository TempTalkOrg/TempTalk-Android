package com.difft.android.setting.notificationtest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationFixJump
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import com.difft.android.base.widget.ToastUtil
import com.difft.android.setting.BackgroundConnectionHelpLink
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract
import com.difft.android.setting.notificationtest.mvi.NotificationTestViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.R as ChatR

/** The notification test page (#1350), opened from the notification settings page. */
@AndroidEntryPoint
class NotificationTestActivity : BaseActivity() {

    private val viewModel: NotificationTestViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TitleBar(
                        titleText = getString(ChatR.string.notification_test_entry),
                        onBackClick = { finish() },
                    )
                    NotificationTestContent(state = state, onIntent = viewModel::dispatch)
                }
            }
        }
        setContentView(composeView)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effect.collect(::handleEffect)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.dispatch(NotificationTestContract.Intent.Resume)
    }

    private fun handleEffect(effect: NotificationTestContract.Effect) {
        when (effect) {
            is NotificationTestContract.Effect.OpenFix -> openFix(effect.fix)
            NotificationTestContract.Effect.OpenGuide ->
                openExternalBrowser(BackgroundConnectionHelpLink.build(BackgroundConnectionHelpLink.Source.NOTIFICATION_SETTINGS))
        }
    }

    private fun openFix(fix: NotificationFix) {
        lifecycleScope.launch {
            val groupBlocked = fix == NotificationFix.MESSAGE_CATEGORY &&
                withContext(Dispatchers.IO) { NotificationFixJump.isMessageGroupBlocked(applicationContext) }
            if (!NotificationFixJump.open(this@NotificationTestActivity, fix, groupBlocked)) {
                L.w { "[NotificationTest] fix jump failed fix=$fix" }
                ToastUtil.show(ChatR.string.notification_settings_open_fail)
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, NotificationTestActivity::class.java))
        }
    }
}
