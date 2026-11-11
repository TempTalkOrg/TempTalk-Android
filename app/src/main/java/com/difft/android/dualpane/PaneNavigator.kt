package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.core.os.BundleCompat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.ReopenableConversationPage
import com.difft.android.base.utils.pane.RemovedConversations
import com.difft.android.base.utils.pane.VisibleDetailStack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow













class PaneNavigator(
    private val activity: FragmentActivity,
    val isDualPane: Boolean,
    private val pages: Pages,
    private val listener: Listener,
) {
    



    interface Owner : VisibleDetailStack {
        val paneNavigator: PaneNavigator

        override fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean =
            paneNavigator.pushOnVisibleStack(destination, overConversation)
    }

    
    interface Pages {
        fun conversationRoot(target: ConversationTarget): Fragment
        fun usesSettingsBackground(page: Fragment): Boolean

        




        fun conversationLayerBackground(context: Context, layer: View): Drawable
    }

    interface Listener {
        
        fun onDetailStateChanged()

        
        fun onCurrentDetailEmptied()

        
        fun onSelectTabRequested(tab: Int)
    }

    
    sealed interface Origin {
        data class Tab(val tab: Int) : Origin
        data object External : Origin
    }

    private val fm: FragmentManager get() = activity.supportFragmentManager
    private val handler = Handler(Looper.getMainLooper())
    internal val chrome = PaneChrome(activity, isDualPane, pages)
    private val pending = ArrayDeque<() -> Unit>()
    private var draining = false
    private var drainPosted = false
    private var derivePosted = false
    private val drainRunnable = Runnable {
        drainPosted = false
        drain()
    }
    private val deriveRunnable = Runnable {
        derivePosted = false
        derive()
    }

    var currentTab: Int = TAB_CHATS
        private set

    private val coversList = MutableStateFlow(false)

    
    val detailCoversList: StateFlow<Boolean> = coversList.asStateFlow()

    
    var currentSelectedId: String? = null
        private set

    private var derivedOnce = false
    private var lastDerivedTab = -1
    private var lastDerivedDepth = 0

    
    
    private var destroyed = false

    
    
    private var operationRan = false

    
    private class PendingClose(val tab: Int, val root: Fragment?)
    private var pendingClose: PendingClose? = null

    
    
    
    private val drainCloses = mutableListOf<PendingClose>()

    
    private var pendingExternal: ConversationTarget? = null

    




    fun attach(savedInstanceState: Bundle?) {
        currentTab = savedInstanceState?.getInt(STATE_CURRENT_TAB, TAB_CHATS) ?: TAB_CHATS
        restorePresentedTabs(savedInstanceState)
        live = WeakReference(this)
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> if (pending.isNotEmpty()) scheduleDrain()
                    Lifecycle.Event.ON_DESTROY -> {
                        destroyed = true
                        if (live?.get() === this) live = null
                        pending.clear()
                        drainCloses.clear()
                        handler.removeCallbacks(drainRunnable)
                        handler.removeCallbacks(deriveRunnable)
                    }
                    else -> Unit
                }
            }
        )
        if (savedInstanceState != null) {
            
            
            applyTabCaps()
            L.i { "[PaneNav] restored tab=$currentTab depth=${depth()} dualPane=$isDualPane" }
        }
        
        clearRemovedConversationsNow()
        derive()
        
        savedInstanceState?.let { BundleCompat.getParcelable(it, STATE_PENDING_OPEN, ConversationTarget::class.java) }
            ?.let { target -> handler.post { openConversation(target, Origin.External) } }
    }

    



    fun saveState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_TAB, currentTab)
        outState.putBoolean(STATE_WAS_DUAL_PANE, isDualPane)
        outState.putIntArray(STATE_PRESENTED_TABS, presentedInOnePane.toIntArray())
        pendingExternal?.let { outState.putParcelable(STATE_PENDING_OPEN, it) }
    }

    
    
    internal var requestVersion: Long = 0
        private set

    fun selectTab(tab: Int) {
        if (tab == currentTab) return
        requestVersion++
        currentTab = tab
        submit { applyTabCaps() }
    }

    




    fun openConversation(target: ConversationTarget, origin: Origin) {
        if (destroyed) return
        requestVersion++
        
        RemovedConversations.remove(target.id)
        
        val tab = if (origin is Origin.Tab && origin.tab != TAB_ME) origin.tab else TAB_CHATS
        if (tab == TAB_CHATS && currentTab != TAB_CHATS) listener.onSelectTabRequested(TAB_CHATS)
        if (origin == Origin.External) pendingExternal = target
        cancelPendingClose(tab)
        submit {
            
            cancelPendingClose(tab)
            if (origin == Origin.External && pendingExternal == target) pendingExternal = null
            TABS.filter { it != tab && hostFor(it)?.rootPage()?.conversationId == target.id }
                .forEach { clearNow(it) }
            val host = obtainHost(tab)
            val sameRoot = host.rootPage()?.conversationId == target.id
            if (sameRoot && !target.hasJump) {
                popAboveRootNow(host)
                
                (host.rootPage() as? ReopenableConversationPage)?.onConversationReopened()
            } else {
                replaceRootNow(host, pages.conversationRoot(target), selectionId = target.id, conversationId = target.id)
            }
            presentNow(tab)
            L.i { "[PaneNav] open kind=${target.kind} tab=$tab sameRoot=$sameRoot jump=${target.hasJump}" }
        }
    }

    
    fun replaceRoot(tab: Int, page: Fragment, selectionId: String?) {
        requestVersion++
        cancelPendingClose(tab)
        
        selectionId?.let { RemovedConversations.remove(it) }
        submit {
            cancelPendingClose(tab)
            replaceRootNow(obtainHost(tab), page, selectionId = selectionId, conversationId = null)
            presentNow(tab)
        }
    }

    















    fun push(tab: Int, page: Fragment, subjectId: String? = null, pageKey: String? = null): Boolean {
        if (destroyed) return false
        requestVersion++
        if (!isDualPane && !presented(tab)) {
            L.w { "[PaneNav] push refused tab=$tab not presented" }
            return false
        }
        cancelPendingClose(tab)
        subjectId?.let { RemovedConversations.remove(it) }
        PaneSubjects.setIdentity(page, subjectId, pageKey)
        submit {
            if (!isDualPane && !presented(tab)) {
                L.w { "[PaneNav] push dropped tab=$tab not presented" }
                return@submit
            }
            val host = obtainHost(tab)
            val top = host.topPage()
            if (pageKey != null && top != null && PaneSubjects.pageKeyOf(top) == pageKey) {
                L.i { "[PaneNav] duplicate push dropped tab=$tab" }
                return@submit
            }
            
            cancelPendingClose(tab)
            if (top == null) {
                replaceRootNow(host, page, selectionId = null, conversationId = null, subjectId = subjectId)
            } else {
                val tx = host.childFragmentManager.beginTransaction()
                
                if (slides()) PageSlide.push(page, top) else PageSlide.clear(listOf(page, top))
                tx.setReorderingAllowed(true)
                    .add(R.id.detail_tab_stack, page)
                    .hide(top)
                    .setMaxLifecycle(top, Lifecycle.State.STARTED)
                    .commitNow()
                host.refreshBackOwners()
            }
            presentNow(tab)
        }
        return true
    }

    
    internal fun isShownTab(tab: Int): Boolean = tab == currentTab && presented(tab)

    




    fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean {
        if (destroyed || overConversation == null) return false
        if (presentedHost(currentTab)?.rootPage()?.conversationId != overConversation) {
            L.w { "[PaneNav] sheet push refused tab=$currentTab root changed" }
            return false
        }
        return push(currentTab, destination.newFragment(), destination.subjectId, destination.pageKey)
    }

    
    internal fun slides(): Boolean = !destroyed

    




    internal fun popSlides(userPop: Boolean): Boolean = userPop && !destroyed

    
    fun showMePage(page: Fragment): Boolean {
        if (destroyed) return false
        requestVersion++
        if (currentTab != TAB_ME) listener.onSelectTabRequested(TAB_ME)
        cancelPendingClose(TAB_ME)
        submit {
            cancelPendingClose(TAB_ME)
            val host = obtainHost(TAB_ME)
            if (PaneStackOperations.sameMeRoot(host.rootPage(), page)) popAboveRootNow(host)
            else replaceRootNow(host, page, selectionId = null, conversationId = null)
            presentNow(TAB_ME)
            L.i { "[PaneNav] me root depth=${host.depth} dualPane=$isDualPane" }
        }
        return true
    }

    
    fun openMePage(
        keepExisting: (List<Fragment>) -> Boolean,
        createPage: () -> Fragment,
        shouldOpen: () -> Boolean = { true },
        onPresented: () -> Unit = {},
        expectedRequestVersion: Long? = null,
    ) {
        if (destroyed || !shouldOpen()) return
        if (expectedRequestVersion != null && expectedRequestVersion != requestVersion) return logMePageDropped("request", expectedRequestVersion, requestVersion)
        if (currentTab != TAB_ME) listener.onSelectTabRequested(TAB_ME)
        val version = ++requestVersion
        cancelPendingClose(TAB_ME)
        submit {
            if (version != requestVersion) return@submit logMePageDropped("queued", version, requestVersion) else if (!shouldOpen()) return@submit
            cancelPendingClose(TAB_ME)
            val host = obtainHost(TAB_ME)
            if (!keepExisting(host.pages())) {
                replaceRootNow(host, createPage(), selectionId = null, conversationId = null)
            }
            presentNow(TAB_ME)
            onPresented()
        }
    }

    
    fun retainedMePages(): List<Fragment> = hostFor(TAB_ME)?.pages().orEmpty()

    fun pop(tab: Int): Boolean = pop(tab, 1)

    
    internal fun pop(tab: Int, count: Int, from: Fragment? = presentedHost(tab)?.topPage()): Boolean {
        if (count <= 0 || from == null || !isShownTab(tab) || !canPop(tab)) return false
        if (hostFor(tab)?.topPage() !== from) return false
        requestVersion++
        submit {
            val host = hostFor(tab) ?: return@submit
            if (!isShownTab(tab) || host.topPage() !== from) {
                L.i { "[PaneNav] stale pop dropped tab=$tab count=$count" }
                return@submit
            }
            val targetDepth = (host.depth - count).coerceAtLeast(if (isDualPane) 1 else 0)
            if (targetDepth == 0) clear(tab)
            else PaneStackOperations.popToDepth(host, targetDepth, slides = popSlides(userPop = true))
        }
        return true
    }

    
    fun clear(tab: Int) {
        if (destroyed) return
        requestVersion++
        if (!isDualPane && tab == currentTab && depth(tab) > 0) {
            if (pendingClose != null) return
            val close = PendingClose(tab, hostFor(tab)?.rootPage())
            pendingClose = close
            chrome.closeLayer {
                
                
                if (pendingClose !== close) return@closeLayer
                submit {
                    if (pendingClose === close) {
                        pendingClose = null
                        if (hostFor(tab)?.rootPage() === close.root) clearNow(tab)
                        
                        
                        if (depth(currentTab) > 0) chrome.cancelClose()
                    }
                }
            }
        } else {
            submit { clearNow(tab) }
        }
    }

    





    fun closeSubject(tab: Int, subjectId: String?) {
        requestVersion++
        if (destroyed) return
        if (subjectId == null) {
            clear(tab)
            return
        }
        submit {
            val host = hostFor(tab) ?: return@submit
            when (val index = PaneSubjects.lowestIndex(host, subjectId)) {
                -1 -> Unit
                
                
                
                0 -> if (!isDualPane && tab == currentTab && depth(tab) > 0 && pendingClose.let { it == null || it.tab == tab }) {
                    clear(tab)
                } else {
                    drainCloses += PendingClose(tab, host.rootPage())
                }
                else -> {
                    L.i { "[PaneNav] close subject tab=$tab from=$index depth=${host.depth}" }
                    PaneSubjects.closeFromNow(host, index)
                }
            }
        }
    }

    
    private fun cancelPendingClose(tab: Int) {
        if (drainCloses.removeAll { it.tab == tab }) L.i { "[PaneNav] queued close cancelled by a newer page tab=$tab" }
        if (pendingClose?.tab != tab) return
        pendingClose = null
        chrome.cancelClose()
        L.i { "[PaneNav] close cancelled by a newer page" }
    }

    




    private fun clearRemovedConversationsNow() {
        for (id in RemovedConversations.snapshot()) {
            TABS.filter { pendingClose?.tab != it }.forEach { tab ->
                val host = hostFor(tab) ?: return@forEach
                val index = PaneSubjects.lowestIndex(host, id)
                if (index < 0) return@forEach
                L.i { "[PaneNav] clear removed subject tab=$tab from=$index" }
                if (index == 0) clearNow(tab) else PaneSubjects.closeFromNow(host, index)
            }
            if (TABS.none { PaneSubjects.shows(hostFor(it), id) }) RemovedConversations.remove(id)
        }
    }

    
    fun closeConversationById(conversationId: String) {
        requestVersion++
        if (destroyed) return
        val tabs = TABS.filter { PaneSubjects.shows(hostFor(it), conversationId) }
        if (tabs.isNotEmpty()) L.i { "[PaneNav] close removed subject tabs=$tabs" }
        tabs.forEach { closeSubject(it, conversationId) }
    }

    fun canPop(tab: Int): Boolean {
        val depth = depth(tab)
        return depth > 1 || (!isDualPane && depth > 0)
    }

    
    fun depth(tab: Int = currentTab): Int = presentedHost(tab)?.depth ?: 0

    






    private fun presented(tab: Int): Boolean = isDualPane || tab in presentedInOnePane

    
    private val presentedInOnePane = mutableSetOf<Int>()

    private fun restorePresentedTabs(savedInstanceState: Bundle?) {
        if (isDualPane || savedInstanceState == null) return
        
        val tabs = if (savedInstanceState.getBoolean(STATE_WAS_DUAL_PANE, false)) {
            listOf(currentTab)
        } else {
            savedInstanceState.getIntArray(STATE_PRESENTED_TABS)?.toList().orEmpty()
        }
        presentedInOnePane += tabs 
    }

    




    private fun presentNow(tab: Int) {
        if (isDualPane) return
        if (presentedInOnePane.add(tab)) applyTabCaps()
    }

    private fun presentedHost(tab: Int): DetailTabHostFragment? = hostFor(tab)?.takeIf { presented(tab) }

    
    fun currentConversationRoot(): Fragment? =
        presentedHost(currentTab)?.rootPage()?.takeIf { it.conversationId != null }

    
    fun currentConversationOnScreen(): String? {
        val host = presentedHost(currentTab) ?: return null
        if (!host.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return null
        val stack = host.pages()
        return stack.singleOrNull()?.conversationId
    }

    internal fun onHostStackChanged() {
        if (destroyed || derivePosted) return
        derivePosted = true
        handler.post(deriveRunnable)
    }

    private fun submit(op: () -> Unit) {
        if (destroyed) return
        pending.addLast {
            operationRan = true
            op()
        }
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (drainPosted) return
        drainPosted = true
        handler.post(drainRunnable)
    }

    private fun canCommit(): Boolean = !fm.isStateSaved && !fm.isDestroyed

    
    private fun runDrainCloses() {
        val closes = drainCloses.toList()
        drainCloses.clear()
        closes.forEach { if (hostFor(it.tab)?.rootPage() === it.root) clearNow(it.tab) }
    }

    private fun drain() {
        if (destroyed || draining || pending.isEmpty()) return
        if (!canCommit()) {
            L.i { "[PaneNav] queued until start pending=${pending.size}" }
            return
        }
        draining = true
        try {
            while (pending.isNotEmpty() && canCommit()) pending.removeFirst().invoke()
            if (pending.isEmpty() && canCommit()) runDrainCloses()
            clearRemovedConversationsNow()
        } finally {
            draining = false
        }
        derive()
    }

    private fun derive() {
        if (destroyed || activity.isDestroyed) return
        val stack = presentedHost(currentTab)?.pages().orEmpty()
        val depth = stack.size
        val top = stack.lastOrNull()
        val root = stack.firstOrNull()
        chrome.apply(
            tab = currentTab,
            top = top,
            root = root,
            depth = depth,
            topIsConversation = top != null && top === root && top.conversationId != null,
            rootIsConversation = root?.conversationId != null,
            animate = derivedOnce,
        )
        val covers = !isDualPane && depth > 0
        val coversChanged = covers != coversList.value
        coversList.value = covers
        val selected = stack.firstOrNull()?.arguments?.getString(ARG_SELECTION_ID)
        val changed = !derivedOnce || coversChanged || selected != currentSelectedId || currentTab != lastDerivedTab || depth != lastDerivedDepth
        val emptied = derivedOnce && operationRan && isDualPane && depth == 0 &&
            (currentTab != lastDerivedTab || lastDerivedDepth > 0)
        operationRan = false
        currentSelectedId = selected
        lastDerivedTab = currentTab
        lastDerivedDepth = depth
        derivedOnce = true
        if (emptied) listener.onCurrentDetailEmptied()
        if (changed) listener.onDetailStateChanged()
    }

    private fun applyTabCaps() {
        val hosts = TABS.mapNotNull { tab -> hostFor(tab)?.let { tab to it } }
        if (hosts.isEmpty()) return
        val tx = fm.beginTransaction()
        hosts.forEach { (tab, host) ->
            if (tab == currentTab && presented(tab)) {
                tx.show(host).setMaxLifecycle(host, Lifecycle.State.RESUMED)
            } else {
                tx.hide(host).setMaxLifecycle(host, Lifecycle.State.STARTED)
            }
        }
        tx.commitNow()
    }

    private fun hostFor(tab: Int): DetailTabHostFragment? =
        fm.findFragmentByTag(tagFor(tab)) as? DetailTabHostFragment

    private fun obtainHost(tab: Int): DetailTabHostFragment {
        hostFor(tab)?.let { return it }
        val host = DetailTabHostFragment.newInstance(tab)
        val tx = fm.beginTransaction().add(R.id.fragment_container_detail, host, tagFor(tab))
        if (tab != currentTab || !presented(tab)) tx.hide(host).setMaxLifecycle(host, Lifecycle.State.STARTED)
        tx.commitNow()
        return host
    }

    private fun replaceRootNow(
        host: DetailTabHostFragment,
        page: Fragment,
        selectionId: String?,
        conversationId: String?,
        subjectId: String? = selectionId,
    ) {
        page.arguments = (page.arguments ?: Bundle()).apply {
            putString(ARG_SELECTION_ID, selectionId)
            putString(ARG_CONVERSATION_ID, conversationId)
        }
        PaneSubjects.setSubject(page, subjectId)
        
        PageSlide.clear(host.pages() + page)
        host.childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.detail_tab_stack, page)
            .commitNow()
        host.refreshBackOwners()
    }

    
    private fun popAboveRootNow(host: DetailTabHostFragment) {
        val stack = host.pages()
        if (stack.size < 2) return
        val root = stack.first()
        PageSlide.clear(stack)
        val tx = host.childFragmentManager.beginTransaction().setReorderingAllowed(true)
        stack.drop(1).forEach { tx.remove(it) }
        tx.show(root).setMaxLifecycle(root, Lifecycle.State.RESUMED).commitNow()
        host.refreshBackOwners()
    }

    private fun clearNow(tab: Int) {
        val host = hostFor(tab) ?: return
        val remaining = host.pages()
        if (remaining.isEmpty()) return
        PageSlide.clear(remaining)
        val tx = host.childFragmentManager.beginTransaction()
        remaining.forEach { tx.remove(it) }
        tx.commitNow()
        host.refreshBackOwners()
    }

    






    internal fun onPageViewCreated(host: DetailTabHostFragment, page: Fragment, view: View) {
        if (destroyed) return
        (view as? ViewGroup)?.isTransitionGroup = true
        if (view.background != null || host.rootPage() === page) return
        view.background = chrome.pageBackground(host.tab, page)
    }

    private val Fragment.conversationId: String? get() = arguments?.getString(ARG_CONVERSATION_ID)

    companion object {
        const val TAB_CHATS = 0
        const val TAB_CONTACTS = 1
        const val TAB_ME = 2
        private val TABS = listOf(TAB_CHATS, TAB_CONTACTS, TAB_ME)

        private const val STATE_CURRENT_TAB = "pane_navigator_current_tab"
        private const val STATE_PENDING_OPEN = "pane_navigator_pending_open"
        private const val STATE_WAS_DUAL_PANE = "pane_navigator_was_dual_pane"
        private const val STATE_PRESENTED_TABS = "pane_navigator_presented_tabs"
        private const val ARG_SELECTION_ID = "pane_nav_selection_id"
        private const val ARG_CONVERSATION_ID = "pane_nav_conversation_id"

        fun tagFor(tab: Int): String = "detail_tab_$tab"

        
        @Volatile
        private var live: WeakReference<PaneNavigator>? = null

        
        fun closeConversationEverywhere(conversationId: String) {
            live?.get()?.closeConversationById(conversationId)
        }
    }
}


private fun logMePageDropped(stage: String, expected: Long, current: Long) =
    L.w { "[PaneNav] me_page_dropped stage=$stage expected=$expected current=$current" }
