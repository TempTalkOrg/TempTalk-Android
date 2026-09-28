package com.difft.android.call

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.data.AvatarData
import com.difft.android.chat.common.AvatarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.difft.app.database.test.builders.buildContactor
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Contract tests for the Compose avatar wrapper the call screens render through
 * (`LCallToChatController.Avatar` delegates to [CallAvatar] verbatim).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class CallAvatarComposeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `wrapper applies the requested diameter`() {
        composeTestRule.setContent {
            DifftTheme {
                CallAvatar(
                    data = AvatarData.FromNameOrUid("Alice", "+123.1"),
                    size = 32.dp,
                    modifier = Modifier.testTag("avatar"),
                    letterTextSizeDp = AvatarLetterSize.forDiameterDp(32),
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("avatar").assertWidthIsEqualTo(32.dp)
        composeTestRule.onNodeWithTag("avatar").assertHeightIsEqualTo(32.dp)
    }

    @Test
    fun `changing the avatar data rebinds the same AvatarView instead of rebuilding it`() {
        // Pins the reason sites A/B no longer wrap the wrapper in key(avatarData): a new
        // AvatarView per data change would restart the image request from the letter placeholder.
        var data by mutableStateOf<AvatarData>(AvatarData.FromNameOrUid("Alice", "+123.1"))
        composeTestRule.setContent {
            DifftTheme {
                CallAvatar(
                    data = data,
                    size = 32.dp,
                    modifier = Modifier.testTag("avatar"),
                    letterTextSizeDp = AvatarLetterSize.DEFAULT_LETTER_TEXT_SIZE_DP,
                )
            }
        }
        composeTestRule.waitForIdle()

        val first = findAvatarView()
        assertNotNull("AvatarView should be hosted by the wrapper", first)
        assertEquals("A", letterOf(first!!))

        data = AvatarData.FromNameOrUid("Bob", "+123.1")
        composeTestRule.waitForIdle()

        val second = findAvatarView()
        assertSame("recomposition must rebind, not rebuild", first, second)
        assertEquals("B", letterOf(second!!))
    }

    /**
     * Letter path of the two skipping contracts that keep the participants sheet cheap.
     *
     * Production row shape: the participants sheet lays the avatar and its labels out in a
     * `ConstraintLayout`, so a sibling state change (speaking, mute) recomposes the row and
     * rebuilds the avatar's `constrainAs` modifier.
     *
     * The tick loop drives that row recomposition and, via the changing padding, forces
     * [CallAvatar] itself to recompose. `LetterAvatar`'s `update` lambda is keyed on
     * `firstLetter`/`id`/`sizePx`/`letterTextSizeDp`, all unchanged, so it stays memoised and
     * `AndroidView` must not re-apply it: `setAvatar` never resets to the letter placeholder nor
     * restarts Glide. A sentinel written straight onto the hosted View survives only if it did not.
     *
     * The equal-copy step pins the other half: an equals-equal new instance skips [CallAvatar] at
     * the call site (`AvatarData` is compared by equals there), so the hosted View is untouched.
     */
    @Test
    fun `sibling recomposition with unchanged letter data does not re-run setAvatar`() {
        assertUnrelatedRecompositionSkipsUpdate(
            initial = AvatarData.FromNameOrUid("Alice", "+123.1"),
            equalCopy = AvatarData.FromNameOrUid("Alice", "+123.1"),
            expectedLetter = "A",
        )
    }

    /**
     * Same two contracts for the sheet's common `AvatarData.FromContactor` path, whose `update`
     * lambda captures an unstable `ContactorModel` and is therefore keyed on that instance's
     * identity: the tick loop keeps the instance, so the lambda stays memoised and `setAvatar` does
     * not run. The equal-copy step swaps in a new but equals-equal `ContactorModel`, which is
     * skipped at the [CallAvatar] call site, so the hosted View is untouched there too.
     */
    @Test
    fun `sibling recomposition with an unchanged contactor does not re-run setAvatar`() {
        assertUnrelatedRecompositionSkipsUpdate(
            initial = AvatarData.FromContactor(buildContactor(id = "+123", name = "Carol")),
            equalCopy = AvatarData.FromContactor(buildContactor(id = "+123", name = "Carol")),
            expectedLetter = "C",
        )
    }

    /**
     * Hosts [initial] in a production-shaped row, recomposes the row three times while the avatar
     * data stays the same instance, then writes [equalCopy]. After each phase the hosted
     * `AvatarView` must be the same instance and must still carry [SENTINEL].
     */
    private fun assertUnrelatedRecompositionSkipsUpdate(
        initial: AvatarData,
        equalCopy: AvatarData,
        expectedLetter: String,
    ) {
        var tick by mutableStateOf(0)
        // neverEqualPolicy so the equals-equal write below actually reaches Compose: under the
        // default structural policy it is a no-op snapshot write and the control passes vacuously.
        var data by mutableStateOf(initial, neverEqualPolicy())
        composeTestRule.setContent {
            DifftTheme {
                ConstraintLayout {
                    val (avatarRef, textRef) = createRefs()
                    CallAvatar(
                        data = data,
                        size = 32.dp,
                        // The padding changes per tick, which is what forces CallAvatar to
                        // recompose; the constrainAs rebuild mirrors the production row.
                        modifier = Modifier
                            .padding(tick.dp)
                            .constrainAs(avatarRef) { start.linkTo(parent.start) }
                            .testTag("avatar"),
                        letterTextSizeDp = AvatarLetterSize.DEFAULT_LETTER_TEXT_SIZE_DP,
                    )
                    Text(
                        text = "tick $tick",
                        modifier = Modifier.constrainAs(textRef) { start.linkTo(avatarRef.end) },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val view = findAvatarView()
        assertNotNull("AvatarView should be hosted by the wrapper", view)
        assertEquals(expectedLetter, letterOf(view!!))
        setLetter(view, SENTINEL)

        repeat(3) {
            tick += 1
            composeTestRule.waitForIdle()
        }
        composeTestRule.onNodeWithText("tick 3").assertExists()

        assertSame("sibling recomposition must not rebuild the View", view, findAvatarView())
        assertEquals("sibling recomposition must not re-run setAvatar", SENTINEL, letterOf(view))

        data = equalCopy
        composeTestRule.waitForIdle()

        assertSame("an equals-equal instance must not rebuild the View", view, findAvatarView())
        assertEquals(
            "an equals-equal instance must not re-run setAvatar",
            SENTINEL,
            letterOf(view),
        )
    }

    private fun letterOf(view: AvatarView): String =
        view.findViewById<TextView>(com.difft.android.chat.R.id.tv_avatar).text.toString()

    private fun setLetter(view: AvatarView, text: String) {
        view.findViewById<TextView>(com.difft.android.chat.R.id.tv_avatar).text = text
    }

    private fun findAvatarView(): AvatarView? =
        findAvatarView(composeTestRule.activity.window.decorView)

    private fun findAvatarView(root: View): AvatarView? {
        if (root is AvatarView) return root
        if (root !is ViewGroup) return null
        for (i in 0 until root.childCount) {
            findAvatarView(root.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        /** Written straight onto the hosted View; erased if `setAvatar` runs again. */
        const val SENTINEL = "ZZ"
    }
}
