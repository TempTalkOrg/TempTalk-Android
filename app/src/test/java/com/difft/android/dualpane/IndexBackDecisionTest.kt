package com.difft.android.dualpane

import org.junit.Assert.assertEquals
import org.junit.Test





class IndexBackDecisionTest {

    @Test
    fun `a pushed page pops first in both layouts, even over a collapsed list or an open list-pane layer`() {
        assertEquals(IndexBackAction.POP, IndexBackDecision.decide(depth = 2, dualPane = true, listCollapsed = true))
        assertEquals(IndexBackAction.POP, IndexBackDecision.decide(depth = 3, dualPane = false, listCollapsed = false))
        assertEquals(IndexBackAction.POP, IndexBackDecision.decide(depth = 2, dualPane = true, listCollapsed = false, layerDepth = 2))
    }

    @Test
    fun `one-pane root conversation returns to the list, also over the list-pane layer`() {
        assertEquals(IndexBackAction.POP_ROOT, IndexBackDecision.decide(depth = 1, dualPane = false, listCollapsed = false))
        assertEquals(IndexBackAction.POP_ROOT, IndexBackDecision.decide(depth = 1, dualPane = false, listCollapsed = false, layerDepth = 1))
    }

    @Test
    fun `two-pane root with a collapsed list re-opens the list before touching an invisible list-pane layer`() {
        assertEquals(IndexBackAction.EXPAND_LIST, IndexBackDecision.decide(depth = 1, dualPane = true, listCollapsed = true))
        assertEquals(IndexBackAction.EXPAND_LIST, IndexBackDecision.decide(depth = 0, dualPane = true, listCollapsed = true))
        assertEquals(IndexBackAction.EXPAND_LIST, IndexBackDecision.decide(depth = 1, dualPane = true, listCollapsed = true, layerDepth = 2))
    }

    @Test
    fun `with the list visible the list-pane layer pops its pushed page, then closes`() {
        assertEquals(IndexBackAction.LAYER_POP, IndexBackDecision.decide(depth = 1, dualPane = true, listCollapsed = false, layerDepth = 2))
        assertEquals(IndexBackAction.LAYER_POP, IndexBackDecision.decide(depth = 0, dualPane = false, listCollapsed = false, layerDepth = 3))
        assertEquals(IndexBackAction.LAYER_CLOSE, IndexBackDecision.decide(depth = 1, dualPane = true, listCollapsed = false, layerDepth = 1))
        assertEquals(IndexBackAction.LAYER_CLOSE, IndexBackDecision.decide(depth = 0, dualPane = false, listCollapsed = false, layerDepth = 1))
    }

    @Test
    fun `otherwise the task goes to the back`() {
        assertEquals(IndexBackAction.MOVE_TASK_TO_BACK, IndexBackDecision.decide(depth = 1, dualPane = true, listCollapsed = false))
        assertEquals(IndexBackAction.MOVE_TASK_TO_BACK, IndexBackDecision.decide(depth = 0, dualPane = false, listCollapsed = false))
        assertEquals(IndexBackAction.MOVE_TASK_TO_BACK, IndexBackDecision.decide(depth = 0, dualPane = true, listCollapsed = false, layerDepth = 0))
    }
}
