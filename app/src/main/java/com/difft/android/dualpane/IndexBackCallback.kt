package com.difft.android.dualpane

import androidx.activity.OnBackPressedCallback
import com.difft.android.base.log.lumberjack.L

internal enum class IndexBackAction { POP, POP_ROOT, EXPAND_LIST, LAYER_POP, LAYER_CLOSE, MOVE_TASK_TO_BACK }

/**
 * IndexActivity's Back order once the visible top page's own callbacks (registered with
 * `detailBackOwner()`, so they outrank this one) have passed: pop a pushed detail page, then in
 * one-pane leave the root conversation for the list, then re-open a collapsed two-pane list (a 1 px
 * list is revealed before an invisible list-pane layer is closed), then pop the list-pane layer's
 * pushed page, then close the layer, and only then send the task to the back.
 */
internal object IndexBackDecision {
    fun decide(depth: Int, dualPane: Boolean, listCollapsed: Boolean, layerDepth: Int = 0): IndexBackAction = when {
        depth > 1 -> IndexBackAction.POP
        !dualPane && depth == 1 -> IndexBackAction.POP_ROOT
        dualPane && listCollapsed -> IndexBackAction.EXPAND_LIST
        layerDepth > 1 -> IndexBackAction.LAYER_POP
        layerDepth == 1 -> IndexBackAction.LAYER_CLOSE
        else -> IndexBackAction.MOVE_TASK_TO_BACK
    }
}

/** The single Activity-level Back callback; register it in `onCreate`, before any page registers. */
internal class IndexBackCallback(
    private val navigator: PaneNavigator,
    private val listLayer: ListPaneLayer,
    private val isListCollapsed: () -> Boolean,
    private val expandList: () -> Unit,
    private val moveTaskToBack: () -> Unit,
) : OnBackPressedCallback(true) {

    override fun handleOnBackPressed() {
        val depth = navigator.depth()
        val layerDepth = listLayer.depth
        val action = IndexBackDecision.decide(depth, navigator.isDualPane, isListCollapsed(), layerDepth)
        L.i { "[PaneNav] back action=$action depth=$depth layerDepth=$layerDepth" }
        when (action) {
            IndexBackAction.POP, IndexBackAction.POP_ROOT -> navigator.pop(navigator.currentTab)
            IndexBackAction.EXPAND_LIST -> expandList()
            IndexBackAction.LAYER_POP -> listLayer.pop()
            IndexBackAction.LAYER_CLOSE -> listLayer.close()
            IndexBackAction.MOVE_TASK_TO_BACK -> moveTaskToBack()
        }
    }
}
