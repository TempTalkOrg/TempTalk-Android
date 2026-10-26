package com.difft.android.setting.notificationtest

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestRepository
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.compose.DifftButton
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.appScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.difft.android.chat.R as ChatR

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
        allowOnLockScreen()
        if (savedInstanceState == null) {
            L.i { "[NotificationTest] result kind=call outcome=ok reason=shown brand=${OemBrand.current()}" }
            val shownAt = System.currentTimeMillis()
            // Before the disk write: a test page at its timeout must already see the success.
            repository.markCallShown(shownAt)
            poster.cancel(NotificationTestKind.CALL)
            appScope.launch {
                try {
                    repository.recordCallShown(shownAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[NotificationTest] call record failed error=${e.javaClass.simpleName}" }
                }
            }
        }
        setContent {
            DifftTheme {
                CallShownScreen(onDone = ::finish)
            }
        }
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
