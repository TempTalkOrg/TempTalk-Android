package com.difft.android.chat.group

import com.difft.android.chat.group.mvi.selectGroupMember
import org.junit.Test
import kotlin.test.assertEquals

class GroupSelectionReducerTest {
    @Test fun `single role selection can replace or remove the current choice`() {
        val eligible = setOf("a", "b")
        assertEquals(setOf("b"), selectGroupMember(setOf("a"), eligible, "b", single = true, enabled = true))
        assertEquals(emptySet(), selectGroupMember(setOf("b"), eligible, "b", single = true, enabled = true))
    }

    @Test fun `busy and ineligible taps preserve selection truth`() {
        val selected = setOf("a")
        assertEquals(selected, selectGroupMember(selected, setOf("a", "b"), "b", single = false, enabled = false))
        assertEquals(selected, selectGroupMember(selected, setOf("a"), "b", single = false, enabled = true))
    }

    @Test fun `multi member selection keeps existing choices`() {
        assertEquals(setOf("a", "b"), selectGroupMember(setOf("a"), setOf("a", "b"), "b", single = false, enabled = true))
    }
}
