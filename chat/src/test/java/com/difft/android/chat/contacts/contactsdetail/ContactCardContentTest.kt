package com.difft.android.chat.contacts.contactsdetail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
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
class ContactCardContentTest {
    @get:Rule val compose = createComposeRule()
    private fun text(id: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    @Test fun `friend shows metadata and invokes message action`() {
        var messages = 0
        show(ContactDetailUiState(displayName = "Friend", joinedAt = "2025.10", sourceDescribe = "Group",
            messageExpirySeconds = 86_400, presentation = contactCardPresentation(true, true, false, false, false)),
            onMessage = { messages++ })
        compose.onNodeWithText("2025.10").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.contacts_card_countdown)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.contact_action_message)).performClick()
        assertEquals(1, messages)
    }

    @Test fun `stranger hides friend metadata and shows add`() {
        show(ContactDetailUiState(joinedAt = "hidden-date", sourceDescribe = "hidden-source",
            presentation = contactCardPresentation(true, false, false, false, false)))
        compose.onNodeWithText("hidden-date").assertDoesNotExist()
        compose.onNodeWithText("hidden-source").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.contacts_card_add)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.contact_action_message)).assertDoesNotExist()
    }

    @Test fun `pending contact uses existing today rule and confirms through delete callback`() {
        var deletes = 0
        show(ContactDetailUiState(pendingRemovalDays = 1,
            presentation = contactCardPresentation(true, false, true, false, false)), onDelete = { deletes++ })
        compose.onNodeWithText(text(R.string.weak_contact_remove_today)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.weak_contact_keep_hint)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.contact_remove_dialog_yes)).performClick()
        assertEquals(1, deletes)
    }

    @Test fun `official pending contact has no delete action`() {
        show(ContactDetailUiState(isOfficialAccount = true, pendingRemovalDays = 2,
            presentation = contactCardPresentation(true, false, true, false, true)))
        compose.onNodeWithText(text(R.string.contact_remove_dialog_yes)).assertDoesNotExist()
    }

    @Test fun `pending action stays visibly disabled during submission`() {
        show(ContactDetailUiState(actionsEnabled = false,
            presentation = contactCardPresentation(true, false, true, false, false)))
        compose.onNodeWithText(text(R.string.contacts_card_add)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.contact_remove_dialog_yes)).assertIsNotEnabled()
    }

    @Test fun `card has no close button of its own and own card offers share`() {
        show(ContactDetailUiState(isSelf = true, presentation = contactCardPresentation(true, false, false, true, false)))
        compose.onNodeWithText(text(R.string.contact_action_share)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(com.difft.android.base.R.string.base_close)).assertDoesNotExist()
    }

    @Test fun `resolving the relation keeps the card height and the placeholder row stays hidden`() {
        val base = ContactDetailUiState(displayName = "Peer", userId = "peer-id")
        var state by mutableStateOf(base)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                ContactCard(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        val loading = compose.onRoot().fetchSemanticsNode().size.height
        compose.onNodeWithText(text(R.string.contact_action_message)).assertDoesNotExist()
        state = base.copy(isRelationResolved = true, presentation = contactCardPresentation(true, false, false, false, false))
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.contacts_card_add)).assertIsDisplayed()
        assertEquals(loading, compose.onRoot().fetchSemanticsNode().size.height)
    }

    private fun show(
        state: ContactDetailUiState,
        onMessage: () -> Unit = {},
        onDelete: () -> Unit = {},
        onClose: () -> Unit = {},
    ) {
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                ContactCard(state, onClose, {}, {}, {}, {}, onMessage, {}, {}, {}, {}, {}, onRemoveNowClick = onDelete)
            }
        }
    }
}
