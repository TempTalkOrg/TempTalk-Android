package com.difft.android.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 1:1 settings rows are re-derived when the official-account flag flips after the page
 * opened (the cache may fill late), in both directions; previously the page was recreated.
 */
class SingleChatSettingRowsTest {

    private val contact = SingleChatSettingRows.of(isSelf = false, isOfficial = false, inContacts = true)
    private val bot = SingleChatSettingRows.of(isSelf = false, isOfficial = true, inContacts = true)

    @Test
    fun `turning official hides the write actions, common groups and create-group`() {
        assertEquals(
            SingleChatSettingRows(mute = true, remove = false, disappearing = true, block = false, commonGroups = false, createGroupAction = false),
            bot,
        )
    }

    @Test
    fun `turning back to a contact restores them`() {
        assertEquals(
            SingleChatSettingRows(mute = true, remove = true, disappearing = true, block = true, commonGroups = true, createGroupAction = true),
            contact,
        )
    }

    @Test
    fun `remove needs the contact in the address book, note to self keeps only what applies`() {
        assertEquals(false, SingleChatSettingRows.of(isSelf = false, isOfficial = false, inContacts = false).remove)
        assertEquals(
            SingleChatSettingRows(mute = false, remove = false, disappearing = false, block = true, commonGroups = false, createGroupAction = true),
            SingleChatSettingRows.of(isSelf = true, isOfficial = false, inContacts = false),
        )
    }
}
