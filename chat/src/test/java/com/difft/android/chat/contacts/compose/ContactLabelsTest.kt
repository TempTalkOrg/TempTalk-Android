package com.difft.android.chat.contacts.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactLabelsTest {
    @Test
    fun `membership badge hides missing counts and shows the full count`() {
        assertNull(memberCountLabel(0))
        assertNull(memberCountLabel(-1))
        assertEquals("1", memberCountLabel(1))
        assertEquals("99", memberCountLabel(99))
        assertEquals("100", memberCountLabel(100))
        assertEquals("1234", memberCountLabel(1234))
    }

    @Test
    fun `countdown hides absent deadline and uses today for elapsed deadlines`() {
        assertNull(countdownDaysLabel(null, "Today", "%dd"))
        assertEquals("Today", countdownDaysLabel(0, "Today", "%dd"))
        assertEquals("Today", countdownDaysLabel(-1, "Today", "%dd"))
        assertEquals("1d", countdownDaysLabel(1, "Today", "%dd"))
        assertEquals("13d", countdownDaysLabel(13, "Today", "%dd"))
    }

    @Test
    fun `countdown honors supplied localized labels`() {
        assertEquals("今天", countdownDaysLabel(0, "今天", "%d天"))
        assertEquals("3天", countdownDaysLabel(3, "今天", "%d天"))
    }
}
