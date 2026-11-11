package util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone





class TimeFormatterHourFormatTest {

    private val defaultReader = TimeFormatter.hourFormatReader
    private var systemIs24Hour = false

    
    private val afternoon: Long = Calendar.getInstance().apply {
        set(2026, Calendar.OCTOBER, 26, 13, 5, 0)
    }.timeInMillis

    @Before
    fun setUp() {
        TimeFormatter.hourFormatReader = { systemIs24Hour }
        TimeFormatter.refreshHourFormat()
    }

    @After
    fun tearDown() {
        TimeFormatter.hourFormatReader = defaultReader
        TimeFormatter.resetHourFormatForTest()
    }

    @Test
    fun `a 24-hour change applies after the next foreground refresh`() {
        assertEquals("01:05 PM", TimeFormatter.formatMessageTime("en", afternoon))

        systemIs24Hour = true
        
        assertEquals("01:05 PM", TimeFormatter.formatMessageTime("en", afternoon))

        TimeFormatter.refreshHourFormat()
        assertEquals("13:05", TimeFormatter.formatMessageTime("en", afternoon))
    }

    @Test
    fun `the format signature changes with the language, the hour format and the time zone`() {
        val defaultZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val base = TimeFormatter.formatSignature("en")

            assertNotEquals(base, TimeFormatter.formatSignature("zh"))

            systemIs24Hour = true
            TimeFormatter.refreshHourFormat()
            val hourChanged = TimeFormatter.formatSignature("en")
            assertNotEquals(base, hourChanged)

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertNotEquals(hourChanged, TimeFormatter.formatSignature("en"))
        } finally {
            TimeZone.setDefault(defaultZone)
        }
    }
}
