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

/**
 * Home-screen stand-in for the real search pages: the real `activity_index` tree, a navigator
 * with plain conversation roots, the production search layer with [IndexDetailPages]'s search
 * root, and the router's home callback recording what a result asked to open.
 */
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

/**
 * The real [GlobalSearchFragment] as the root of the list pane's search layer (design "global
 * search in the list pane" §4.3; R1-F2, R1-F3): the layout autofocus is off, the back arrow
 * closes the layer, a result opens on the home screen through the router with the layer kept,
 * and a multi-hit row pushes the message search onto the layer with the trimmed query.
 */
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
        // The pushed message search queries WCDB (native) on its first load: answer it empty.
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
        dispatchers.testDispatcher.scheduler.advanceTimeBy(301)
        dispatchers.testDispatcher.scheduler.runCurrent()
        idle()
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

    /**
     * The test Application is no ConversationOpener, so the routed open shows as the shell intent
     * here; with the real Application the router's rule 1 hands the same target to the home
     * screen's `openConversation` (ConversationRouterTest). Either way the layer stays.
     */
    @Test
    fun `a result opens its conversation through the router and keeps the layer`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = true))
        val page = openLayer()
        search(page, "abc")

        clickRow(binding(page).recyclerviewChatHistory, 0)

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

        clickRow(binding(page).recyclerviewChatHistory, 0)

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

    /**
     * The keyboard itself is driven through Compose's keyboard controller, which the Robolectric
     * IMM shadow does not see; the fresh-open / no-keyboard-after-recreation rule is
     * `detailKeyboard`'s (DetailKeyboardTest), which the page calls with its saved state.
     */
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
        assertEquals(View.VISIBLE, binding(restored).llChatHistory.visibility)
        assertFalse(binding(restored).searchInput.autoFocus)
    }

    /** A finger drag on [view] from ([x], [y]): down, then a move beyond the touch slop, then up. */
    private fun drag(view: View, x: Float = 100f, y: Float = 100f) {
        val t = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, x, y + 300f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_UP, x, y + 300f, 0))
        idle()
    }

    @Test
    fun `a finger drag on the results hides the keyboard, a programmatic or clamp scroll does not (D2, S-1)`() {
        FakeGlobalSearchSource.history = listOf(history("u1", single = true))
        val page = openLayer()
        search(page, "abc")
        // The shadow IMM only sees the framework calls: raise it here, the drag must lower it.
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())
        // A short viewport, so the results overflow and the scroll position can move at all.
        val results = binding(page).vContent
        results.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
        results.layout(0, 0, 1080, 100)
        assertTrue(results.getChildAt(0).height > results.height, "the results overflow the viewport")

        // What a shrinking results list does under a narrower query: NestedScrollView clamps its offset.
        results.scrollTo(0, 1)
        idle()
        assertEquals(1, results.scrollY)
        assertTrue(keyboardShown(), "typing must not lose the keyboard to a layout clamp")

        val list = binding(page).recyclerviewChatHistory
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        drag(list)
        assertFalse(keyboardShown(), "a drag on a results list hides the keyboard")

        // A drag that starts on a section heading is handled by the outer scroll container (R2-F1).
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())
        val heading = binding(page).tvChatHistoryTitle
        assertTrue(heading.isShown && heading.height > 0, "the history heading is laid out")
        // The list sits under the heading again (clickRow's layout put it at the section's top).
        list.layout(0, heading.bottom, list.measuredWidth, heading.bottom + list.measuredHeight)
        drag(results, x = 100f, y = heading.top + heading.height / 2f)

        assertFalse(keyboardShown(), "a drag on a heading hides the keyboard too")
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `one-pane - a conversation opened over search without a result tap takes the keyboard down (S-3)`() {
        val page = openLayer()
        search(page, "abc")
        imm().showSoftInput(binding(page).root, 0)
        assertTrue(keyboardShown())

        // A notification or link: the navigator opens the conversation layer, no result row was tapped.
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
        assertEquals(GlobalSearchContract.State(), ViewModelProvider(fresh)[GlobalSearchViewModel::class.java].state.value)
        assertEquals("", binding(fresh).searchInput.query)
    }
}
