package com.difft.android.dualpane

internal enum class PaneReconcileAction { NONE, REBUILD_TREE }

/**
 * Decides whether the inflated pane tree still matches the window it is displayed in.
 *
 * The dual-pane gate is the resource qualifier on `layout-w673dp-h480dp/activity_index.xml`;
 * `@bool/dual_pane_layout_active` mirrors it with the identical qualifier set. When the OS
 * resizes the window without relaunching the activity (ColorOS fold/unfold, floating windows,
 * split-screen), the gate re-resolves against the new configuration while the tree stays as
 * inflated — that disagreement is the only signal we have, and the only fix is to inflate again.
 */
internal object PaneReconcileDecision {
    fun decide(
        gateExpectsDualPane: Boolean,
        treeHasDetailPane: Boolean,
        paneChromeStale: Boolean,
    ): PaneReconcileAction = when {
        // Structural disagreement in either direction: the window crossed the dual-pane floor
        // while this instance kept the pre-resize tree. Size-independent on purpose so that a
        // skipped layout callback cannot hide a due rebuild — onStart reconciles unconditionally.
        gateExpectsDualPane != treeHasDetailPane -> PaneReconcileAction.REBUILD_TREE
        // Right structure, wrong dimens band (values-w900dp): rail / divider widths are resolved
        // at inflation and nothing mutates them at runtime, so only re-inflation can fix them.
        treeHasDetailPane && paneChromeStale -> PaneReconcileAction.REBUILD_TREE
        // Structure and band agree. A resize INSIDE one band needs no rebuild: the pane split is
        // recomputed from the @dimen budget and the persisted ratio on every apply.
        else -> PaneReconcileAction.NONE
    }
}
