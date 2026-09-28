package com.difft.android.dualpane

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneRailUtil
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Owns the navigation rail's collapsed / expanded presentation in the dual-pane layout.
 *
 * Collapsing hides the rail and its divider with `visibility = GONE` and nothing else: the list
 * pane keeps its width (the pane budget still subtracts the rail dimen), the detail pane absorbs
 * the freed width through its constraints, and `layoutParams.width` is untouched so
 * [DualPaneReconciler]'s chrome check keeps agreeing with the dimens. No animation — the Mac
 * client hides its rail the same way.
 *
 * The state lives in [DualPaneRailUtil]; the header toggle buttons in the three tab fragments
 * reach it through `DualPaneHost.toggleNavigationRail()`, which the host delegates here.
 */
internal class DualPaneRailController(
    private val rail: View,
    private val railDivider: View,
) {
    val collapsedState: StateFlow<Boolean>
        get() = DualPaneRailUtil.collapsedState

    /** Apply the persisted state now and keep following it while [owner] is started. */
    fun bind(owner: LifecycleOwner) {
        apply(DualPaneRailUtil.isCollapsed)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                DualPaneRailUtil.collapsedState.collect { apply(it) }
            }
        }
    }

    fun toggle(currentTab: Int) {
        val collapsed = !DualPaneRailUtil.isCollapsed
        L.i { "[IndexActivity] rail toggle collapsed=$collapsed tab=$currentTab" }
        DualPaneRailUtil.setCollapsed(collapsed)
    }

    private fun apply(collapsed: Boolean) {
        val visibility = if (collapsed) View.GONE else View.VISIBLE
        if (rail.visibility == visibility) return
        rail.visibility = visibility
        railDivider.visibility = visibility
    }
}
