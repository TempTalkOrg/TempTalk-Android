package com.difft.android.chat.recent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier






class TimeTextFormatTrackerTest {

    private var current = "en|false|UTC"
    private val tracker = TimeTextFormatTracker { current }

    @Test
    fun `nothing formatted yet needs no refresh`() {
        current = "zh|false|UTC"
        assertFalse(tracker.needsRefresh())
    }

    @Test
    fun `a language, 12-24 hour or time zone change needs a refresh until re-formatted`() {
        listOf("zh|false|UTC", "zh|true|UTC", "zh|true|Asia/Tokyo").forEach { next ->
            tracker.markFormatted(current)
            assertFalse(tracker.needsRefresh())

            current = next
            assertTrue(next, tracker.needsRefresh())
        }
        tracker.markFormatted(current)
        assertFalse(tracker.needsRefresh())
    }

    @Test
    fun `the recorded signature is volatile`() {
        val field = TimeTextFormatTracker::class.java.getDeclaredField("formattedWith")
        assertTrue(Modifier.isVolatile(field.modifiers))
    }
}
