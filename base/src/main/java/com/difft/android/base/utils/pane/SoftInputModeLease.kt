package com.difft.android.base.utils.pane

import android.app.Activity
import android.os.Build
import android.view.WindowManager.LayoutParams
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap












object SoftInputModeLease {

    private class Lease(val foundMode: Int) {
        val holders: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var conversationRootHeld = false
    }

    private val leases = WeakHashMap<Activity, Lease>()

    private val active: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    


    fun acquireResize(activity: Activity, owner: Any, conversationRoot: Boolean = false) {
        if (!active) return
        val window = activity.window ?: return
        val current = window.attributes.softInputMode
        val lease = leases.getOrPut(activity) { Lease(current) }
        lease.holders += owner
        if (conversationRoot) lease.conversationRootHeld = true
        val mode = if (conversationRoot) {
            LayoutParams.SOFT_INPUT_ADJUST_RESIZE or LayoutParams.SOFT_INPUT_STATE_HIDDEN
        } else {
            LayoutParams.SOFT_INPUT_ADJUST_RESIZE or (current and LayoutParams.SOFT_INPUT_MASK_STATE)
        }
        if (mode != current) window.setSoftInputMode(mode)
    }

    
    fun release(activity: Activity, owner: Any) {
        if (!active) return
        val lease = leases[activity] ?: return
        if (!lease.holders.remove(owner) || lease.holders.isNotEmpty()) return
        leases.remove(activity)
        val restore = if (lease.conversationRootHeld) {
            LayoutParams.SOFT_INPUT_ADJUST_NOTHING or LayoutParams.SOFT_INPUT_STATE_HIDDEN
        } else {
            lease.foundMode
        }
        val window = activity.window ?: return
        if (restore != 0) {
            window.setSoftInputMode(restore)
        } else {
            
            
            window.attributes = window.attributes.apply { softInputMode = restore }
        }
    }
}
