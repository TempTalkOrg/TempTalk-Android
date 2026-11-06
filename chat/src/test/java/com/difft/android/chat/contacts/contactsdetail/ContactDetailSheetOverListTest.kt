package com.difft.android.chat.contacts.contactsdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F17: "Message" in a contact sheet opens a full conversation only when the sheet sits over the home
 * list with no conversation on screen; over a conversation it keeps the popup chat. Decided by what
 * is on screen, not by the layout mode (one-pane can now show a conversation on the home screen).
 */
class ContactDetailSheetOverListTest {

    @Test
    fun `a sheet over the home list opens the conversation as a page`() {
        assertTrue(ContactDetailFragment.isSheetOverList(isPopupMode = true, inHome = true, conversationOnScreen = null))
    }

    @Test
    fun `a sheet over a home conversation keeps the popup chat in either layout`() {
        assertFalse(ContactDetailFragment.isSheetOverList(isPopupMode = true, inHome = true, conversationOnScreen = "peer"))
    }

    @Test
    fun `a sheet outside the home screen or a non-sheet card is never over the list`() {
        assertFalse(ContactDetailFragment.isSheetOverList(isPopupMode = true, inHome = false, conversationOnScreen = null))
        assertFalse(ContactDetailFragment.isSheetOverList(isPopupMode = false, inHome = true, conversationOnScreen = null))
    }
}
