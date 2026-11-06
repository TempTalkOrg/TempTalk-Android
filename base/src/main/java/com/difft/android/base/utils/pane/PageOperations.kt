package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide record of page writes that must outlive their page (run inside
 * [launchOutlivingPage]), by key such as `"group-notification:<gid>"`. A page restored after a
 * fold reads [running] to show the write's wait again ([waitWhileVisibleTop]) and re-reads its
 * value when the key ends ([onOperationEnded]).
 */
object PageOperations {

    private val counts = HashMap<String, Int>()
    private val flows = HashMap<String, MutableStateFlow<Boolean>>()

    /** Whether a write for [key] is running now. */
    fun running(key: String): StateFlow<Boolean> = synchronized(this) { flowFor(key).asStateFlow() }

    /** Marks [key] running for the duration of [block] (nests and overlaps by count). */
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

/**
 * Runs [action] whenever no write for [key] is running, the first value included, for as long as
 * this page's view exists (backgrounded too): an outcome stored after the page last read its
 * value, also while the app was in the background, is re-read. Call from `onViewCreated`.
 */
fun Fragment.onOperationEnded(key: String, action: () -> Unit) {
    val running = PageOperations.running(key)
    viewLifecycleOwner.lifecycleScope.launch {
        running.collect { if (!it) action() }
    }
}
