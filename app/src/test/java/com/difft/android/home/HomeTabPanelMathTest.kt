package com.difft.android.home

import com.difft.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeTabPanelMathTest {
    @Test
    fun `panel physical displacement eases toward four pixels per density unit and clamps`() {
        assertEquals(0f, HomeTabPanelMath.offset(0f, 274f, 4f), 0f)
        val partial = HomeTabPanelMath.offset(137f, 274f, 4f)
        assertTrue(partial > 2f && partial < 4f)
        assertEquals(-partial, HomeTabPanelMath.offset(-137f, 274f, 4f), 0f)
        assertEquals(4f, HomeTabPanelMath.offset(1000f, 274f, 4f), 0f)
        assertEquals(-4f, HomeTabPanelMath.offset(-1000f, 274f, 4f), 0f)
        assertEquals(0f, HomeTabPanelMath.offset(Float.NaN, 274f, 4f), 0f)
        assertEquals(0f, HomeTabPanelMath.offset(20f, 0f, 4f), 0f)
    }

    @Test
    fun `panel uniform press scale uses its long dimension and selected contents grow separately`() {
        assertEquals(1f, HomeTabPanelMath.scale(0f, 274f, 16f), 0f)
        assertEquals(1f + 16f / 274f, HomeTabPanelMath.scale(1f, 274f, 16f), 0f)
        assertEquals(1f + 8f / 152f, HomeTabPanelMath.scale(0.5f, 152f, 16f), 0f)
        assertEquals(1f + 16f / 152f, HomeTabPanelMath.scale(10f, 152f, 16f), 0f)
        assertEquals(1f, HomeTabPanelMath.scale(Float.NaN, 152f, 16f), 0f)
        assertEquals(1f, HomeTabPanelMath.scale(1f, 0f, 16f), 0f)
        assertEquals(1f, HomeTabPanelMath.selectedContentScale(0f), 0f)
        assertEquals(1.1f, HomeTabPanelMath.selectedContentScale(0.5f), 0.0001f)
        assertEquals(1.2f, HomeTabPanelMath.selectedContentScale(1f), 0.0001f)
    }

    @Test
    fun `live normal row is unselected while every hidden glyph uses existing filled assets`() {
        val filled = listOf(R.drawable.ic_home_tab_chat_filled, R.drawable.ic_home_tab_contact_filled, R.drawable.ic_home_tab_me_filled)
        val normal = listOf(R.drawable.ic_index_activity_chat_default, R.drawable.ic_index_activity_contact_default, R.drawable.ic_index_activity_me_default)
        repeat(HomeTab.COUNT) { selected ->
            repeat(HomeTab.COUNT) { tab ->
                val visible = homeTabSelectedVisual(false, true, selected, tab)
                val accent = homeTabSelectedVisual(true, true, selected, tab)
                assertFalse(visible)
                assertTrue(accent)
                assertEquals(normal[tab], homeTabGlyph(tab, visible))
                assertEquals(filled[tab], homeTabGlyph(tab, accent))
                assertEquals(selected == tab, homeTabSelectedVisual(false, false, selected, tab))
            }
        }
    }
}
