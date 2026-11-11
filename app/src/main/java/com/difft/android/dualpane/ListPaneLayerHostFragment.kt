package com.difft.android.dualpane

import com.difft.android.base.ui.topchrome.TopChromeHost
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.ui.sidechrome.SideChromeOptOut
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
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
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import com.difft.android.search.SearchSelectionPage










internal class ListPaneLayerHostFragment : Fragment(), DetailStackHost, DualPaneSelectionListener, SideChromeOptOut {

    
    
    override val keepsOwnChrome: Boolean get() = (activity as? DualPaneHost)?.isDualPaneMode == true

    
    interface Pages {
        fun layerRoot(root: ListPaneLayerRoot): Fragment

        
        fun layerRoot(root: ListPaneLayerRoot, args: Bundle?): Fragment = layerRoot(root)
    }

    
    val root: ListPaneLayerRoot
        get() = arguments?.getString(ARG_ROOT)?.let { name -> ListPaneLayerRoot.entries.firstOrNull { it.name == name } }
            ?: ListPaneLayerRoot.SEARCH

    
    val rootArgs: Bundle? get() = arguments?.getBundle(ARG_ROOT_ARGS)

    
    fun hasRootArgs(args: Bundle?): Boolean {
        val mine = rootArgs
        if (mine == null || args == null) return mine == null && args == null
        if (mine.keySet() != args.keySet()) return false
        return mine.keySet().all { key ->
            val a = mine.get(key)
            val b = args.get(key)
            when (a) {
                is Array<*> -> b is Array<*> && a.contentEquals(b)
                is List<*> -> a == b
                else -> a == b
            }
        }
    }

    private val layer: ListPaneLayer?
        get() = (activity as? ListPaneLayer.Owner)?.listLayer

    private var selectedId: String? = null
    private val backOwners = PageBackOwners(::pageOf, ::isVisibleTop)

    private val pageCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f.id == R.id.list_layer_stack) {
                forwardSelection(f)
                onPageViewCreated(f, v)
            }
            backOwners.refresh()
        }
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) = backOwners.refresh()
        override fun onFragmentStopped(fm: FragmentManager, f: Fragment) = backOwners.refresh()
        override fun onFragmentDetached(fm: FragmentManager, f: Fragment) = backOwners.refresh()
    }

    override val detailNavigation: DetailNavigation = object : DetailNavigation {
        override fun push(from: Fragment, destination: DetailDestination): Boolean {
            val page = pageOf(from)
            val top = topPage()
            if (page == null || page !== top || !isAdded) {
                L.w { "[ListLayer] push refused fromTop=${page != null && page === top}" }
                return false
            }
            if (top != null && PaneSubjects.pageKeyOf(top) == destination.pageKey) {
                L.i { "[ListLayer] push_dropped reason=duplicate" }
                return true
            }
            val next = destination.newFragment()
            PaneSubjects.setIdentity(next, destination.subjectId, destination.pageKey)
            PageSlide.push(next, top)
            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.list_layer_stack, next)
                .apply { if (top != null) hide(top).setMaxLifecycle(top, Lifecycle.State.STARTED) }
                .commitNow()
            backOwners.refresh()
            return true
        }

        override fun pop(from: Fragment): Boolean = pop(from, 1)

        override fun pop(from: Fragment, count: Int): Boolean {
            if (count <= 0) return false
            val page = pageOf(from) ?: return false
            if (page !== topPage()) {
                L.i { "[ListLayer] pop_dropped reason=stale" }
                return false
            }
            val targetDepth = depth - count
            if (targetDepth <= 0) {
                val owner = layer ?: return false
                owner.close()
                return true
            }
            popToDepth(targetDepth)
            return true
        }

        override fun openConversation(target: ConversationTarget) {
            val home = activity as? ConversationNavigationCallback
            if (home == null) {
                L.w { "[ListLayer] open_ignored reason=no_home kind=${target.kind}" }
                return
            }
            home.openConversation(target)
        }

        override fun closeConversation(from: Fragment) {
            layer?.close()
        }

        
        override fun canPop(from: Fragment): Boolean = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(pageCallbacks, false)
        lifecycle.addObserver(LifecycleEventObserver { _, _ -> backOwners.refresh() })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context).apply {
            id = R.id.list_layer_stack
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        
        
        val navigator = (activity as? PaneNavigator.Owner)?.paneNavigator
        if (navigator != null && navigator.isDualPane) selectedId = navigator.currentSelectedId
        
        if (savedInstanceState == null && pages().isEmpty()) {
            val page = (requireActivity() as ListPaneLayer.Owner).listLayer.pages.layerRoot(root, rootArgs)
            childFragmentManager.beginTransaction().add(R.id.list_layer_stack, page).commitNow()
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        backOwners.refresh()
    }

    override fun onDestroy() {
        childFragmentManager.unregisterFragmentLifecycleCallbacks(pageCallbacks)
        super.onDestroy()
    }

    
    fun pages(): List<Fragment> {
        if (!isAdded) return emptyList()
        return childFragmentManager.fragments.filter { it.id == R.id.list_layer_stack }
    }

    val depth: Int get() = pages().size

    fun rootPage(): Fragment? = pages().firstOrNull()

    fun topPage(): Fragment? = pages().lastOrNull()

    
    fun popTop(): Boolean {
        if (depth < 2) return false
        popToDepth(depth - 1)
        return true
    }

    private fun popToDepth(targetDepth: Int) {
        val stack = pages()
        if (targetDepth < 1 || targetDepth >= stack.size) return
        val top = stack.last()
        val revealed = stack[targetDepth - 1]
        val tx = childFragmentManager.beginTransaction().setReorderingAllowed(true)
        val between = stack.drop(targetDepth).filter { it !== top }
        between.forEach { tx.remove(it) }
        PageSlide.clear(between)
        PageSlide.pop(top, revealed)
        tx.remove(top).show(revealed).setMaxLifecycle(revealed, Lifecycle.State.RESUMED).commitNow()
        backOwners.refresh()
    }

    




    private fun onPageViewCreated(page: Fragment, view: View) {
        (view as? ViewGroup)?.isTransitionGroup = true
        TopChromeHost.adopt(page, view)
        if (view.background != null || rootPage() === page) return
        view.background = ColorDrawable(ContextCompat.getColor(view.context, com.difft.android.base.R.color.bg1))
    }

    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = backOwners.ownerFor(fragment)

    override fun updateDualPaneSelection(selectedId: String?) {
        this.selectedId = selectedId
        pages().forEach(::forwardSelection)
    }

    
    private fun forwardSelection(page: Fragment) {
        when (page) {
            is SearchSelectionPage -> page.showSelection(selectedId)
            is DualPaneSelectionListener -> page.updateDualPaneSelection(selectedId)
        }
    }

    
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

    companion object {
        const val TAG = "list_layer"
        private const val ARG_ROOT = "root"
        private const val ARG_ROOT_ARGS = "root_args"

        fun newInstance(root: ListPaneLayerRoot, args: Bundle? = null): ListPaneLayerHostFragment =
            ListPaneLayerHostFragment().apply {
                arguments = bundleOf(ARG_ROOT to root.name).also { if (args != null) it.putBundle(ARG_ROOT_ARGS, args) }
            }
    }
}
