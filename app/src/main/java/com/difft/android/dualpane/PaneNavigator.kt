package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.core.os.BundleCompat
import android.os.Handler
import android.os.Looper
import android.view.View
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

/**
 * IndexActivity's detail navigation: one [DetailTabHostFragment] per home tab in
 * `fragment_container_detail` (tag `detail_tab_<tab>`), each holding that tab's page stack. The
 * same container exists in the one-pane and two-pane layouts, so fold/unfold recreation restores
 * the hosts and their stacks in place; one-pane shows the current stack as a full-window layer
 * over the list, two-pane shows it in the detail pane.
 *
 * Every stack change is queued and run from a posted drain, never inside a FragmentManager
 * dispatch (a page may navigate from its own lifecycle callbacks), and only while state is not
 * saved; requests made after `onSaveInstanceState` wait for the next start/resume. Within a drain
 * the transactions execute synchronously, so each step reads the executed stack.
 */
class PaneNavigator(
    private val activity: FragmentActivity,
    val isDualPane: Boolean,
    private val pages: Pages,
    private val listener: Listener,
) {
    /**
     * Implemented by the Activity that owns the navigator; hosts find it through this, and sheets
     * shown on that Activity push through it ([VisibleDetailStack]).
     */
    interface Owner : VisibleDetailStack {
        val paneNavigator: PaneNavigator

        override fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean =
            paneNavigator.pushOnVisibleStack(destination, overConversation)
    }

    /** The concrete pages and backgrounds; kept out of the navigator so tests can use plain fragments. */
    interface Pages {
        fun conversationRoot(target: ConversationTarget): Fragment
        fun usesSettingsBackground(page: Fragment): Boolean

        /**
         * Detail layer behind a conversation, in either layout: it reaches the window bottom, so it
         * paints the navigation-bar strip like ChatActivity's window (the status-bar strip is the root's).
         */
        fun conversationLayerBackground(context: Context, layer: View): Drawable
    }

    interface Listener {
        /** The current tab, its root page or [detailCoversList] changed (list highlight, startup prompts). */
        fun onDetailStateChanged()

        /** Two-pane: the shown stack became empty, so a collapsed list must open again. */
        fun onCurrentDetailEmptied()

        /** An external entry needs [tab] selected in the pager first. */
        fun onSelectTabRequested(tab: Int)
    }

    /** Where an open request comes from: a tab's own page, or outside the tabs (notification, link). */
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

    /** One-pane only: the detail layer is up and covers the list. */
    val detailCoversList: StateFlow<Boolean> = coversList.asStateFlow()

    /** Id the current tab's root page was opened for (conversation or contact); list highlight. */
    var currentSelectedId: String? = null
        private set

    private var derivedOnce = false
    private var lastDerivedTab = -1
    private var lastDerivedDepth = 0

    // Set at ON_DESTROY: fragments are torn down after it and still call back; a dead navigator
    // must not touch views, flows or the listener (it would expand the new instance's list).
    private var destroyed = false

    // A stack change made by an operation (not restore or teardown) is waiting for derive();
    // only those may report the shown stack as emptied.
    private var operationRan = false

    /** One-pane Back slid the layer out to clear this root; a newer page cancels it. */
    private class PendingClose(val tab: Int, val root: Fragment?)
    private var pendingClose: PendingClose? = null

    // Subject closes of a root that run at the end of the drain (no slide), so a later request
    // for that tab in the same drain (reopen, push, replace) can still cancel them, as it cancels
    // the one-pane slide.
    private val drainCloses = mutableListOf<PendingClose>()

    // An external open not yet executed; saved with the state so a recreation still opens it.
    private var pendingExternal: ConversationTarget? = null

    /**
     * Call right after `setContentView`. Restores the current tab before ViewPager2 restores its
     * own item, so lookups made while fragments restore read the right tab's host. Every tab uses the same
     * one-pane presentation rule, see [presented].
     */
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
            // onCreate runs outside any FragmentManager dispatch, so the restore fix-up runs now.
            // Restored stacks obey the saved presentation state before their pages are resumed.
            applyTabCaps()
            L.i { "[PaneNav] restored tab=$currentTab depth=${depth()} dualPane=$isDualPane" }
        }
        // A removed conversation's close may have been lost with the previous instance.
        clearRemovedConversationsNow()
        derive()
        // Replayed once the owner has finished creating (its tab pager must exist to select Chats).
        savedInstanceState?.let { BundleCompat.getParcelable(it, STATE_PENDING_OPEN, ConversationTarget::class.java) }
            ?.let { target -> handler.post { openConversation(target, Origin.External) } }
    }

    /**
     * Saves the current tab and an external open still queued (the request a recreation must not
     * lose). Other queued changes (a pop or close) are dropped with the old instance.
     */
    fun saveState(outState: Bundle) {
        outState.putInt(STATE_CURRENT_TAB, currentTab)
        outState.putBoolean(STATE_WAS_DUAL_PANE, isDualPane)
        outState.putIntArray(STATE_PRESENTED_TABS, presentedInOnePane.toIntArray())
        pendingExternal?.let { outState.putParcelable(STATE_PENDING_OPEN, it) }
    }

    /** Pager selection: shows that tab's host (RESUMED) and hides the others (capped at STARTED). */
    /** Supersedes delayed external preparation when a newer navigation request arrives. */
    internal var requestVersion: Long = 0
        private set

    fun selectTab(tab: Int) {
        if (tab == currentTab) return
        requestVersion++
        currentTab = tab
        submit { applyTabCaps() }
    }

    /**
     * Opens [target] in the origin tab's stack (external entries use Chats). A conversation lives
     * in at most one stack: another tab holding it is cleared first. Same conversation without a
     * jump pops back to it; with a jump it is replaced, since a jump is read at ViewModel creation.
     */
    fun openConversation(target: ConversationTarget, origin: Origin) {
        if (destroyed) return
        requestVersion++
        // Opened again on purpose: no longer a removed conversation to close.
        RemovedConversations.remove(target.id)
        // A conversation opened from Me belongs to Chats, like an external entry.
        val tab = if (origin is Origin.Tab && origin.tab != TAB_ME) origin.tab else TAB_CHATS
        if (tab == TAB_CHATS && currentTab != TAB_CHATS) listener.onSelectTabRequested(TAB_CHATS)
        if (origin == Origin.External) pendingExternal = target
        cancelPendingClose(tab)
        submit {
            // Again in queue order: a close queued before this open is superseded by it.
            cancelPendingClose(tab)
            if (origin == Origin.External && pendingExternal == target) pendingExternal = null
            TABS.filter { it != tab && hostFor(it)?.rootPage()?.conversationId == target.id }
                .forEach { clearNow(it) }
            val host = obtainHost(tab)
            val sameRoot = host.rootPage()?.conversationId == target.id
            if (sameRoot && !target.hasJump) {
                popAboveRootNow(host)
                // A fresh page would clear a manual unread mark on open; the kept one does it here.
                (host.rootPage() as? ReopenableConversationPage)?.onConversationReopened()
            } else {
                replaceRootNow(host, pages.conversationRoot(target), selectionId = target.id, conversationId = target.id)
            }
            presentNow(tab)
            L.i { "[PaneNav] open kind=${target.kind} tab=$tab sameRoot=$sameRoot jump=${target.hasJump}" }
        }
    }

    /** Replaces [tab]'s whole stack with [page] (contact card, Me settings page). */
    fun replaceRoot(tab: Int, page: Fragment, selectionId: String?) {
        requestVersion++
        cancelPendingClose(tab)
        // Opened again on purpose: no longer a removed subject to close.
        selectionId?.let { RemovedConversations.remove(it) }
        submit {
            cancelPendingClose(tab)
            replaceRootNow(obtainHost(tab), page, selectionId = selectionId, conversationId = null)
            presentNow(tab)
        }
    }

    /**
     * Puts [page] on top of [tab]'s stack; the covered page keeps its view and drops to STARTED.
     *
     * The stack is the host's ordered page list, not a FragmentManager back stack: a back stack
     * entry would store its pop animations, and those would also play for a two-pane pop after an
     * unfold. Each push and pop is its own transaction, and the pop picks its animations when it
     * happens ([popAnimations]).
     *
     * [subjectId] is what the page is about ([PaneSubjects]); pushing it means the user opened it
     * again, so a pending removal of that subject no longer applies. A push whose [pageKey] equals
     * the top page's when it runs is dropped (a double tap before the first push landed).
     *
     * Only a shown stack takes pushes: one-pane refuses a tab whose stack is not presented (a
     * retained stack, the Me stack), so nothing slides over the list or lands invisibly. Returns
     * whether the push was accepted; it can still be dropped when it runs (logged).
     */
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
            // Only a push that lands supersedes a close queued before it.
            cancelPendingClose(tab)
            if (top == null) {
                replaceRootNow(host, page, selectionId = null, conversationId = null, subjectId = subjectId)
            } else {
                val tx = host.childFragmentManager.beginTransaction()
                // The entering page is the last child, so it draws over the covered one.
                pushAnimations()?.let { tx.setCustomAnimations(it[0], it[1]) }
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

    /** [tab] is the current tab and its stack is shown (the only stack a page may push onto). */
    internal fun isShownTab(tab: Int): Boolean = tab == currentTab && presented(tab)

    /**
     * Push from a sheet shown on the Activity (no parent page): only onto the current tab's shown
     * stack, and only while its root is still [overConversation], the conversation the sheet was
     * shown over; a sheet shown from the list (null) never pushes. See [Owner].
     */
    fun pushOnVisibleStack(destination: DetailDestination, overConversation: String?): Boolean {
        if (destroyed || overConversation == null) return false
        if (presentedHost(currentTab)?.rootPage()?.conversationId != overConversation) {
            L.w { "[PaneNav] sheet push refused tab=$currentTab root changed" }
            return false
        }
        return push(currentTab, destination.newFragment(), destination.subjectId, destination.pageKey)
    }

    /**
     * One-pane pushes slide like an Activity open, matching the layer's own slide; two-pane pushes
     * inside the detail pane stay instant. Order: enter, exit.
     */
    internal fun pushAnimations(): IntArray? =
        if (isDualPane) null else intArrayOf(R.anim.pane_push_enter, R.anim.pane_push_exit)

    /**
     * Only a user leaving a pushed page in one-pane slides it out like an Activity close; every
     * other removal (two-pane pop, clear, replace, pop back to a reopened root) is instant, also
     * for pages pushed in one-pane before an unfold. Order: enter (revealed page), exit.
     */
    internal fun popAnimations(userPop: Boolean): IntArray? =
        if (isDualPane || !userPop) null else intArrayOf(R.anim.pane_pop_enter, R.anim.pane_pop_exit)

    /** Opens a Me root in either layout; a repeated row keeps its root and removes its leaves. */
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

    /** An explicit return may reveal a retained Me stack; the predicate is checked in queue order. */
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

    /** Retained pages used only to identify an explicit return, regardless of presentation. */
    fun retainedMePages(): List<Fragment> = hostFor(TAB_ME)?.pages().orEmpty()

    fun pop(tab: Int): Boolean = pop(tab, 1)

    /** Captures the requesting page, so a queued pop never removes a newer top. */
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
            else PaneStackOperations.popToDepth(host, targetDepth, popAnimations(userPop = true))
        }
        return true
    }

    /** Empties [tab]'s stack (conversation closed); one-pane slides the visible layer out first. */
    fun clear(tab: Int) {
        if (destroyed) return
        requestVersion++
        if (!isDualPane && tab == currentTab && depth(tab) > 0) {
            if (pendingClose != null) return
            val close = PendingClose(tab, hostFor(tab)?.rootPage())
            pendingClose = close
            chrome.closeLayer {
                // Only the root this close was started for; a page that arrived meanwhile
                // cancelled it, and a replaced root is left alone.
                if (pendingClose !== close) return@closeLayer
                submit {
                    if (pendingClose === close) {
                        pendingClose = null
                        if (hostFor(tab)?.rootPage() === close.root) clearNow(tab)
                        // The shown stack still has pages (a replaced root, or another tab was
                        // selected meanwhile): bring the slid-out layer back.
                        if (depth(currentTab) > 0) chrome.cancelClose()
                    }
                }
            }
        } else {
            submit { clearNow(tab) }
        }
    }

    /**
     * Closes the pages of [tab] about [subjectId] (read from the asking page when it asked): the
     * lowest page about it and everything above. When that is the root the whole stack closes as
     * [clear] does; when no page is about it any more (an earlier close ran) nothing happens; a
     * null subject (a page about nothing removable) closes the whole stack.
     */
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
                // Closes at the slide's end (one-pane, shown) or at the drain's end, and only the
                // root matched here; a later request for this tab in queue order cancels it. One
                // slide at a time: while another tab's slide runs, this one closes at the drain's end.
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

    /** A new page for [tab] cancels that tab's close; a request for another tab lets it finish. */
    private fun cancelPendingClose(tab: Int) {
        if (drainCloses.removeAll { it.tab == tab }) L.i { "[PaneNav] queued close cancelled by a newer page tab=$tab" }
        if (pendingClose?.tab != tab) return
        pendingClose = null
        chrome.cancelClose()
        L.i { "[PaneNav] close cancelled by a newer page" }
    }

    /**
     * Closes, instantly, the pages about every [RemovedConversations] entry at any depth (the whole
     * stack when its root is about it), except in a tab whose close slide is already running (its
     * end clears it), and forgets entries no page is about any more.
     */
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

    /** Closes, in every tab, the pages about [conversationId] (a removed friend). */
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

    /** Presented depth: a retained but unpresented stack (one-pane) counts as empty. */
    fun depth(tab: Int = currentTab): Int = presentedHost(tab)?.depth ?: 0

    /**
     * One-pane presents a tab's stack only if it was the current tab when the screen was restored
     * into one-pane, or the user opened or pushed something in it while in one-pane. Other stacks
     * restored from two-pane are retained (a fold or an inner-screen rotation and back does not
     * lose them) but not presented: their host stays hidden and capped, so their pages' back
     * callbacks and lookups stay inactive, and switching to that tab shows its list. Two-pane presents every stack; one-pane applies the same rule to every tab.
     */
    private fun presented(tab: Int): Boolean = isDualPane || tab in presentedInOnePane

    // One-pane only; persisted, see presented().
    private val presentedInOnePane = mutableSetOf<Int>()

    private fun restorePresentedTabs(savedInstanceState: Bundle?) {
        if (isDualPane || savedInstanceState == null) return
        // Restored from two-pane: only the tab in front; else what the one-pane instance had.
        val tabs = if (savedInstanceState.getBoolean(STATE_WAS_DUAL_PANE, false)) {
            listOf(currentTab)
        } else {
            savedInstanceState.getIntArray(STATE_PRESENTED_TABS)?.toList().orEmpty()
        }
        presentedInOnePane += tabs // applied by attach's tab caps
    }

    /**
     * One-pane: the user opened or pushed something in [tab], so its stack is presented from now
     * on. Called at the end of that operation, after the retained stack was replaced while its host
     * was still hidden and capped: no retained page is shown or resumed on the way.
     */
    private fun presentNow(tab: Int) {
        if (isDualPane) return
        if (presentedInOnePane.add(tab)) applyTabCaps()
    }

    private fun presentedHost(tab: Int): DetailTabHostFragment? = hostFor(tab)?.takeIf { presented(tab) }

    /** The current tab's root page when it is a conversation, covered or not. */
    fun currentConversationRoot(): Fragment? =
        presentedHost(currentTab)?.rootPage()?.takeIf { it.conversationId != null }

    /** Conversation id when a conversation root is the visible top of the resumed current tab. */
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

    /** Runs the closes no later request cancelled, each only if its root is still the one matched. */
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
        // replace() removes every page in the container, covered ones included, without animation.
        host.childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.detail_tab_stack, page)
            .commitNow()
        host.refreshBackOwners()
    }

    /** Removes every pushed page, instantly, leaving the root shown and resumed. */
    private fun popAboveRootNow(host: DetailTabHostFragment) {
        val stack = host.pages()
        if (stack.size < 2) return
        val root = stack.first()
        val tx = host.childFragmentManager.beginTransaction().setReorderingAllowed(true)
        stack.drop(1).forEach { tx.remove(it) }
        tx.show(root).setMaxLifecycle(root, Lifecycle.State.RESUMED).commitNow()
        host.refreshBackOwners()
    }

    private fun clearNow(tab: Int) {
        val host = hostFor(tab) ?: return
        val remaining = host.pages()
        if (remaining.isEmpty()) return
        val tx = host.childFragmentManager.beginTransaction()
        remaining.forEach { tx.remove(it) }
        tx.commitNow()
        host.refreshBackOwners()
    }

    /**
     * One-pane: a page pushed above another is made opaque (the background the pane would paint
     * for it) so no frame of the slide shows two pages through each other. Roots keep the pane's
     * own background (the conversation background stays fixed while the IME resizes the page).
     * Two-pane pages slide nowhere and are left as they are.
     */
    internal fun onPageViewCreated(host: DetailTabHostFragment, page: Fragment, view: View) {
        if (isDualPane || destroyed || view.background != null) return
        if (host.rootPage() === page) return
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

        // The home screen's navigator while it exists; one IndexActivity at a time (singleTask).
        @Volatile
        private var live: WeakReference<PaneNavigator>? = null

        /** For work that outlived its page (friend removal): close the conversation on the home screen. */
        fun closeConversationEverywhere(conversationId: String) {
            live?.get()?.closeConversationById(conversationId)
        }
    }
}

/** A Me-page open request superseded by a later navigator call; the caller's run stays unhandled. */
private fun logMePageDropped(stage: String, expected: Long, current: Long) =
    L.w { "[PaneNav] me_page_dropped stage=$stage expected=$expected current=$current" }
