package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.difft.android.R
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.ReopenableConversationPage
import com.difft.android.base.utils.pane.UncoverableSearchPage
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.chat.recent.DualPaneSelectionListener
import com.difft.android.search.SearchSelectionPage
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Test double for IndexActivity (a Hilt entry point the app's Robolectric tests never boot): it
 * inflates the real `activity_index.xml`, picks the mode from the same resource gate, reports it as
 * a [DualPaneHost], and wires a real [PaneNavigator] with plain [StackPageFragment] pages.
 */
class PaneNavigatorTestHost : FragmentActivity(), PaneNavigator.Owner, ListPaneLayer.Owner, DualPaneHost {
    lateinit var navigator: PaneNavigator
    override val paneNavigator: PaneNavigator get() = navigator

    // The list pane's layer, as IndexActivity owns it next to the navigator.
    override lateinit var listLayer: ListPaneLayer
        private set
    var layerActiveChanges = 0

    // Like IndexActivity, the host reports its layout mode to the pages.
    override val isDualPaneMode: Boolean get() = navigator.isDualPane
    override val homeTabBarObstructionPx: StateFlow<Int> = MutableStateFlow(0)
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit

    internal var paneImeRouter: PaneImeRouter? = null
        private set

    override fun onDestroy() {
        paneImeRouter?.detach()
        paneImeRouter = null
        super.onDestroy()
    }

    var emptiedCalls = 0
    var stateChangedCalls = 0
    var moveTaskToBackCalls = 0
    val requestedTabs = mutableListOf<Int>()
    var lookupDuringCreate: Fragment? = null
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_index)
        val dualPane = resources.getBoolean(R.bool.dual_pane_layout_active)
        navigator = PaneNavigator(this, dualPane, TestPages, object : PaneNavigator.Listener {
            override fun onDetailStateChanged() {
                stateChangedCalls++
            }

            // As IndexActivity: an emptied two-pane stack re-opens a collapsed list.
            override fun onCurrentDetailEmptied() {
                emptiedCalls++
                listCollapsed = false
            }

            // As IndexActivity: a requested tab switch leaves the list-pane layer like a rail tap.
            override fun onSelectTabRequested(tab: Int) {
                requestedTabs += tab
                if (::listLayer.isInitialized) listLayer.close()
                onPageSelected(tab)
            }
        })
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, TestPages, navigator.detailCoversList) { layerActiveChanges++ }
        listLayer.attach(savedInstanceState)
        // Two-pane: keyboard insets reach only the pane holding the focus, as in IndexActivity.
        if (dualPane) paneImeRouter = PaneImeRouter(findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).also { it.attach() }
        // The same single Activity-level Back callback IndexActivity registers in onCreate.
        onBackPressedDispatcher.addCallback(
            this,
            IndexBackCallback(
                navigator = navigator,
                listLayer = listLayer,
                isListCollapsed = { navigator.isDualPane && listCollapsed },
                expandList = { listCollapsed = false },
                moveTaskToBack = { moveTaskToBackCalls++ },
            )
        )
        // What IndexActivity's provider lookups see while fragments are still restoring.
        lookupDuringCreate = navigator.currentConversationRoot()
    }

    /** What ViewPager2 does for `setCurrentItem(tab, false)`: dispatches the selection at once. */
    private fun onPageSelected(tab: Int) {
        navigator.selectTab(tab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigator.saveState(outState)
        listLayer.saveState(outState)
    }

    fun host(tab: Int): DetailTabHostFragment? =
        supportFragmentManager.findFragmentByTag(PaneNavigator.tagFor(tab)) as? DetailTabHostFragment

    fun pages(tab: Int): List<StackPageFragment> = host(tab)?.pages().orEmpty().map { it as StackPageFragment }

    companion object {
        /** Stands in for the persisted DualPaneCollapseUtil flag, which outlives a recreation. */
        var listCollapsed = false
    }

    object TestPages : PaneNavigator.Pages, ListPaneLayerHostFragment.Pages {
        override fun conversationRoot(target: ConversationTarget): Fragment =
            StackPageFragment.newInstance("conv:${target.id}:${target.jumpTimestamp ?: 0}")

        // Like GlobalSearchFragment, the search root registers no Back callback of its own.
        override fun layerRoot(root: ListPaneLayerRoot): Fragment = when (root) {
            ListPaneLayerRoot.SEARCH -> StackPageFragment.newInstance("search", handlesBack = false)
            ListPaneLayerRoot.FOLDED_GROUPS -> FoldedStandInFragment()
        }

        override fun usesSettingsBackground(page: Fragment): Boolean = false


        override fun conversationLayerBackground(context: Context, layer: View): Drawable = ColorDrawable(2)
    }
}

/**
 * A page that registers an Activity-dispatcher back callback with [detailBackOwner] and an
 * Activity-root overlay with [dismissWhenLeavingVisibleTop], exactly as hosted pages do, and
 * counts the presses and dismissals it receives.
 */
class StackPageFragment : Fragment(), ReopenableConversationPage, SearchSelectionPage, UncoverableSearchPage {
    var reopens = 0
        private set

    /** Times the search layer told this page the conversation layer over it popped (D1). */
    var uncovers = 0
        private set

    override fun onUncovered() {
        uncovers++
    }

    /** Times the search layer told this page a conversation layer covered it (S-3). */
    var covers = 0
        private set

    override fun onCovered() {
        covers++
    }

    override fun onConversationReopened() {
        reopens++
    }

    /** Every selection the search layer host forwarded to this page, in order. */
    val selections = mutableListOf<String?>()

    override fun showSelection(selectedId: String?) {
        selections += selectedId
    }

    val label: String get() = requireArguments().getString(ARG_LABEL).orEmpty()
    var backPresses = 0
        private set
    var resumes = 0
        private set

    override fun onResume() {
        super.onResume()
        resumes++
    }
    var overlayDismissals = 0
        private set
    lateinit var backOwner: LifecycleOwner
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        backOwner = detailBackOwner()
        requireActivity().onBackPressedDispatcher.addCallback(
            backOwner,
            object : OnBackPressedCallback(requireArguments().getBoolean(ARG_HANDLES_BACK, true)) {
                override fun handleOnBackPressed() {
                    backPresses++
                }
            }
        )
        dismissWhenLeavingVisibleTop { overlayDismissals++ }
    }

    companion object {
        private const val ARG_LABEL = "label"
        private const val ARG_HANDLES_BACK = "handles_back"

        /** [handlesBack] false: the page's callback is registered but disabled (nothing to close). */
        fun newInstance(label: String, handlesBack: Boolean = true): StackPageFragment =
            StackPageFragment().apply { arguments = bundleOf(ARG_LABEL to label, ARG_HANDLES_BACK to handlesBack) }
    }
}

/**
 * Stand-in for the folded-groups root: a chat-list page ([DualPaneSelectionListener]) that pops
 * itself, from a collector started in `onCreate` that waits for STARTED, as soon as [folding] is
 * false, like FoldedGroupsFragment does when the focus stops folding.
 */
class FoldedStandInFragment : Fragment(), DualPaneSelectionListener {
    /** Every selection the layer host forwarded to this page, in order. */
    val selections = mutableListOf<String?>()

    override fun updateDualPaneSelection(selectedId: String?) {
        selections += selectedId
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            lifecycle.withStarted {}
            folding.collect { if (!it && isAdded) detailNavigation().pop(this@FoldedStandInFragment) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context)

    companion object {
        /** The focus folds the other groups; tests reset it to true. */
        val folding = MutableStateFlow(true)
    }
}
