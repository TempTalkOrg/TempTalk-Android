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






class PaneNavigatorTestHost : FragmentActivity(), PaneNavigator.Owner, ListPaneLayer.Owner, DualPaneHost {
    lateinit var navigator: PaneNavigator
    override val paneNavigator: PaneNavigator get() = navigator

    
    override lateinit var listLayer: ListPaneLayer
        private set
    var layerActiveChanges = 0

    
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

            
            override fun onCurrentDetailEmptied() {
                emptiedCalls++
                listCollapsed = false
            }

            
            override fun onSelectTabRequested(tab: Int) {
                requestedTabs += tab
                if (::listLayer.isInitialized) listLayer.close()
                onPageSelected(tab)
            }
        })
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, TestPages, navigator.detailCoversList) { layerActiveChanges++ }
        listLayer.attach(savedInstanceState)
        
        if (dualPane) paneImeRouter = PaneImeRouter(findViewById<ViewGroup>(android.R.id.content).getChildAt(0)).also { it.attach() }
        
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
        
        lookupDuringCreate = navigator.currentConversationRoot()
    }

    
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
        
        var listCollapsed = false
    }

    object TestPages : PaneNavigator.Pages, ListPaneLayerHostFragment.Pages {
        override fun conversationRoot(target: ConversationTarget): Fragment =
            StackPageFragment.newInstance("conv:${target.id}:${target.jumpTimestamp ?: 0}")

        
        override fun layerRoot(root: ListPaneLayerRoot): Fragment = layerRoot(root, null)

        override fun layerRoot(root: ListPaneLayerRoot, args: Bundle?): Fragment = when (root) {
            ListPaneLayerRoot.SEARCH -> StackPageFragment.newInstance("search", handlesBack = false)
            ListPaneLayerRoot.FOLDED_GROUPS -> FoldedStandInFragment()
            
            ListPaneLayerRoot.CREATE_GROUP ->
                StackPageFragment.newInstance("create:" + args?.getStringArrayList(CREATE_IDS).orEmpty().joinToString(","), handlesBack = false)
        }

        const val CREATE_IDS = "ids"

        override fun usesSettingsBackground(page: Fragment): Boolean = false


        override fun conversationLayerBackground(context: Context, layer: View): Drawable = ColorDrawable(2)
    }
}






class StackPageFragment : Fragment(), ReopenableConversationPage, SearchSelectionPage, UncoverableSearchPage {
    var reopens = 0
        private set

    
    var uncovers = 0
        private set

    override fun onUncovered() {
        uncovers++
    }

    
    var covers = 0
        private set

    override fun onCovered() {
        covers++
    }

    override fun onConversationReopened() {
        reopens++
    }

    
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
        FrameLayout(inflater.context).apply {
            addView(View(context), ViewGroup.LayoutParams(40, 40))
            addView(View(context), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 80))
        }

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

        
        fun newInstance(label: String, handlesBack: Boolean = true): StackPageFragment =
            StackPageFragment().apply { arguments = bundleOf(ARG_LABEL to label, ARG_HANDLES_BACK to handlesBack) }
    }
}






class FoldedStandInFragment : Fragment(), DualPaneSelectionListener {
    
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
        
        val folding = MutableStateFlow(true)
    }
}
