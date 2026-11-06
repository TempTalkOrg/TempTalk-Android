package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.PackageUtil
import com.difft.android.chat.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The vendor rows of the background connection page carry their own hint; the group caption only says they cannot be read. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BackgroundConnectionPageTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(brand: OemBrand) {
        val flags = BgRowFlags(
            showBackgroundRestriction = true,
            canOpenAutoStart = true,
            canOpenBackgroundActivity = true,
            exactAlarmApplicable = false,
        )
        composeTestRule.setContent {
            DifftTheme {
                BackgroundConnectionPage(
                    state = BackgroundConnectionPageState(
                        isBackgroundConnectionEnabled = true,
                        brand = brand,
                        rows = backgroundRowOrder(brand, flags),
                        check = BgCheckState(isBackgroundRestricted = false, isIgnoringBatteryOpt = false, isDataSaverRestricted = false),
                    ),
                    onBackClick = {},
                    onSwitchChanged = {},
                    onRowClick = {},
                    onGuideClick = {},
                )
            }
        }
    }

    private fun assertNoteInRow(row: BgRow, note: String) {
        composeTestRule.onNodeWithTag(bgRowTag(row), useUnmergedTree = false).assertTextContains(note)
        composeTestRule.onAllNodesWithText(note).assertCountEquals(1)
    }

    @Test
    fun `xiaomi vendor rows carry their hints inside the row`() {
        show(OemBrand.XIAOMI)
        val appName = PackageUtil.getAppName().orEmpty()
        assertNoteInRow(BgRow.AUTO_START, context.getString(R.string.background_hint_auto_start_xiaomi, appName))
        assertNoteInRow(BgRow.BACKGROUND_ACTIVITY, context.getString(R.string.background_hint_power_policy_xiaomi))
        composeTestRule.onNodeWithText(context.getString(R.string.background_vendor_checks_caption)).assertTextContains(
            context.getString(R.string.background_vendor_checks_caption),
        )
    }

    @Test
    fun `vivo vendor rows carry their hints inside the row`() {
        show(OemBrand.VIVO)
        assertNoteInRow(BgRow.AUTO_START, context.getString(R.string.background_hint_auto_start_vivo))
        assertNoteInRow(BgRow.BACKGROUND_POWER, context.getString(R.string.background_power_hint_vivo))
    }

    @Test
    fun `the group caption is the one sentence that the rows cannot be read`() {
        show(OemBrand.XIAOMI)
        composeTestRule.onAllNodesWithText(context.getString(R.string.background_vendor_checks_caption)).assertCountEquals(1)
        // The caption repeats no row hint.
        composeTestRule.onAllNodesWithText(context.getString(R.string.background_hint_power_policy_xiaomi), substring = true)
            .assertCountEquals(1)
    }
}
