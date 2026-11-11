package com.difft.android.search

import android.content.Intent
import com.difft.android.R
import android.os.Looper
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import com.difft.android.databinding.ActivitySearchBinding
import com.difft.android.search.mvi.GlobalSearchContract
import com.difft.android.search.mvi.GlobalSearchViewModel
import com.difft.android.test.TestDispatcherRule
import com.difft.android.test.di.FakeGlobalSearchSource
import com.difft.android.test.rules.GlobalStaticMockRule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.difft.app.database.models.ContactorModel
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.difft.android.base.R as BaseR







@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = com.difft.android.test.FoldableTestApplication_Application::class, sdk = [34])
class GlobalSearchShellIntegrationTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val dispatchers = TestDispatcherRule()
    @get:Rule(order = 2) val globals = GlobalStaticMockRule()
    @get:Rule(order = 3) val compose = createEmptyComposeRule()
    @BindValue @JvmField val strictUsers: StorageBoundUserManager = mockk(relaxed = true)

    private var controller: ActivityController<SearchActivity>? = null
    private val activity: SearchActivity get() = controller!!.get()

    @Before
    fun setUp() {
        every { com.difft.android.base.utils.application } returns androidx.test.core.app.ApplicationProvider.getApplicationContext<com.difft.android.base.application.ScopeApplication>()
        mockkObject(AppStartup)
        every { AppStartup.onCriticalRenderEventStart() } just Runs
        every { AppStartup.onCriticalRenderEventEnd() } just Runs
        FakeGlobalSearchSource.reset()
        hilt.inject()
    }

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
        FakeGlobalSearchSource.reset()
        unmockkObject(AppStartup)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
        compose.waitForIdle()
    }

    private fun launch(): GlobalSearchFragment {
        controller = Robolectric.buildActivity(SearchActivity::class.java).setup().visible()
        idle()
        return activity.supportFragmentManager.findFragmentById(BaseR.id.detail_shell_container) as GlobalSearchFragment
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

    
    private fun awaitRows(list: RecyclerView, count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (list.adapter!!.itemCount != count && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle()
        }
        assertEquals(count, list.adapter!!.itemCount)
    }

    private fun layOut(list: RecyclerView) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST))
        list.layout(0, 0, list.measuredWidth, list.measuredHeight)
        idle()
    }

    private fun clickRow(list: RecyclerView, position: Int) {
        layOut(list)
        assertNotNull(list.findViewHolderForAdapterPosition(position), "row $position laid out").itemView.performClick()
        idle()
    }

    private fun multiHit(id: String) = SearchChatHistoryViewData(id, type = SearchChatHistoryViewData.Type.OneOnOne, label = id, onlyOneResult = false)

    private fun startedForResult(): Intent = assertNotNull(shadowOf(activity).nextStartedActivityForResult, "a shell child was launched").intent

    @Test
    fun `the shell page keeps the layout autofocus and its back arrow finishes the Activity`() {
        val page = launch()
        val binding = binding(page)

        assertTrue(binding.searchInput.autoFocus, "shells keep the layout autofocus")
        assertEquals(View.VISIBLE, binding.ibBack.visibility)
        binding.ibBack.performClick()
        idle()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a multi-hit history row starts the message search shell with the trimmed query`() {
        FakeGlobalSearchSource.history = listOf(multiHit("u1"))
        val page = launch()
        search(page, " abc ")
        clickRow(binding(page).vContent, 2)

        val intent = startedForResult()
        assertEquals(SearchMessageActivity::class.java.name, intent.component?.className)
        assertEquals("u1", intent.getStringExtra("conversationId"))
        assertEquals("abc", intent.getStringExtra("key"))
    }

    @Test
    fun `View all switches to the section's scope in place`() {
        FakeGlobalSearchSource.history = (1..5).map { multiHit("u$it") }
        val page = launch()
        search(page, "abc")
        val list = binding(page).vContent
        
        awaitRows(list, 6)

        layOut(list)
        compose.onNodeWithText("View all 5 results").performClick()
        idle()

        assertEquals(GlobalSearchContract.Scope.MESSAGES, ViewModelProvider(page)[GlobalSearchViewModel::class.java].state.value.scope)
        
        awaitRows(list, 7)
        assertNull(shadowOf(activity).nextStartedActivityForResult, "no page is opened")
    }

    @Test
    fun `View more under a full contacts scope starts the More shell with the section type and the query`() {
        FakeGlobalSearchSource.contacts = (1..25).map { i -> ContactorModel().apply { id = "c$i"; name = "abc $i" } }
        val page = launch()
        search(page, "abc")
        ViewModelProvider(page)[GlobalSearchViewModel::class.java].dispatch(GlobalSearchContract.Intent.SelectScope(GlobalSearchContract.Scope.CONTACTS))
        idle()
        val list = binding(page).vContent
        
        awaitRows(list, 28)

        list.scrollToPosition(27)
        idle()
        assertNotNull(list.findViewHolderForAdapterPosition(27), "the more row is laid out")
        compose.onNodeWithText("View More").performClick()
        idle()

        val intent = startedForResult()
        assertEquals(SearchMoreActivity::class.java.name, intent.component?.className)
        assertEquals(SearchMoreActivity.SEARCH_TYPE_CONTACT, intent.getIntExtra("type", -1))
        assertEquals("abc", intent.getStringExtra("key"))
    }

    @Test
    fun `S9 - a contact opens its one-on-one conversation from the search page and from the More page`() {
        FakeGlobalSearchSource.contacts = listOf(ContactorModel().apply { id = "u7"; name = "abc" })
        val page = launch()
        search(page, "abc")

        
        clickRow(binding(page).vContent, 2)

        val fromSearch = assertNotNull(shadowOf(activity).nextStartedActivity, "the conversation opened")
        assertEquals(ChatActivity::class.java.name, fromSearch.component?.className)
        assertEquals("u7", fromSearch.contactorID)

        val more = Robolectric.buildActivity(SearchMoreActivity::class.java, SearchMoreActivity.intent(activity, SearchMoreActivity.SEARCH_TYPE_CONTACT, "abc")).setup().visible()
        try {
            dispatchers.testDispatcher.scheduler.runCurrent()
            idle()
            clickRow(more.get().findViewById(R.id.recyclerview_result), 0)

            val fromMore = assertNotNull(shadowOf(more.get()).nextStartedActivity, "the More page opens the conversation, not the card")
            assertEquals(ChatActivity::class.java.name, fromMore.component?.className)
            assertEquals("u7", fromMore.contactorID)
        } finally {
            runCatching { more.pause().stop().destroy() }
        }
    }
}
