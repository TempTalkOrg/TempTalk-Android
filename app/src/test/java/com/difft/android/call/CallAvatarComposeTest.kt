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

    




    @Test
    fun `sibling recomposition with unchanged letter data does not re-run setAvatar`() {
        assertUnrelatedRecompositionSkipsUpdate(
            initial = AvatarData.FromNameOrUid("Alice", "+123.1"),
            equalCopy = AvatarData.FromNameOrUid("Alice", "+123.1"),
            expectedLetter = "A",
        )
    }

    



    @Test
    fun `sibling recomposition with an unchanged contactor does not re-run setAvatar`() {
        assertUnrelatedRecompositionSkipsUpdate(
            initial = AvatarData.FromContactor(buildContactor(id = "+123", name = "Carol")),
            equalCopy = AvatarData.FromContactor(buildContactor(id = "+123", name = "Carol")),
            expectedLetter = "C",
        )
    }

    




    private fun assertUnrelatedRecompositionSkipsUpdate(
        initial: AvatarData,
        equalCopy: AvatarData,
        expectedLetter: String,
    ) {
        var tick by mutableStateOf(0)
        
        
        var data by mutableStateOf(initial, neverEqualPolicy())
        composeTestRule.setContent {
            DifftTheme {
                ConstraintLayout {
                    val (avatarRef, textRef) = createRefs()
                    CallAvatar(
                        data = data,
                        size = 32.dp,
                        
                        
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
        
        const val SENTINEL = "ZZ"
    }
}
