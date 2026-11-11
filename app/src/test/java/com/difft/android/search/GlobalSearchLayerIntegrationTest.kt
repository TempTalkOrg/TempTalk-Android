package com.difft.android.search

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.R
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageFragment
import com.difft.android.chat.search.SearchUtils
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import com.difft.android.databinding.ActivitySearchBinding
import com.difft.android.dualpane.IndexDetailPages
import com.difft.android.dualpane.ListPaneLayer
import com.difft.android.dualpane.ListPaneLayerRoot
import com.difft.android.dualpane.PaneNavigator
import com.difft.android.search.mvi.GlobalSearchContract
import com.difft.android.search.mvi.GlobalSearchViewModel
import com.difft.android.search.mvi.SearchRow
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.di.FakeGlobalSearchSource
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.difft.app.database.PaginatedMessageSearchResult
import org.difft.app.database.WCDB
import org.difft.app.database.loadPaginatedMessagesByKeyword
import org.difft.app.database.models.MessageModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue






@AndroidEntryPoint
class SearchLayerWiringTestActivity : FragmentActivity(), PaneNavigator.Owner, ListPaneLayer.Owner, ConversationNavigationCallback {
    lateinit var navigator: PaneNavigator
        private set
    override val paneNavigator: PaneNavigator get() = navigator
    override lateinit var listLayer: ListPaneLayer
        private set
    val opened = mutableListOf<ConversationTarget>()

    override val isDualPaneMode: Boolean get() = navigator.isDualPane
    override val currentSelectedConversationId: String? = null
    override val detailCoversList: StateFlow<Boolean> = MutableStateFlow(false)
    override val homeTabBarObstructionPx: StateFlow<Int> = MutableStateFlow(0)
    override fun currentConversationOnScreen(): String? = null
    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onContactDetailSelected(contactId: String) = Unit
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit
    override fun openConversation(target: ConversationTarget) {
        opened += target
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_index)
        navigator = PaneNavigator(
            this,
            resources.getBoolean(R.bool.dual_pane_layout_active),
            object : PaneNavigator.Pages {
                override fun conversationRoot(target: ConversationTarget): Fragment = Fragment()
                override fun usesSettingsBackground(page: Fragment): Boolean = false
                override fun conversationLayerBackground(context: Context, layer: View): Drawable = ColorDrawable(0)
            },
            object : PaneNavigator.Listener {
                override fun onDetailStateChanged() = Unit
                override fun onCurrentDetailEmptied() = Unit
                override fun onSelectTabRequested(tab: Int) = navigator.selectTab(tab)
            },
        )
        navigator.attach(savedInstanceState)
        listLayer = ListPaneLayer(this, navigator.chrome, IndexDetailPages, navigator.detailCoversList) {}
        listLayer.attach(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        navigator.saveState(outState)
        listLayer.saveState(outState)
    }
}







@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34], qualifiers = "w840dp-h900dp")
class GlobalSearchLayerIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)
    @BindValue @JvmField val wcdb: WCDB = mockk(relaxed = true)

    private var controller: ActivityController<SearchLayerWiringTestActivity>? = null
    private val activity: SearchLayerWiringTestActivity get() = controller!!.get()
    private val layer: ListPaneLayer get() = activity.listLayer

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        coEvery { any<WCDB>().loadPaginatedMessagesByKeyword(any(), any(), any(), any()) } returns PaginatedMessageSearchResult(emptyList(), false, null)
        mockkObject(SearchUtils)
        every { SearchUtils.createSearchChatHistoryViewDataList(any<List<MessageModel>>()) } returns emptyList()
        FakeGlobalSearchSource.reset()
        hilt.inject()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        FakeGlobalSearchSource.reset()
        unmockkObject(AppStartup, SearchUtils)
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun openLayer(): GlobalSearchFragment {
        controller = Robolectric.buildActivity(SearchLayerWiringTestActivity::class.java).setup().visible()
        idle()
        layer.open(ListPaneLayerRoot.SEARCH)
        idle()
        return assertIs<GlobalSearchFragment>(layer.host!!.rootPage())
    }

    private fun binding(page: GlobalSearchFragment): ActivitySearchBinding = ActivitySearchBinding.bind(page.requireView())

    private fun search(page: GlobalSearchFragment, raw: String) {
        binding(page).searchInput.query = raw
        ViewModelProvider(page)[GlobalSearchViewModel::class.java].dispatch(GlobalSearchContract.Intent.QueryChanged(raw))
        dispatchers.testDispatcher.scheduler.advanceTimeBy(GlobalSearchViewModel.DEBOUNCE_MS + 1)
        dispatchers.testDispatcher.scheduler.runCurrent()
        idle()
        awaitRendered(page)
    }

    
    private fun awaitRendered(page: GlobalSearchFragment) {
        val rows = ViewModelProvider(page)[GlobalSearchViewModel::class.java].state.value.rows
        val adapter = binding(page).vContent.adapter as GlobalSearchResultsAdapter
        val deadline = System.currentTimeMillis() + 5_000
        while (adapter.currentList != rows && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle()
        }
        assertEquals(rows, adapter.currentList)
    }

    private fun clickRow(list: RecyclerView, position: Int) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        assertNotNull(list.findViewHolderForAdapterPosition(position), "row $position laid out").itemView.performClick()
        idle()
    }

    private fun history(id: String, single: Boolean) =
        SearchChatHistoryViewData(id, type = SearchChatHistoryViewData.Type.OneOnOne, label = id, onlyOneResult = single, messageTimeStamp = if (single) 42L else null)

    @Test
    fun `the hosted page turns the layout autofocus off and its back arrow closes the layer`() {
        val page = openLayer()
        val binding = binding(page)
        assertFalse(binding.searchInput.autoFocus, "hosted: detailKeyboard opens the keyboard, not the layout")
        assertEquals(View.VISIBLE, binding.ibBack.visibility)

        binding.ibBack.performClick()
        idle()

        assertFalse(layer.active)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.list_layer).visibility)
    }

    




    @Test
    fun `a result opens its conversation through the router and keeps the layer`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = true))
        val page = openLayer()
        search(page, "abc")

        clickRow(binding(page).vContent, HISTORY_ROW)

        val started = assertNotNull(shadowOf(activity).nextStartedActivity, "the conversation open left the page")
        assertEquals(ChatActivity::class.java.name, started.component?.className)
        assertEquals("u1", started.contactorID)
        assertTrue(layer.active)
        assertEquals(1, layer.depth)
        assertIs<GlobalSearchFragment>(layer.host!!.rootPage())
    }

    @Test
    fun `a multi-hit row pushes the message search onto the layer with the trimmed query`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = false))
        val page = openLayer()
        search(page, " abc ")

        clickRow(binding(page).vContent, HISTORY_ROW)

        val host = layer.host!!
        assertEquals(2, host.depth)
        val pushed = assertIs<SearchMessageFragment>(host.topPage())
        assertEquals("abc", pushed.requireArguments().getString("key"))
        assertEquals("u1", pushed.requireArguments().getString("conversationId"))
        assertTrue(page.isHidden, "the root is covered")
        assertTrue(activity.opened.isEmpty())
    }

    private fun imm(): InputMethodManager = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun keyboardShown(): Boolean = shadowOf(imm()).isSoftInputVisible

    




    @Test
    fun `a recreation keeps the query and its results in the restored page`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = true))
        val page = openLayer()
        search(page, "abc")

        controller!!.recreate()
        idle()

        val restored = assertIs<GlobalSearchFragment>(layer.host!!.rootPage())
        assertTrue(restored !== page)
        val state = ViewModelProvider(restored)[GlobalSearchViewModel::class.java].state.value
        assertEquals("abc", state.query)
        assertEquals(listOf("u1"), state.history.map { it.conversationId })
        assertEquals(state.rows.size, binding(restored).vContent.adapter!!.itemCount)
        assertFalse(binding(restored).searchInput.autoFocus)
    }

    
    private fun drag(view: View, x: Float = 100f, y: Float = 100f) {
        val t = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, x, y + 300f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x, y + 300f, 0))
        idle()
    }

    @Test
    fun `a finger drag on the results hides the keyboard, a programmatic scroll does not (D2, S-1)`() {
        FakeGlobalSearchSource.history = (1..30).map { history("u$it", single = true) }
        val page = openLayer()
        search(page, "abc")
        
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())
        
        val list = binding(page).vContent
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 400)

        
        list.scrollBy(0, 50)
        idle()
        assertTrue(keyboardShown(), "typing must not lose the keyboard to a layout clamp")

        drag(list)
        assertFalse(keyboardShown(), "a drag on the results hides the keyboard")
    }

    @Test
    fun `a drag on results too short to scroll still hides the keyboard (D2)`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = true))
        val page = openLayer()
        search(page, "abc")
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())
        val list = binding(page).vContent
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.EXACTLY))
        list.layout(0, 0, 1080, 2000)
        assertFalse(list.canScrollVertically(1), "the results fit the page")

        
        drag(list, y = 1500f)

        assertFalse(keyboardShown())
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - a conversation opened over search without a result tap takes the keyboard down (S-3)`() {
        val page = openLayer()
        search(page, "abc")
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())

        
        activity.navigator.replaceRoot(PaneNavigator.TAB_CHATS, Fragment(), selectionId = "u1")
        idle()

        assertTrue(activity.navigator.detailCoversList.value)
        assertFalse(keyboardShown())
        assertTrue(layer.active)
    }

    @Test
    fun `closing search drops the query and the next open starts empty (D4)`() {
        val page = openLayer()
        search(page, "abc")
        assertEquals("abc", ViewModelProvider(page)[GlobalSearchViewModel::class.java].state.value.query)

        layer.close()
        idle()
        layer.open(ListPaneLayerRoot.SEARCH)
        idle()

        val fresh = assertIs<GlobalSearchFragment>(layer.host!!.rootPage())
        assertTrue(fresh !== page)
        val freshState = ViewModelProvider(fresh)[GlobalSearchViewModel::class.java].state.value
        assertEquals("", freshState.query)
        assertEquals(listOf(SearchRow.Scopes(GlobalSearchContract.Scope.ALL), SearchRow.Empty("")), freshState.rows)
        assertEquals("", binding(fresh).searchInput.query)
    }

    @Test
    fun `opening from the search circle reveals the layer and holds the keyboard until it covers`() {
        controller = Robolectric.buildActivity(SearchLayerWiringTestActivity::class.java).setup().visible()
        idle()
        var focused = false

        layer.open(ListPaneLayerRoot.SEARCH, revealFrom = android.graphics.Rect(700, 800, 762, 862))
        layer.runAfterReveal { focused = true }
        assertFalse(focused, "held while the circle grows")

        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(REVEAL_SETTLE_MS))
        idle()
        assertTrue(focused)
        val container = activity.findViewById<View>(R.id.list_layer)
        assertEquals(View.VISIBLE, container.visibility)
        assertFalse(container.clipToOutline, "the circle clip is released once the layer is covered")

        layer.close()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(REVEAL_SETTLE_MS))
        idle()
        assertEquals(View.GONE, container.visibility)
        assertEquals(1f, container.alpha)
    }

    @Test
    fun `closing before the reveal ends drops the held keyboard request`() {
        controller = Robolectric.buildActivity(SearchLayerWiringTestActivity::class.java).setup().visible()
        idle()
        var focused = false

        layer.open(ListPaneLayerRoot.SEARCH, revealFrom = android.graphics.Rect(700, 800, 762, 862))
        layer.runAfterReveal { focused = true }
        layer.close()
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(REVEAL_SETTLE_MS))
        idle()

        assertFalse(focused, "the closing search must not raise its keyboard")
    }

    @Test
    fun `the close circle beside the bottom search bar closes the layer`() {
        val binding = binding(openLayer())

        assertEquals(View.VISIBLE, binding.searchBarRow.visibility)
        binding.ibBack.performClick()
        idle()

        assertFalse(layer.active)
    }

    private companion object {
        
        const val HISTORY_ROW = 2

        const val REVEAL_SETTLE_MS = 700L
    }
}
