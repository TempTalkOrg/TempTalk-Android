package com.difft.android.chat.setting.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33], qualifiers = "en")
class CompactExpiryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `English uses the largest whole unit at every boundary`() {
        assertCases(listOf("59s", "1m", "59m", "1h", "23h", "1d", "30d", "365d", ""))
        assertEquals("1m", 119L.toCompactExpiryText(context))
        assertEquals("59m", 3_599L.toCompactExpiryText(context))
        assertEquals("23h", 86_399L.toCompactExpiryText(context))
        assertEquals("", (-1L).toCompactExpiryText(context))
        assertEquals("106751991167300d", Long.MAX_VALUE.toCompactExpiryText(context))
    }

    @Test
    @Config(qualifiers = "zh")
    fun `Chinese uses localized compact units including the compact minute suffix`() {
        assertCases(listOf("59秒", "1分", "59分", "1小时", "23小时", "1天", "30天", "365天", ""))
    }

    private fun assertCases(expected: List<String>) {
        val seconds = listOf(59L, 60L, 59 * 60L, 3_600L, 23 * 3_600L, 86_400L, 30 * 86_400L, 365 * 86_400L, 0L)
        assertEquals(expected, seconds.map { it.toCompactExpiryText(context) })
    }
}
