package com.difft.android.dualpane

internal enum class PaneReconcileAction { NONE, REBUILD_TREE }










internal object PaneReconcileDecision {
    fun decide(
        gateExpectsDualPane: Boolean,
        treeHasDetailPane: Boolean,
        paneChromeStale: Boolean,
    ): PaneReconcileAction = when {
        
        
        
        gateExpectsDualPane != treeHasDetailPane -> PaneReconcileAction.REBUILD_TREE
        
        
        treeHasDetailPane && paneChromeStale -> PaneReconcileAction.REBUILD_TREE
        
        
        else -> PaneReconcileAction.NONE
    }
}
