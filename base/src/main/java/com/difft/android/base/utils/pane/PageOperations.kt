package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch







object PageOperations {

    private val counts = HashMap<String, Int>()
    private val flows = HashMap<String, MutableStateFlow<Boolean>>()

    
    fun running(key: String): StateFlow<Boolean> = synchronized(this) { flowFor(key).asStateFlow() }

    
    suspend fun <T> track(key: String, block: suspend () -> T): T {
        update(key, +1)
        try {
            return block()
        } finally {
            update(key, -1)
        }
    }

    private fun update(key: String, delta: Int) = synchronized(this) {
        val count = ((counts[key] ?: 0) + delta).coerceAtLeast(0)
        if (count == 0) counts.remove(key) else counts[key] = count
        flowFor(key).value = count > 0
    }

    private fun flowFor(key: String): MutableStateFlow<Boolean> = flows.getOrPut(key) { MutableStateFlow(false) }
}






fun Fragment.onOperationEnded(key: String, action: () -> Unit) {
    val running = PageOperations.running(key)
    viewLifecycleOwner.lifecycleScope.launch {
        running.collect { if (!it) action() }
    }
}
