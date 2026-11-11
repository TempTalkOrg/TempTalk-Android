package com.difft.android.base.utils

import kotlinx.coroutines.flow.StateFlow










object DualPaneCollapseUtil {

    private val flag = PersistedDualPaneFlag(
        tag = "DualPaneCollapse",
        read = { it.dualPaneListCollapsed },
        write = { dualPaneListCollapsed = it },
    )

    val collapsedState: StateFlow<Boolean>
        get() = flag.state

    val isCollapsed: Boolean
        get() = flag.value

    
    fun loadAndEmit() = flag.loadAndEmit()

    
    fun setCollapsed(collapsed: Boolean) = flag.set(collapsed)
}
