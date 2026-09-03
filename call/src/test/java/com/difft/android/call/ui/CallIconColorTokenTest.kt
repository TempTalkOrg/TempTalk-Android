package com.difft.android.call.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the Android resource-resolution assumption behind the `fillColor` of
 * `R.drawable.call_ic_wifi_x` (the weak-network glyph).
 *
 * The glyph is a white accent on a fixed near-black badge (#0B0E11) and on the force-dark call
 * pill. An XML drawable resolves `@color/` through the SYSTEM resource configuration, not through
 * the Compose `DifftTheme(darkTheme = true)` wrapper the call screen applies — so the token must
 * be night-stable. `t.white` is written as #FFFFFF in BOTH `base values/colors.xml` and
 * `values-night/colors.xml`; a theme text token such as `t.primary` would flip and render the
 * glyph near-black on the badge whenever the system is in light mode.
 *
 * Each method also asserts `t.primary`, which DOES flip (#1E2329 light / #EAECEF dark). That is
 * the negative control: without a token that differs per qualifier, a `qualifiers` setup that
 * silently failed to take effect would let both `t.white` assertions pass while proving nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [30])
class CallIconColorTokenTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "notnight")
    fun `t white stays white under a light system config while t primary takes its light value`() {
        assertEquals(
            "t.white must be #FFFFFF under notnight",
            WHITE,
            ContextCompat.getColor(context, com.difft.android.base.R.color.t_white),
        )
        // Negative control — proves the notnight qualifier actually resolved.
        assertEquals(
            "t.primary must resolve to the light value under notnight",
            T_PRIMARY_LIGHT,
            ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary),
        )
    }

    @Test
    @Config(qualifiers = "night")
    fun `t white stays white under a dark system config while t primary takes its dark value`() {
        assertEquals(
            "t.white must be #FFFFFF under night too",
            WHITE,
            ContextCompat.getColor(context, com.difft.android.base.R.color.t_white),
        )
        // Negative control — a different value than the notnight method asserts, so the pair of
        // methods proves the night qualifier flipped the configuration.
        assertEquals(
            "t.primary must resolve to the dark value under night",
            T_PRIMARY_DARK,
            ContextCompat.getColor(context, com.difft.android.base.R.color.t_primary),
        )
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val T_PRIMARY_LIGHT = 0xFF1E2329.toInt()
        const val T_PRIMARY_DARK = 0xFFEAECEF.toInt()
    }
}
