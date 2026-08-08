package com.difft.android.chat.ui.messageaction

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the access-layer pure helpers (design-report §8.3, H1-H7).
 * failedActionDispatch / buildFailedActions / computeMaxPanelWidth are pure — no Android runtime.
 */
class MessageActionAccessTest {

    private fun assertDpEquals(expected: Float, actual: Dp) =
        assertEquals(expected, actual.value, 0.01f)

    // ---------- failedActionDispatch (H1-H4) ----------

    /** T5-2 */
    @Test
    fun H1_dispatch_resend() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.RESEND, { resend++ }, { delete++ })
        assertEquals(1, resend); assertEquals(0, delete)
    }

    /** T5-3 */
    @Test
    fun H2_dispatch_delete() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.DELETE, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(1, delete)
    }

    /**
     * T5-4 — MORE_INFO is no longer part of the failed-state set, so it must fall through to the
     * else branch and reach NO callback. Guards against Info being wired back in.
     */
    @Test
    fun H3_dispatch_moreInfo_isNoop() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.MORE_INFO, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(0, delete)
    }

    /** T5-5 */
    @Test
    fun H4_dispatch_other_isNoop() {
        var resend = 0; var delete = 0
        failedActionDispatch(MessageAction.Type.QUOTE, { resend++ }, { delete++ })
        assertEquals(0, resend); assertEquals(0, delete)
    }

    // ---------- buildFailedActions (H5) ----------

    /** T5-1 — failed-state menu is exactly Resend + Delete(destructive). */
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

    // ---------- computeMaxPanelWidth (H6-H7) ----------

    @Test
    fun H6_computeMaxPanelWidth_normal() {
        // content 1080px, edge 8dp @3x = 24px -> (1080 - 2*24)/3 = 344dp
        assertDpEquals(344f, computeMaxPanelWidth(1080, 24, Density(3f)))
    }

    @Test
    fun H7_computeMaxPanelWidth_underflow_coercedToZero() {
        // contentWidth < 2*edge -> coerceAtLeast(0) -> 0dp
        assertDpEquals(0f, computeMaxPanelWidth(10, 20, Density(3f)))
    }

    // ---------- failed panel geometry after narrowing to 2 actions (H8 / T5-6) ----------

    /**
     * T5-6 — pins the panel geometry that follows from the 2-action failed set: single row of 2
     * columns, each cell at the 68dp design width, so the panel is 68*2 + 1dp grid line = 137dp.
     * Keeps the design cell size verifiable without a device measurement.
     */
    @Test
    fun H8_failedPanel_twoActions_isTwoColumnsOfDesignCellWidth() {
        val cols = colsForN(buildFailedActions().size)
        assertEquals(2, cols)
        assertDpEquals(68f, designCellWidth(cols))
        // 344dp budget (H6) is far above the 137dp design panel -> no shrink branch.
        assertDpEquals(68f, computeCellWidth(cols, 344.dp))
    }
}
