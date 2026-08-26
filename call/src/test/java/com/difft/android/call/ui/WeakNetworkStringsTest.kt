package com.difft.android.call.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.R
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks the two weak-network banner strings into BOTH locale files `:call` ships
 * (`values/` and `values-zh/`; every other locale falls back to English).
 *
 * Shipping a string in only one of the two is a recurring defect in this repo — it surfaces as a
 * missing translation in production, never as a build failure. The zh method reads through the
 * `zh` qualifier and the default method through the fallback config, so a one-sided edit fails
 * one of them.
 *
 * `call_other_network_poor_tip` is taken verbatim from iOS
 * (`SINGLE_CALL_CALLER_NETWORK_POOR`) so the two clients read identically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30])
class WeakNetworkStringsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "zh")
    fun `weak network tips resolve to their zh values under the zh qualifier`() {
        assertEquals(ZH_MYSELF, context.getString(R.string.call_myself_network_poor_tip))
        assertEquals(ZH_OTHER, context.getString(R.string.call_other_network_poor_tip))
    }

    @Test
    fun `weak network tips resolve to filled english values under the default qualifier`() {
        val myself = context.getString(R.string.call_myself_network_poor_tip)
        val other = context.getString(R.string.call_other_network_poor_tip)

        assertTrue("default call_myself_network_poor_tip must not be blank", myself.isNotBlank())
        assertTrue("default call_other_network_poor_tip must not be blank", other.isNotBlank())
        // A zh value leaking into the default config means values/strings.xml is missing the entry.
        assertNotEquals(ZH_MYSELF, myself)
        assertNotEquals(ZH_OTHER, other)
    }

    private companion object {
        const val ZH_MYSELF = "你的网络不佳，可能影响通话质量"
        const val ZH_OTHER = "对方网络不佳"
    }
}
