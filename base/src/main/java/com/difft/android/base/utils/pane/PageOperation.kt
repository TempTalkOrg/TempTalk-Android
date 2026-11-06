package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs a page's server write and local persist to completion even when the page goes away
 * mid-way. A fold or unfold recreates IndexActivity and destroys its detail pages; their own
 * scopes would cancel a group leave or a key rotation between the server call and the local
 * update. The work runs on the application scope (on Main, hopping to IO itself as before); UI
 * follow-ups go through [PageOperationScope.onPage], which runs only while the page is still
 * showing.
 *
 * The page object is held until the operation ends (its block refers to it), so a destroyed page
 * stays in memory until its network call returns; nothing on it is touched after it is gone.
 */
fun Fragment.launchOutlivingPage(block: suspend PageOperationScope.() -> Unit): Job {
    val scope = PageOperationScope(this)
    return appScope.launch(Dispatchers.Main) { scope.block() }
}

class PageOperationScope internal constructor(private val page: Fragment) {

    /** The page is still added with a view: spinners, toasts and navigation may touch it. */
    val pageAlive: Boolean
        get() = page.isAdded && page.view != null

    /** Runs [block] with the page only while it is alive; skipped after the page was destroyed. */
    fun onPage(block: (Fragment) -> Unit) {
        if (pageAlive) block(page)
    }
}
