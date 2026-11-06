package com.difft.android.search

import android.content.Intent
import android.os.Looper
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.utils.AppStartup
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchMessageActivity
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
import kotlin.test.assertTrue
import com.difft.android.base.R as BaseR

/**
 * The shell [SearchActivity] keeps the former Activity's call graph with the page as a fragment
 * (design "global search in the list pane" §4.3/§4.6, S2/S18): the layout autofocus stays on,
 * the back arrow finishes, a multi-hit row starts the message-search shell with the trimmed
 * query and "More" starts the More shell with the section type and key.
 */
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

    /** Types [raw] through the page's ViewModel and lets the 300 ms debounce and the lookups run. */
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
        val binding = binding(page)
        assertEquals(View.VISIBLE, binding.llChatHistory.visibility)

        clickRow(binding.recyclerviewChatHistory, 0)

        val intent = startedForResult()
        assertEquals(SearchMessageActivity::class.java.name, intent.component?.className)
        assertEquals("u1", intent.getStringExtra("conversationId"))
        assertEquals("abc", intent.getStringExtra("key"))
    }

    @Test
    fun `More starts the More shell with the section type and the query`() {
        FakeGlobalSearchSource.history = (1..5).map { multiHit("u$it") }
        val page = launch()
        search(page, "abc")
        val binding = binding(page)
        assertEquals(View.VISIBLE, binding.clSearchChatHistoryMore.root.visibility)
        assertEquals(4, binding.recyclerviewChatHistory.adapter!!.itemCount)

        binding.clSearchChatHistoryMore.root.performClick()
        idle()

        val intent = startedForResult()
        assertEquals(SearchMoreActivity::class.java.name, intent.component?.className)
        assertEquals(SearchMoreActivity.SEARCH_TYPE_MESSAGE, intent.getIntExtra("type", -1))
        assertEquals("abc", intent.getStringExtra("key"))
    }
}
