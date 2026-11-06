package com.difft.android.setting

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.UserData
import com.difft.android.setting.mvi.ScreenLockContract
import com.difft.android.setting.mvi.ScreenLockSettingsViewModel
import com.difft.android.setting.repo.ScreenLockSettingsRepository
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.test.rules.GlobalStaticMockRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenLockSettingsContentTest {
    @get:Rule(order = 0) val compose = createComposeRule()
    @get:Rule(order = 1) val globals = GlobalStaticMockRule()

    @Test
    fun `pending enable keeps real switch off and blocks the other lock request`() {
        val users = FakeUserManager(UserData(account = "test-user-001", email = "masked", passcode = "existing"))
        val model = ScreenLockSettingsViewModel(ScreenLockSettingsRepository(users), SavedStateHandle())
        compose.setContent {
            val state by model.state.collectAsState()
            DifftTheme(applyWindowBackground = false) {
                ScreenLockSettingsContent(state, true, true, {},
                    { kind, enabled -> model.dispatch(ScreenLockContract.Intent.Toggle(kind, enabled)) }, {}, {})
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pattern = compose.onNodeWithText(context.getString(R.string.settings_pattern_lock))
        pattern.performClick()
        pattern.assertIsOff()
        compose.runOnIdle {
            assertFalse(model.state.value.pattern)
            assertNotNull(model.state.value.pending)
        }
        pattern.performClick()
        pattern.assertIsOff()
    }
}
