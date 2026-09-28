package com.difft.android.setting

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.difft.android.R
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.compose.settings.DifftSettingsGroup
import com.difft.android.base.ui.compose.settings.DifftSettingsOptionRow
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.login.PasscodeUtil
import util.TimeUtils
import kotlin.time.Duration.Companion.seconds

/**
 * Timeout row label: zero means the lock engages immediately, any other value reads as a duration.
 * The instant string comes from the caller's context, the duration from [TimeUtils], so the label
 * matches the timeout row on the screen-lock page.
 *
 * Kept out of the composable so a test can assert the rule directly.
 */
internal fun passcodeTimeoutLabel(context: Context, seconds: Int): String =
    if (seconds == 0) {
        context.getString(R.string.settings_screen_lock_timeout_instant)
    } else {
        TimeUtils.millis2FitTimeSpan(seconds.seconds.inWholeMilliseconds, 3, false)
    }

/**
 * Stateless body of the screen-lock timeout screen.
 *
 * [selected] is nullable because the stored value is read through `getUserData()`, which is null
 * when there is no user data; no row is marked then. Every tap reports through [onSelect],
 * including a tap on the already-selected row: the page stays open, and re-applying the same value
 * is a no-op for the host.
 */
@Composable
fun PasscodeTimeoutContent(
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DifftSettingsDefaults.PageInset),
    ) {
        Spacer(Modifier.height(DifftSettingsDefaults.FirstGroupSpacing))
        DifftSettingsGroup {
            PasscodeUtil.TIMEOUT_LIST.forEach { seconds ->
                DifftSettingsOptionRow(
                    label = passcodeTimeoutLabel(context, seconds),
                    selected = selected == seconds,
                    onClick = { onSelect(seconds) },
                )
            }
        }
        Spacer(Modifier.height(DifftSettingsDefaults.GroupSpacing))
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PasscodeTimeoutContentPreview() {
    DifftTheme(applyWindowBackground = false) {
        Column(Modifier.background(DifftTheme.colors.bg).size(360.dp, 400.dp)) {
            PasscodeTimeoutContent(
                selected = PasscodeUtil.DEFAULT_TIMEOUT,
                onSelect = {},
            )
        }
    }
}
