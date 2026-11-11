package com.difft.android.chat.group

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.PageOperationScope
import com.difft.android.base.utils.pane.launchOutlivingPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow







internal object GroupCryptoOperations {

    private val inFlight = MutableStateFlow<Set<String>>(emptySet())

    val running: StateFlow<Set<String>> = inFlight.asStateFlow()

    
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
