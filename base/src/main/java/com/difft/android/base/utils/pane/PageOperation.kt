package com.difft.android.base.utils.pane

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch












fun Fragment.launchOutlivingPage(block: suspend PageOperationScope.() -> Unit): Job {
    val scope = PageOperationScope(this)
    return appScope.launch(Dispatchers.Main) { scope.block() }
}

class PageOperationScope internal constructor(private val page: Fragment) {

    
    val pageAlive: Boolean
        get() = page.isAdded && page.view != null

    
    fun onPage(block: (Fragment) -> Unit) {
        if (pageAlive) block(page)
    }
}
