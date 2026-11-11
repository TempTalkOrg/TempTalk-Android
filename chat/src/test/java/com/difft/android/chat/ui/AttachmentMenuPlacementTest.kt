package com.difft.android.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Test






class AttachmentMenuPlacementTest {

    private val area = PxBox(left = 0, top = 0, right = 400, bottom = 800)
    private val plus = PxBox(left = 16, top = 700, right = 60, bottom = 744)

    @Test
    fun `the menu sits above the plus button, left-aligned 8dp before it`() {
        val placement = AttachmentMenuPlacement.place(plus, area, contentHeight = 312, density = 1f)

        assertEquals(248, placement.width)
        assertEquals(312, placement.height)
        assertEquals(8, placement.x)
        assertEquals(700 - 8 - 312, placement.y)
    }

    @Test
    fun `the menu grows from the plus button centre`() {
        val placement = AttachmentMenuPlacement.place(plus, area, contentHeight = 312, density = 1f)

        assertEquals((38 - 8).toFloat(), placement.pivotX)
        assertEquals((722 - placement.y).toFloat(), placement.pivotY)
    }

    @Test
    fun `a narrow area takes the area width less 8dp each side`() {
        val narrow = PxBox(left = 0, top = 0, right = 200, bottom = 800)

        assertEquals(184, AttachmentMenuPlacement.width(narrow, density = 1f))
    }

    @Test
    fun `the menu stays 8dp inside the area on the right`() {
        val rightPlus = PxBox(left = 340, top = 700, right = 384, bottom = 744)

        val placement = AttachmentMenuPlacement.place(rightPlus, area, contentHeight = 312, density = 1f)

        assertEquals(400 - 8 - 248, placement.x)
    }

    @Test
    fun `a tall menu is capped to the area less the button and 32dp and then scrolls`() {
        val short = PxBox(left = 0, top = 0, right = 400, bottom = 300)
        val lowPlus = PxBox(left = 16, top = 250, right = 60, bottom = 294)

        val placement = AttachmentMenuPlacement.place(lowPlus, short, contentHeight = 312, density = 1f)

        assertEquals(300 - 44 - 32, placement.height)
        assertEquals(250 - 8 - placement.height, placement.y)
    }

    @Test
    fun `dp values scale with density`() {
        val placement = AttachmentMenuPlacement.place(
            source = PxBox(left = 32, top = 1400, right = 120, bottom = 1488),
            area = PxBox(left = 0, top = 0, right = 1080, bottom = 1600),
            contentHeight = 624,
            density = 2f,
        )

        assertEquals(496, placement.width)
        assertEquals(16, placement.x)
        assertEquals(1400 - 16 - 624, placement.y)
    }
}
