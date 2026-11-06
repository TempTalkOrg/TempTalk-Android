package com.difft.android.chat.group

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.PageOperationScope
import com.difft.android.base.utils.pane.launchOutlivingPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Groups with a key rotation or an encryption upgrade in flight, process-wide. These operations
 * outlive the group-info page (a fold recreates it), so the exclusion that stops a second one
 * from racing the first must outlive it too; a recreated page reads [running] to show the wait
 * state and refuses to start another.
 */
internal object GroupCryptoOperations {

    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    val running: StateFlow<Set<String>> = inFlight.asStateFlow()

    /** Marks [groupId] busy; false when an operation for it is already running. */
    fun tryBegin(groupId: String): Boolean {
        while (true) {
            val current = inFlight.value
            if (groupId in current) return false
            if (inFlight.compareAndSet(current, current + groupId)) return true
        }
    }

    fun end(groupId: String) {
        while (true) {
            val current = inFlight.value
            if (inFlight.compareAndSet(current, current - groupId)) return
        }
    }

    /**
     * Starts [block] for [groupId] on the outliving page scope, or returns false while one is
     * running. Every path after the claim releases it: the block's own completion, or a failure
     * to launch it. Capture page inputs (Context, strings) before calling: nothing here may
     * throw between the claim and the release.
     */
    fun launch(page: Fragment, groupId: String, block: suspend PageOperationScope.() -> Unit): Boolean {
        if (!tryBegin(groupId)) return false
        try {
            page.launchOutlivingPage {
                try {
                    block()
                } finally {
                    end(groupId)
                }
            }
        } catch (e: Throwable) {
            end(groupId)
            throw e
        }
        return true
    }
}
