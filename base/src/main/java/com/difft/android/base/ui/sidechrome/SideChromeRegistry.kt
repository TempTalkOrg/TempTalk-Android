package com.difft.android.base.ui.sidechrome

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner












class SideChromeRegistry(private val window: Lifecycle? = null) {

    private var clock = 0L
    private var windowResumed by mutableStateOf(window?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true)

    init {
        window?.addObserver(LifecycleEventObserver { source, _ ->
            windowResumed = source.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        })
    }

    
    inner class Entry internal constructor(internal val owner: LifecycleOwner, model: SideChromeModel, internal val base: Boolean) {
        internal var model by mutableStateOf(model)
        internal var state by mutableStateOf(owner.lifecycle.currentState)
        
        
        internal var activeAt by mutableLongStateOf(0L)
        internal val publishedAt = ++clock
        
        
        
        
        private var hidden by mutableStateOf(false)
        private var eligibleState by mutableStateOf(true)

        private val observer = LifecycleEventObserver { source, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                remove()
                return@LifecycleEventObserver
            }
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    activeAt = ++clock
                    hidden = false
                }
                Lifecycle.Event.ON_PAUSE -> if (window?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != false) hidden = true
                else -> Unit
            }
            state = source.lifecycle.currentState
        }

        init {
            owner.lifecycle.addObserver(observer)
        }

        internal val shown: Boolean
            get() = eligibleState && (state.isAtLeast(Lifecycle.State.RESUMED) ||
                (!windowResumed && !hidden && state.isAtLeast(Lifecycle.State.STARTED)))

        
        fun setEligible(eligible: Boolean) {
            eligibleState = eligible
        }

        fun update(model: SideChromeModel) {
            this.model = model
        }

        fun remove() {
            owner.lifecycle.removeObserver(observer)
            entries.remove(this)
        }
    }

    private val entries = mutableStateListOf<Entry>()

    





    var preferBase by mutableStateOf(true)

    
    val current: SideChromeModel? by derivedStateOf {
        val order = compareBy<Entry>({ it.activeAt }, { it.publishedAt })
        val shown = entries.filter { it.shown }
        val base = shown.filter { it.base }.maxWithOrNull(order)
        val covering = shown.filterNot { it.base }.maxWithOrNull(order)
        (if (preferBase) base ?: covering else covering)?.model
    }

    fun publish(owner: LifecycleOwner, model: SideChromeModel, base: Boolean = false): Entry? {
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return null
        return Entry(owner, model, base).also { entries.add(it) }
    }
}
