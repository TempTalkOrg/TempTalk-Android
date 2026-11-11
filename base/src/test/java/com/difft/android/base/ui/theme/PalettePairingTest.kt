package com.difft.android.base.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test


class PalettePairingTest {
    @Test
    fun `primary content contrasts in both themes`() {
        val light = createLightColorScheme()
        val dark = createDarkColorScheme()
        assertEquals(Color(0xFF1E2329), light.primary)
        assertEquals(Color.White, light.onPrimary)
        assertEquals(Color(0xFFEAECEF), dark.primary)
        assertEquals(Color(0xFF181A20), dark.onPrimary)
        listOf(light, dark).forEach { scheme ->
            assertNotEquals(scheme.primary, scheme.onPrimary)
            assertContrast(scheme.primary, scheme.onPrimary)
            assertEquals(Color.White, scheme.onError)
        }
    }

    @Test
    fun `system avatar foreground contrasts with its background in both themes`() {
        val light = createLightExtendedColors()
        val dark = createDarkExtendedColors()
        assertEquals(Color(0xFF1E2329), light.avsysBg)
        assertEquals(Color.White, light.avsysFg)
        assertEquals(Color(0xFF2B3139), dark.avsysBg)
        assertEquals(Color(0xFFEAECEF), dark.avsysFg)
        listOf(light, dark).forEach { colors ->
            assertNotEquals(colors.avsysBg, colors.avsysFg)
            assertContrast(colors.avsysBg, colors.avsysFg)
        }
    }

    @Test
    fun `material accent roles follow the neutral palette`() {
        val light = createLightColorScheme()
        val dark = createDarkColorScheme()
        listOf(light to createLightExtendedColors(), dark to createDarkExtendedColors()).forEach { (scheme, colors) ->
            assertEquals(scheme.primary, scheme.surfaceTint)
            assertEquals(colors.fill, scheme.primaryContainer)
            assertEquals(scheme.primary, scheme.onPrimaryContainer)
            assertEquals(scheme.primary, colors.icon)
            assertEquals(Color.White, colors.textOnPrimary)
            assertEquals(Color.White, colors.iconOnPrimary)
            assertEquals(Color(0xFF056FFA), colors.info)
        }
        assertEquals(dark.primary, light.inversePrimary)
        assertEquals(light.primary, dark.inversePrimary)
        assertEquals(Color(0xFF056FFA), createLightExtendedColors().textInfo)
        assertEquals(Color(0xFFA6F4FA), createDarkExtendedColors().textInfo)
    }

    private fun assertContrast(background: Color, foreground: Color) {
        val luminances = listOf(background.luminance(), foreground.luminance())
        val ratio = (luminances.max() + 0.05f) / (luminances.min() + 0.05f)
        assertTrue("Expected text contrast >= 4.5, got $ratio", ratio >= 4.5f)
    }
}
