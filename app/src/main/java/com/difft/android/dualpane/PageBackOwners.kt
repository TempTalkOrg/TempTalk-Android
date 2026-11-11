package com.difft.android.dualpane

import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry








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
