package com.difft.android.call.manager

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.user.UserManager
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.service.TestScopeApplication
import com.difft.android.network.ChativeHttpClient
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * Regression pin (family F, issue #1127): [CallFeedbackManager.showCallFeedbackView]
 * adds a temporary Compose overlay directly onto whatever host Activity `onActivityResumed`
 * happened to land on (`TempTalkApplication.kt:517-542` -> `LCallManager.showCallFeedbackView` ->
 * this class) — it never owns that Activity's window and must not mutate its background, neither
 * while displayed (M17) nor after the user dismisses it (M18).
 *
 * `CallRatingView` (rendered inside `CallRatingFeedbackView`) calls `ResUtils.getString`, which
 * dereferences the lateinit `ApplicationHelper.instance` — `TestScopeApplication.onCreate`
 * initializes it, same precedent as `ForegroundServiceIntegrationTest`/
 * `MainPageWithTopStatusViewTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [34])
class CallFeedbackManagerWindowBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val manager = CallFeedbackManager(
        callHttpClient = mockk<dagger.Lazy<ChativeHttpClient>>(relaxed = true),
        appStateStore = mockk<DataStore<Preferences>>(relaxed = true),
        userManager = mockk<UserManager>(relaxed = true),
    )

    private val callInfo = FeedbackCallInfo(
        userIdentity = "user-1",
        userSid = "sid-1",
        roomId = "room-1",
        roomSid = "room-sid-1",
    )

    @Test
    fun `M17 showCallFeedbackView never writes the host window background`() {
        val sentinel = ColorDrawable(Color.RED)
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        manager.showCallFeedbackView(composeTestRule.activity, callInfo)
        composeTestRule.waitForIdle()

        assertBackgroundUnchanged()
    }

    @Test
    fun `M18 dismissing the feedback view still never writes the host window background`() {
        val sentinel = ColorDrawable(Color.RED)
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        manager.showCallFeedbackView(composeTestRule.activity, callInfo)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.waitForIdle()

        assertBackgroundUnchanged()
    }

    private fun assertBackgroundUnchanged() {
        val background = composeTestRule.activity.window.decorView.background as ColorDrawable
        assertEquals(
            Color.RED,
            background.color,
            "CallFeedbackManager's overlay must never touch the host Activity's window background",
        )
    }
}
