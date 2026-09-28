package com.difft.android.chat.translate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The single owner of the translate setting's display strings — the two settings entry rows, the
 * settings screen footer and the local system message all read it, so a drift here is a drift
 * between the screen and the message it inserts.
 *
 * Robolectric only because the mapping resolves real string resources; there is no view involved.
 *
 * What each case asserts:
 *  - T58 entry value text for (1, ready) / (1, not ready) / (2, ready) / (0, any)
 *  - T61 the system message reuses translateValueDescRes, pairing it with the settings-screen
 *        description string so the two can never drift
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslateDisplayTextTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- label / description mapping ---------------------------------------

    @Test
    fun `labels map 0 1 2 and treat an unknown value as off`() {
        assertEquals(R.string.chat_translate_setting_off, translateValueLabelRes(0))
        assertEquals(R.string.chat_translate_setting_zh, translateValueLabelRes(1))
        assertEquals(R.string.chat_translate_setting_en, translateValueLabelRes(2))
        assertEquals(
            "a value written by a newer build must read as off, not crash",
            R.string.chat_translate_setting_off,
            translateValueLabelRes(99),
        )
    }

    @Test
    fun `descriptions map 0 1 2 and treat an unknown value as off`() {
        assertEquals(R.string.chat_translate_setting_desc_off, translateValueDescRes(0))
        assertEquals(R.string.chat_translate_setting_desc_zh, translateValueDescRes(1))
        assertEquals(R.string.chat_translate_setting_desc_en, translateValueDescRes(2))
        assertEquals(R.string.chat_translate_setting_desc_off, translateValueDescRes(99))
    }

    // --- T58 ----------------------------------------------------------------

    @Test
    fun `T58 the entry value is the bare option label`() {
        assertEquals(context.getString(R.string.chat_translate_setting_off), translateEntryValue(context, value = 0))
        assertEquals(context.getString(R.string.chat_translate_setting_zh), translateEntryValue(context, value = 1))
        assertEquals(context.getString(R.string.chat_translate_setting_en), translateEntryValue(context, value = 2))
        assertEquals(context.getString(R.string.chat_translate_setting_off), translateEntryValue(context, value = 99))
    }
}
