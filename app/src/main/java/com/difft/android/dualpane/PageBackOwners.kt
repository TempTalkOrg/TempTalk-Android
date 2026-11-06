package com.difft.android.dualpane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Back-callback owners for the pages of one stack host ([DetailTabHostFragment],
 * [ListPaneLayerHostFragment]): an owner is STARTED only while its requester's page is the host's
 * visible top, so Activity-dispatcher callbacks registered with it never fire for covered or
 * hidden pages. [pageOf] maps a requesting fragment to the host's page it belongs to;
 * [isVisibleTop] says whether that page is shown on top right now.
 */
internal class PageBackOwners(
    private val pageOf: (Fragment) -> Fragment?,
    private val isVisibleTop: (Fragment?) -> Boolean,
) {
    private val owners = LinkedHashMap<Fragment, Owner>()

    fun ownerFor(fragment: Fragment): LifecycleOwner =
        owners[fragment] ?: Owner(fragment).also {
            owners[fragment] = it
            it.update()
        }

    fun refresh() {
        owners.values.toList().forEach { it.update() }
    }

    /**
     * Owner for one requesting fragment. Anchored to the requester's view lifecycle (or the
     * fragment itself without a view): it is DESTROYED with it, which removes callbacks.
     */
    private inner class Owner(private val requester: Fragment) : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
        private val anchor: LifecycleOwner = requester.viewLifecycleOwnerLiveData.value ?: requester
        private val anchorObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) release() else update()
        }

        override val lifecycle: Lifecycle get() = registry

        init {
            anchor.lifecycle.addObserver(anchorObserver)
        }

        fun update() {
            if (registry.currentState == Lifecycle.State.DESTROYED) return
            val active = anchor.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && isVisibleTop(pageOf(requester))
            val target = if (active) Lifecycle.State.STARTED else Lifecycle.State.CREATED
            if (registry.currentState != target) registry.currentState = target
        }

        private fun release() {
            anchor.lifecycle.removeObserver(anchorObserver)
            registry.currentState = Lifecycle.State.DESTROYED
            owners.remove(requester)
        }
    }
}
