package com.difft.android.dualpane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import java.util.Objects


internal object PaneStackOperations {
    @Suppress("DEPRECATION")
    fun sameMeRoot(existing: Fragment?, requested: Fragment): Boolean {
        if (existing == null || existing.javaClass != requested.javaClass) return false
        val left = existing.arguments
        val right = requested.arguments
        val leftKeys = left?.keySet().orEmpty().filterNot { it.startsWith("pane_nav_") }.toSet()
        val rightKeys = right?.keySet().orEmpty().filterNot { it.startsWith("pane_nav_") }.toSet()
        return leftKeys == rightKeys && leftKeys.all { Objects.deepEquals(left?.get(it), right?.get(it)) }
    }

    
    fun popToDepth(host: DetailTabHostFragment, targetDepth: Int, slides: Boolean) {
        val stack = host.pages()
        if (targetDepth < 1 || targetDepth >= stack.size) return
        val top = stack.last()
        val revealed = stack[targetDepth - 1]
        val tx = host.childFragmentManager.beginTransaction().setReorderingAllowed(true)
        val between = stack.drop(targetDepth).filter { it !== top }
        between.forEach { tx.remove(it) }
        PageSlide.clear(between)
        if (slides) PageSlide.pop(top, revealed) else PageSlide.clear(listOf(top, revealed))
        tx.remove(top).show(revealed).setMaxLifecycle(revealed, Lifecycle.State.RESUMED).commitNow()
        host.refreshBackOwners()
    }
}
