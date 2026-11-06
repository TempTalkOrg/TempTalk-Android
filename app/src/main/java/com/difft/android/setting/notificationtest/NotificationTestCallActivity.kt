package com.difft.android.setting.notificationtest

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationTestCallUpdate
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.validNotificationTestId
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R as ChatR
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the call test's full-screen intent (#1350). Being created is the objective proof that
 * the system let the app pop a screen over the lock screen, so creation records the success. It
 * shares nothing with the real call screens.
 */
@AndroidEntryPoint
class NotificationTestCallActivity : ComponentActivity() {

    @Inject
    lateinit var repository: NotificationTestRepository

    @Inject
    lateinit var poster: NotificationTestPoster

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runId = intent.getStringExtra(RUN_ID)?.takeIf(::validNotificationTestId)
        if (runId == null || intent.data?.toString() != "temptalk-internal://notification-test/call/$runId") {
            finish()
            return
        }
        allowOnLockScreen()
        // The window is lit over the lock screen at once; the result only swaps in the screen body.
        val shown = mutableStateOf(false)
        setContent { DifftTheme { if (shown.value) CallShownScreen(onDone = ::finish) else Box(Modifier.fillMaxSize().background(DifftTheme.colors.background)) } }
        appScope.launch {
            val result = repository.recordCallShown(runId)
            poster.cancel(runId, NotificationTestKind.CALL)
            withContext(Dispatchers.Main.immediate) {
                if (isFinishing || isDestroyed) return@withContext
                when (result) {
                    NotificationTestCallUpdate.RECORDED,
                    NotificationTestCallUpdate.ALREADY_SHOWN -> {
                        L.i { "[NotificationTest] call_shown outcome=ok" }
                        shown.value = true
                    }
                    NotificationTestCallUpdate.PERSISTENCE_FAILED -> {
                        ToastUtil.show(ChatR.string.notification_test_persistence_failed)
                        finish()
                    }
                    NotificationTestCallUpdate.REJECTED -> finish()
                }
            }
        }
    }

    companion object {
        const val RUN_ID = "notification_test_call_run_id"
    }

    /** API 27+ uses the manifest's showWhenLocked / turnScreenOn; API 26 needs the window flags. */
    private fun allowOnLockScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
    }
}

@Composable
private fun CallShownScreen(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DifftTheme.colors.background)
            .systemBarsPadding()
            .padding(DifftTheme.spacing.insetLarge),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(ChatR.string.notification_test_call_shown),
            style = DifftTheme.typography.titleMedium,
            color = DifftTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(DifftTheme.spacing.stackLarge))
        DifftButton(
            text = stringResource(ChatR.string.notification_test_done),
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
