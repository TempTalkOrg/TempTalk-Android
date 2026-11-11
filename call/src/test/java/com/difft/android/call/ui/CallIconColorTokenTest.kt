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
