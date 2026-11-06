package com.difft.android.dualpane

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

/**
 * The list pane's layer inside IndexActivity's `list_layer`: its child FragmentManager holds the
 * layer's pages in stack order, root first (built by [Pages.layerRoot] for [root], which the
 * host's arguments carry so a recreation restores it), then the pages it pushes (message search,
 * More lists). Pushes add + hide + cap like [DetailTabHostFragment]; a pop at the root closes the
 * layer through [ListPaneLayer]. A row opens its conversation in the current tab's detail stack
 * ([ConversationNavigationCallback], router rule 1); the detail pane's selection comes back
 * through [DualPaneSelectionListener] and is forwarded to every page, so the visible one shows it.
 */
internal class ListPaneLayerHostFragment : Fragment(), DetailStackHost, DualPaneSelectionListener {

    /** Builds the layer's root page; kept out of the host so tests can use plain fragments. */
    interface Pages {
        fun layerRoot(root: ListPaneLayerRoot): Fragment
    }

    /** The root kind this host was added with. */
    val root: ListPaneLayerRoot
        get() = arguments?.getString(ARG_ROOT)?.let { name -> ListPaneLayerRoot.entries.firstOrNull { it.name == name } }
            ?: ListPaneLayerRoot.SEARCH

    private val layer: ListPaneLayer?
        get() = (activity as? ListPaneLayer.Owner)?.listLayer

    private var selectedId: String? = null
    private val backOwners = PageBackOwners(::pageOf, ::isVisibleTop)

    private val pageCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            if (f.id == R.id.list_layer_stack) forwardSelection(f)
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

        /** The root closes the layer and a pushed page pops: both have somewhere to go back to. */
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
        // A conversation already open when the layer opens (or restored with it) is highlighted at
        // once, before any page view exists; later changes arrive through updateDualPaneSelection.
        // Two-pane only, like the list. The navigator exists: the Activity attached it in onCreate.
        val navigator = (activity as? PaneNavigator.Owner)?.paneNavigator
        if (navigator != null && navigator.isDualPane) selectedId = navigator.currentSelectedId
        // A recreation restores the stack from the child FragmentManager; only a fresh host builds the root.
        if (savedInstanceState == null && pages().isEmpty()) {
            val page = (requireActivity() as ListPaneLayer.Owner).listLayer.pages.layerRoot(root)
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

    /** The layer's pages, root first. Empty before the host is attached. */
    fun pages(): List<Fragment> {
        if (!isAdded) return emptyList()
        return childFragmentManager.fragments.filter { it.id == R.id.list_layer_stack }
    }

    val depth: Int get() = pages().size

    fun rootPage(): Fragment? = pages().firstOrNull()

    fun topPage(): Fragment? = pages().lastOrNull()

    /** Back on a pushed page: removes the top and reveals the page under it. False at the root. */
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
        stack.drop(targetDepth).filter { it !== top }.forEach { tx.remove(it) }
        tx.remove(top).show(revealed).setMaxLifecycle(revealed, Lifecycle.State.RESUMED).commitNow()
        backOwners.refresh()
    }

    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = backOwners.ownerFor(fragment)

    override fun updateDualPaneSelection(selectedId: String?) {
        this.selectedId = selectedId
        pages().forEach(::forwardSelection)
    }

    /** Search pages show the selection through [SearchSelectionPage], chat-list pages through [DualPaneSelectionListener]. */
    private fun forwardSelection(page: Fragment) {
        when (page) {
            is SearchSelectionPage -> page.showSelection(selectedId)
            is DualPaneSelectionListener -> page.updateDualPaneSelection(selectedId)
        }
    }

    /** The page (direct child of this host) that [fragment] belongs to. */
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

        fun newInstance(root: ListPaneLayerRoot): ListPaneLayerHostFragment =
            ListPaneLayerHostFragment().apply { arguments = bundleOf(ARG_ROOT to root.name) }
    }
}
