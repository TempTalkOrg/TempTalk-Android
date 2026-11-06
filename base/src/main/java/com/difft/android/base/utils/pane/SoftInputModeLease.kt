package com.difft.android.base.utils.pane

import android.app.Activity
import android.os.Build
import android.view.WindowManager.LayoutParams
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap

/**
 * The Activity window's soft-input adjust mode below API 30, shared by every page of that window
 * that needs the window resized for the keyboard (conversation roots, keyboard pages). There
 * WindowInsetsAnimation does not work with ADJUST_NOTHING, so those pages need ADJUST_RESIZE;
 * the home screen hosts several such pages at once (one per tab stack, plus pushed keyboard
 * pages), and one going away must not reset the mode under another that is still shown.
 *
 * The first holder sets ADJUST_RESIZE; the last release restores ADJUST_NOTHING | STATE_HIDDEN
 * when a conversation root held the lease (what a conversation view wrote when it went), else the
 * mode found at the first acquire. API 30+ writes nothing. Main thread.
 */
object SoftInputModeLease {

    private class Lease(val foundMode: Int) {
        val holders: MutableSet<Any> = Collections.newSetFromMap(IdentityHashMap())
        var conversationRootHeld = false
    }

    private val leases = WeakHashMap<Activity, Lease>()

    private val active: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    /**
     * [owner] needs the window resized for the keyboard. A [conversationRoot] keeps today's
     * conversation write (ADJUST_RESIZE | STATE_HIDDEN); other holders keep the window's state bits.
     */
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

    /** [owner] no longer needs it; the last release restores the window's mode. */
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
            // setSoftInputMode(0) means "keep the current mode": put the unspecified mode back
            // through the window attributes.
            window.attributes = window.attributes.apply { softInputMode = restore }
        }
    }
}
