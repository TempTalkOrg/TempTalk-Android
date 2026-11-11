package com.difft.android.chat.contacts.groupcard

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Action
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.Intent
import com.difft.android.chat.contacts.groupcard.mvi.GroupCardContract.State
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class GroupCardContentTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `unavailable card disables all action widgets`() {
        render(State(name = "Group", actionsEnabled = false, inviteEnabled = false))
        for (label in listOf(R.string.contact_action_message, R.string.chat_action_call,
            R.string.contacts_card_invite, R.string.chat_action_more)) {
            compose.onNodeWithText(context.getString(label)).assertIsNotEnabled()
        }
    }

    @Test
    fun `restricted invitation stays tappable like the other group entries and every action dispatches`() {
        val intents = mutableListOf<Intent>()
        render(State(name = "Group", actionsEnabled = true, inviteEnabled = false)) { intents.add(it) }
        compose.onNodeWithText(context.getString(R.string.contacts_card_invite)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.contact_action_message)).assertIsEnabled().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_action_call)).performClick()
        compose.onNodeWithText(context.getString(R.string.chat_action_more)).performClick()
        assertEquals<List<Intent>>(listOf(Intent.Open(Action.INVITE), Intent.Open(Action.MESSAGE), Intent.Open(Action.CALL),
            Intent.Open(Action.MORE)), intents)
    }

    @Test
    fun `today warning and enabled member row are rendered from viewmodel state`() {
        val intents = mutableListOf<Intent>()
        render(State(name = "Group", memberCount = 29, actionsEnabled = true, dissolveDays = 0)) { intents.add(it) }
        compose.onNodeWithText(context.getString(R.string.group_dissolve_today)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.group_dissolve_keep_hint)).assertIsDisplayed()
        compose.onNodeWithText("29").performClick()
        assertEquals<List<Intent>>(listOf(Intent.Open(Action.MEMBERS)), intents)
    }

    @Test fun `group card has no close button of its own`() {
        render(State(name = "Group"))
        compose.onNodeWithText("Group").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(com.difft.android.base.R.string.base_close)).assertDoesNotExist()
    }

    private fun render(state: State, onIntent: (Intent) -> Unit = {}) {
        compose.setContent { DifftTheme(applyWindowBackground = false) { GroupCard(state, onIntent) } }
    }
}
