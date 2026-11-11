package com.difft.android.base.ui.theme

import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import com.difft.android.base.ui.theme.tokens.ColorTokens
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteResourceTest {
    @Test
    fun `light XML and Compose palettes match`() = assertPalette(dark = false)

    @Test
    fun `dark XML and Compose palettes match`() = assertPalette(dark = true)

    private fun assertPalette(dark: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val configuration = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = context.createConfigurationContext(configuration)
        val scheme = if (dark) createDarkColorScheme() else createLightColorScheme()
        val colors = if (dark) createDarkExtendedColors() else createLightExtendedColors()
        val expected = mapOf(
            R.color.primary to scheme.primary,
            R.color.on_primary to scheme.onPrimary,
            R.color.icon to colors.icon,
            R.color.info to colors.info,
            R.color.t_info to colors.textInfo,
            R.color.bg_primary_clicked to colors.primaryPressed,
            R.color.fill to colors.fill,
            R.color.selected to colors.selected,
            R.color.avsys_bg to colors.avsysBg,
            R.color.avsys_fg to colors.avsysFg,
            R.color.bubble_sent_meta to colors.bubbleSentMeta,
            R.color.bubble_sent_link to colors.bubbleSentLink,
            R.color.ripple to if (dark) ColorTokens.Dark.Ripple else ColorTokens.Light.Ripple,
        )
        expected.forEach { (resource, color) ->
            assertEquals(themed.resources.getResourceEntryName(resource), color.toArgb(), themed.getColor(resource))
        }
        assertEquals(Color(if (dark) 0x0FFFFFFF else 0x0D000000), colors.fill)
        assertEquals(Color(if (dark) 0xFF474D57 else 0xFFFFFFFF), colors.selected)
        assertEquals(Color(if (dark) 0x1FEAECEF else 0x1F1E2329), expected[R.color.ripple])
        assertEquals(Color(if (dark) 0xFFB7BDC6 else 0xFF474D57), colors.primaryPressed)
    }
}
