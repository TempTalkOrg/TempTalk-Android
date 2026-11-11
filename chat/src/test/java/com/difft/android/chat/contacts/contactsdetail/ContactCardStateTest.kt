package com.difft.android.chat.contacts.contactsdetail

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactCardStateTest {
    @Test fun `friend offers chat call share and more plus friend info`() {
        val state = contactCardPresentation(true, true, false, false, false)
        assertEquals(ContactCardKind.FRIEND, state.kind)
        assertTrue(state.showCall && state.showShare && state.showMore && state.showFriendInfo)
        assertFalse(state.showDelete)
    }

    @Test fun `stranger offers add and a private remark without friend-only metadata`() {
        val state = contactCardPresentation(true, false, false, false, false)
        assertEquals(ContactCardKind.STRANGER, state.kind)
        assertFalse(state.showCall || state.showShare || state.showMore || state.showDelete || state.showFriendInfo)
        assertTrue(state.showRemarkEdit)
    }

    @Test fun `pending removal takes precedence and offers delete`() {
        val state = contactCardPresentation(true, true, true, false, false)
        assertEquals(ContactCardKind.PENDING_REMOVAL, state.kind)
        assertTrue(state.showDelete && state.showRemarkEdit)
        assertFalse(state.showCall || state.showShare || state.showMore || state.showFriendInfo)
    }

    @Test fun `official pending contacts cannot be deleted and official friends cannot be called`() {
        assertFalse(contactCardPresentation(true, false, true, false, true).showDelete)
        val friend = contactCardPresentation(true, true, false, false, true)
        assertFalse(friend.showCall)
        assertTrue(friend.showShare)
    }

    @Test fun `self keeps message share and more without call delete or remark`() {
        val state = contactCardPresentation(true, false, false, true, false)
        assertEquals(ContactCardKind.FRIEND, state.kind)
        assertTrue(state.showShare && state.showMore)
        assertFalse(state.showCall || state.showDelete || state.showRemarkEdit)
    }

    @Test fun `unresolved relation does not flash friend actions`() {
        val state = contactCardPresentation(false, true, false, false, false)
        assertEquals(ContactCardKind.LOADING, state.kind)
        assertFalse(state.showCall || state.showShare || state.showMore || state.showRemarkEdit)
    }
}
