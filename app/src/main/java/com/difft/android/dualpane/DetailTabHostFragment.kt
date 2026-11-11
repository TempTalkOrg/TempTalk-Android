package com.difft.android.dualpane

import com.difft.android.base.ui.topchrome.TopChromeHost
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost










class DetailTabHostFragment : Fragment(), DetailStackHost {

    val tab: Int get() = requireArguments().getInt(ARG_TAB)

    private val navigator: PaneNavigator?
        get() = (activity as? PaneNavigator.Owner)?.paneNavigator

    private val backOwners = PageBackOwners(::pageOf, ::isVisibleTop)

    private val pageCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f.id == R.id.detail_tab_stack) {
                
                TopChromeHost.adopt(f, v)
                navigator?.onPageViewCreated(this@DetailTabHostFragment, f, v)
            }
            refreshBackOwners()
        }
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) = refreshBackOwners()
        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) = refreshBackOwners()
        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) = onStackChanged()
    }

    override val detailNavigation: DetailNavigation = object : DetailNavigation {
        override fun push(from: Fragment, destination: DetailDestination): Boolean {
            val nav = requireNavigator("push") ?: return false
            
            val page = pageOf(from)
            if (page == null || page !== topPage() || !nav.isShownTab(tab)) {
                L.w { "[PaneNav] push refused tab=$tab fromTop=${page != null && page === topPage()}" }
                return false
            }
            return nav.push(tab, destination.newFragment(), destination.subjectId, destination.pageKey)
        }

        override fun pop(from: Fragment): Boolean = pop(from, 1)

        override fun pop(from: Fragment, count: Int): Boolean {
            val nav = requireNavigator("pop") ?: return false
            val page = pageOf(from) ?: return false
            return nav.pop(tab, count, page)
        }

        override fun openConversation(target: ConversationTarget) {
            requireNavigator("open")?.openConversation(target, PaneNavigator.Origin.Tab(tab))
        }

        override fun closeConversation(from: Fragment) {
            
            val subject = pageOf(from)?.let { PaneSubjects.subjectOf(it) }
            requireNavigator("close")?.closeSubject(tab, subject)
        }

        





        override fun canPop(from: Fragment): Boolean {
            val page = pageOf(from) ?: return false
            return pages().indexOf(page) > 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(pageCallbacks, false)
        lifecycle.addObserver(LifecycleEventObserver { _, _ -> refreshBackOwners() })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        DetailStackLayout(inflater.context).apply {
            id = R.id.detail_tab_stack
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        refreshBackOwners()
    }

    override fun onDestroy() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(pageCallbacks)
        super.onDestroy()
    }

    
    fun pages(): List<Fragment> {
        if (!isAdded) return emptyList()
        return childFragmentManager.fragments.filter { it.id == R.id.detail_tab_stack }
    }

    val depth: Int get() = pages().size

    fun rootPage(): Fragment? = pages().firstOrNull()

    fun topPage(): Fragment? = pages().lastOrNull()

    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = backOwners.ownerFor(fragment)

    private fun onStackChanged() {
        refreshBackOwners()
        navigator?.onHostStackChanged()
    }

    internal fun refreshBackOwners() = backOwners.refresh()

    
    private fun pageOf(fragment: Fragment): Fragment? {
        var current: Fragment? = fragment
        while (current != null) {
            if (current.parentFragment === this) return current
            current = current.parentFragment
        }
        return null
    }

    private fun isVisibleTop(page: Fragment?): Boolean =
        page != null && isAdded && !isHidden &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            page === topPage() && !page.isHidden

    private fun requireNavigator(action: String): PaneNavigator? {
        val current = navigator
        if (current == null) L.w { "[PaneNav] host without navigator action=$action tab=$tab" }
        return current
    }

    companion object {
        private const val ARG_TAB = "detail_tab_host_tab"

        fun newInstance(tab: Int): DetailTabHostFragment =
            DetailTabHostFragment().apply { arguments = bundleOf(ARG_TAB to tab) }
    }
}






internal fun FragmentManager.shownDetailStackTop(): Fragment? =
    fragments.filterIsInstance<DetailTabHostFragment>().singleOrNull { !it.isHidden }?.topPage()
