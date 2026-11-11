package com.difft.android.chat.ui.popup

import org.junit.Assert.assertEquals
import org.junit.Test









class PopupSheetGeometryTest {

    private companion object {
        const val BASE = 1200
        const val NAV = 60
        const val KB = 900
        const val PANEL = KB - NAV

        
        const val PANEL_LIFT = PANEL + NAV
    }

    private fun input(
        imeHeight: Int = 0,
        imeVisible: Boolean = false,
        panelHeight: Int = 0,
        panelVisible: Boolean = false,
        held: Int = 0,
        max: Int = 0,
    ) = SheetLiftInput(
        baseHeightPx = BASE,
        navigationBarHeightPx = NAV,
        imeHeightPx = imeHeight,
        imeVisible = imeVisible,
        panelHeightPx = panelHeight,
        panelVisible = panelVisible,
        heldLiftPx = held,
        maxHeightPx = max,
    )

    
    @Test
    fun `U1 resting sheet keeps base height and nav-bar padding`() {
        val geometry = PopupSheetGeometry.compute(input())

        assertEquals(BASE, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.peekHeightPx)
        assertEquals(0, geometry.heldLiftPx)
    }

    
    @Test
    fun `U2 keyboard lifts the sheet and consumes the same pixels as padding`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.peekHeightPx)
        assertEquals(KB, geometry.heldLiftPx)
    }

    
    @Test
    fun `U3 keyboard hidden holds the previous lift`() {
        val geometry = PopupSheetGeometry.compute(input(held = KB))

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    



    @Test
    fun `U4 panel lifts the sheet by its height plus the navigation bar without consuming padding`() {
        val geometry = PopupSheetGeometry.compute(input(panelHeight = PANEL, panelVisible = true))

        assertEquals(BASE + PANEL_LIFT, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)

        val listBox = geometry.heightPx - geometry.paddingBottomPx - PANEL
        assertEquals("the list box must match the keyboard state's", BASE, listBox)
    }

    



    @Test
    fun `U5 keyboard and panel both active take the maximum and give padding to the panel`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelHeight = PANEL, panelVisible = true)
        )

        assertEquals(BASE + KB, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.heightPx - geometry.paddingBottomPx - PANEL)
    }

    




    @Test
    fun `U6 keyboard to panel handoff does not change the sheet height`() {
        val keyboardOnly = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))
        val handoff = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelHeight = PANEL, panelVisible = true)
        )

        assertEquals(keyboardOnly.heightPx, handoff.heightPx)
    }

    




    @Test
    fun `U7 freeze before the panel notification computes the unchanged keyboard state`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))

        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.heightPx)
    }

    



    @Test
    fun `U8 panel close holds the lift instead of restoring base height`() {
        val geometry = PopupSheetGeometry.compute(input(held = PANEL_LIFT))

        assertEquals(BASE + PANEL_LIFT, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    
    @Test
    fun `U9 a shorter keyboard shrinks the sheet as before`() {
        val geometry = PopupSheetGeometry.compute(input(imeHeight = 700, imeVisible = true, held = KB))

        assertEquals(BASE + 700, geometry.heightPx)
    }

    
    @Test
    fun `U10 a shorter keyboard replacing a taller panel shrinks once`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = 700, imeVisible = true, held = PANEL_LIFT)
        )

        assertEquals(BASE + 700, geometry.heightPx)
    }

    
    @Test
    fun `U11 height is clamped and heldLift follows the clamped height`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = 1200, imeVisible = true, max = 2200)
        )

        assertEquals(2200, geometry.heightPx)
        assertEquals(1000, geometry.heldLiftPx)
    }

    
    @Test
    fun `U12 feeding heldLift back reproduces the same geometry`() {
        val first = PopupSheetGeometry.compute(input(imeHeight = 1200, imeVisible = true, max = 2200))
        val second = PopupSheetGeometry.compute(
            input(imeHeight = 1200, imeVisible = true, held = first.heldLiftPx, max = 2200)
        )

        assertEquals(first, second)
    }

    




    @Test
    fun `U13 a panel with no height applies no lift`() {
        val geometry = PopupSheetGeometry.compute(input(panelHeight = 0, panelVisible = true))

        assertEquals(BASE, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
    }

    





    @Test
    fun `U14 padding follows the keyboard even when the panel closed without releasing`() {
        val geometry = PopupSheetGeometry.compute(
            input(imeHeight = KB, imeVisible = true, panelVisible = false, held = PANEL)
        )

        assertEquals(KB, geometry.paddingBottomPx)
        assertEquals(BASE + KB, geometry.heightPx)
    }

    




    @Test
    fun `U15 keyboard to panel keeps the sheet height and the list box constant at every step`() {
        val keyboard = PopupSheetGeometry.compute(input(imeHeight = KB, imeVisible = true))
        val both = PopupSheetGeometry.compute(
            input(
                imeHeight = KB,
                imeVisible = true,
                panelHeight = PANEL,
                panelVisible = true,
                held = keyboard.heldLiftPx,
            )
        )
        val panelOnly = PopupSheetGeometry.compute(
            input(panelHeight = PANEL, panelVisible = true, held = both.heldLiftPx)
        )

        assertEquals(BASE + KB, keyboard.heightPx)
        assertEquals("no jump while both are momentarily visible", BASE + KB, both.heightPx)
        assertEquals("no jump once the keyboard is gone", BASE + KB, panelOnly.heightPx)

        assertEquals(BASE, keyboard.heightPx - keyboard.paddingBottomPx)
        assertEquals(BASE, both.heightPx - both.paddingBottomPx - PANEL)
        assertEquals(BASE, panelOnly.heightPx - panelOnly.paddingBottomPx - PANEL)
    }

    





    @Test
    fun `U16 a fallback-sized panel lifts by its height plus the navigation bar`() {
        val fallback = 700

        val geometry = PopupSheetGeometry.compute(
            input(panelHeight = fallback, panelVisible = true)
        )

        assertEquals(BASE + fallback + NAV, geometry.heightPx)
        assertEquals(NAV, geometry.paddingBottomPx)
        assertEquals(BASE, geometry.heightPx - geometry.paddingBottomPx - fallback)
    }
}
