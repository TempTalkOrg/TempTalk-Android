package com.difft.android.chat.ui.messageaction

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test






class MessageActionContentTest {

    private fun dummyActions(n: Int): List<MessageAction> =
        List(n) { MessageAction(type = MessageAction.Type.COPY, iconRes = 0, labelRes = 0) }

    private fun assertDpEquals(expected: Float, actual: Dp) {
        assertEquals(expected, actual.value, 0.01f)
    }

    

    @Test
    fun R1_colsForN_1() {
        assertEquals(1, colsForN(1))
    }

    @Test
    fun R2_colsForN_4() {
        assertEquals(4, colsForN(4))
    }

    @Test
    fun R3_colsForN_5() {
        assertEquals(5, colsForN(5))
    }

    @Test
    fun R4_colsForN_6to8_is4() {
        assertEquals(4, colsForN(6))
        assertEquals(4, colsForN(7))
        assertEquals(4, colsForN(8))
    }

    @Test
    fun R5_colsForN_9to10_is5() {
        assertEquals(5, colsForN(9))
        assertEquals(5, colsForN(10))
    }

    

    @Test
    fun R6_designCellWidth_5col_is64() {
        assertEquals(64.dp, designCellWidth(5))
    }

    @Test
    fun R7_designCellWidth_other_is68() {
        assertEquals(68.dp, designCellWidth(1))
        assertEquals(68.dp, designCellWidth(2))
        assertEquals(68.dp, designCellWidth(3))
        assertEquals(68.dp, designCellWidth(4))
    }

    

    @Test
    fun R8_computeCellWidth_5col_infinity_noShrink() {
        assertEquals(64.dp, computeCellWidth(5, Dp.Infinity))
    }

    @Test
    fun R9_computeCellWidth_5col_max300_shrinks() {
        
        assertDpEquals(59.2f, computeCellWidth(5, 300.dp))
    }

    @Test
    fun R10_computeCellWidth_4col_max275_boundary_noShrink() {
        
        assertEquals(68.dp, computeCellWidth(4, 275.dp))
    }

    @Test
    fun R11_computeCellWidth_5col_max0_clampsToMin_nonNegative() {
        val result = computeCellWidth(5, 0.dp)
        assertTrue("must be non-negative", result.value >= 0f)
        assertEquals(32.dp, result)
    }

    @Test
    fun R12_computeCellWidth_5col_max100_clampsToMin() {
        
        assertEquals(32.dp, computeCellWidth(5, 100.dp))
    }

    @Test
    fun R13_computeCellWidth_5col_max200_shrinksAboveMin() {
        
        assertDpEquals(39.2f, computeCellWidth(5, 200.dp))
    }

    

    @Test
    fun R14_padRows_7items_4cols() {
        val rows = padRows(dummyActions(7), 4)
        assertEquals(2, rows.size)
        assertEquals(4, rows[0].size)
        assertEquals(4, rows[1].size)
        rows[0].forEach { assertNotNull(it) }
        
        assertNotNull(rows[1][2])
        assertNull(rows[1][3])
    }

    @Test
    fun R15_padRows_9items_5cols() {
        val rows = padRows(dummyActions(9), 5)
        assertEquals(2, rows.size)
        assertEquals(5, rows[0].size)
        assertEquals(5, rows[1].size)
        rows[0].forEach { assertNotNull(it) }
        
        assertNotNull(rows[1][3])
        assertNull(rows[1][4])
    }
}
