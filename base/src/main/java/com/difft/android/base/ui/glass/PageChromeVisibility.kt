package com.difft.android.base.ui.glass

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach





fun bindPageChromeVisibility(
    owner: LifecycleOwner,
    windowLifecycle: Lifecycle?,
    covered: StateFlow<Boolean>?,
    vararg views: View,
): AutoCloseable = PageChromeVisibility(owner, windowLifecycle, covered, views)

private class PageChromeVisibility(
    private val owner: LifecycleOwner,
    private val window: Lifecycle?,
    private val covered: StateFlow<Boolean>?,
    private val views: Array<out View>,
) : AutoCloseable {
    private val original = IntArray(views.size) { views[it].visibility }
    private var lastFront = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    private var closed = owner.lifecycle.currentState == Lifecycle.State.DESTROYED
    private var job: Job? = null
    private val windowObserver = LifecycleEventObserver { _, _ -> render() }
    private val pageObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_DESTROY -> close()
            Lifecycle.Event.ON_RESUME -> lastFront = true
            Lifecycle.Event.ON_PAUSE -> if (window?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) lastFront = false
            else -> Unit
        }
        render()
    }

    init {
        if (!closed) {
            render()
            window?.addObserver(windowObserver)
            owner.lifecycle.addObserver(pageObserver)
            job = covered?.onEach { render() }?.launchIn(owner.lifecycleScope)
        }
    }

    private fun render() {
        if (closed) return
        val state = owner.lifecycle.currentState
        val windowPaused = window?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == false
        val front = state.isAtLeast(Lifecycle.State.RESUMED) ||
            (lastFront && windowPaused && state.isAtLeast(Lifecycle.State.STARTED))
        val shown = front && covered?.value != true
        for (index in views.indices) {
            views[index].visibility = if (shown || original[index] == View.GONE) original[index] else View.INVISIBLE
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        owner.lifecycle.removeObserver(pageObserver)
        window?.removeObserver(windowObserver)
        for (index in views.indices) views[index].visibility = original[index]
    }
}
