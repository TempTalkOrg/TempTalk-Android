package com.difft.android.dualpane

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneReconcileDecisionTest {

    private data class Case(
        val gateExpectsDualPane: Boolean,
        val treeHasDetailPane: Boolean,
        val paneChromeStale: Boolean,
        val expected: PaneReconcileAction,
        val why: String,
    )

    @Test
    fun `the full truth table - all eight input combinations`() {
        // Expected values are literals, not a re-derivation of the implementation's `when`.
        val cases = listOf(
            // Agreement on a single-pane tree: nothing to rebuild, and no rail / divider to be stale.
            Case(false, false, false, PaneReconcileAction.NONE, "phone, settled"),
            Case(false, false, true, PaneReconcileAction.NONE, "phone: no rail/divider to be stale"),
            // Gate says phone, tree is dual-pane: folded / shrunk without an OS relaunch.
            Case(false, true, false, PaneReconcileAction.REBUILD_TREE, "shrunk below the floor"),
            Case(false, true, true, PaneReconcileAction.REBUILD_TREE, "shrunk below the floor, chrome also stale"),
            // Gate says dual-pane, tree is single: unfolded / grew without an OS relaunch.
            Case(true, false, false, PaneReconcileAction.REBUILD_TREE, "grew past the floor"),
            Case(true, false, true, PaneReconcileAction.REBUILD_TREE, "grew past the floor, chrome flag inert"),
            // Agreement on a dual-pane tree.
            Case(true, true, false, PaneReconcileAction.NONE, "dual-pane, settled"),
            Case(true, true, true, PaneReconcileAction.REBUILD_TREE, "dual-pane crossed the values-w900dp band"),
        )
        assertEquals("the table must stay exhaustive over three booleans", 8, cases.size)
        cases.forEach { case ->
            assertEquals(
                "gate=${case.gateExpectsDualPane} tree=${case.treeHasDetailPane}" +
                    " chromeStale=${case.paneChromeStale} (${case.why})",
                case.expected,
                PaneReconcileDecision.decide(
                    gateExpectsDualPane = case.gateExpectsDualPane,
                    treeHasDetailPane = case.treeHasDetailPane,
                    paneChromeStale = case.paneChromeStale,
                )
            )
        }
    }

    @Test
    fun `loop-freedom - the input set a completed rebuild produces asks for no further rebuild`() {
        // A rebuild re-inflates from the current configuration, so the new instance's tree matches
        // the gate that asked for it and its chrome comes from the dimens band that configuration
        // selects. The first pass of the new instance is therefore gate == tree with fresh chrome,
        // the only fixed point in both directions.
        assertEquals(
            "post-rebuild pass after a shrink must settle",
            PaneReconcileAction.NONE,
            PaneReconcileDecision.decide(gateExpectsDualPane = false, treeHasDetailPane = false, paneChromeStale = false)
        )
        assertEquals(
            "post-rebuild pass after a grow must settle",
            PaneReconcileAction.NONE,
            PaneReconcileDecision.decide(gateExpectsDualPane = true, treeHasDetailPane = true, paneChromeStale = false)
        )
    }
}
