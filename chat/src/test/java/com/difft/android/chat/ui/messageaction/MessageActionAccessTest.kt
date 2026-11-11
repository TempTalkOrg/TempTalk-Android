package com.difft.android.chat.ui.messageaction

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test





class MessageActionAccessTest {

    private fun assertDpEquals(expected: Float, actual: Dp) =
        assertEquals(expected, actual.value, 0.01f)

    

    @Test
    fun H1_dispatch_resend() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.RESEND, { resend++ }, { delete++ })
        assertEquals(1, resend); assertEquals(0, delete)
    }

    @Test
    fun H2_dispatch_delete() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.DELETE, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(1, delete)
    }

    


    @Test
    fun H3_dispatch_moreInfo_isNoop() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.MORE_INFO, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(0, delete)
    }

    @Test
    fun H4_dispatch_other_isNoop() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.QUOTE, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(0, delete)
    }

    

    
    @Test
    fun H5_failedActions_content_and_deleteIsDestructive() {
        val actions = buildFailedActions()
        assertEquals(
            listOf(
                MessageAction.Type.RESEND,
                MessageAction.Type.DELETE
            ),
            actions.map { it.type }
        )
        assertEquals(2, actions.size)
        assertTrue(actions[1].isDestructive)
    }

    

    @Test
    fun H6_computeMaxPanelWidth_normal() {
        
        assertDpEquals(344f, computeMaxPanelWidth(1080, 24, Density(3f)))
    }

    @Test
    fun H7_computeMaxPanelWidth_underflow_coercedToZero() {
        
        assertDpEquals(0f, computeMaxPanelWidth(10, 20, Density(3f)))
    }

    

    


    @Test
    fun H8_failedPanel_twoActions_isTwoColumnsOfDesignCellWidth() {
        val cols = colsForN(buildFailedActions().size)
        assertEquals(2, cols)
        assertDpEquals(68f, designCellWidth(cols))
        
        assertDpEquals(68f, computeCellWidth(cols, 344.dp))
    }
}
