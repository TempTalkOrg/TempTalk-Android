package com.difft.android.chat.scroll.testing

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A real [RecyclerView] + [LinearLayoutManager] under Robolectric, for verifying FRAMEWORK
 * ASSUMPTIONS (scroll-state dispatch, visible-position computation) — not for rebuilding the
 * Fragment.
 *
 * Hosting the real `ChatMessageListFragment` was considered and rejected: `@AndroidEntryPoint`,
 * 2400+ lines, a 12-dependency ViewModel, ViewBinding and four host Activities. The cost lands on
 * the scaffolding, not the production code.
 *
 * SCOPE LIMIT — this harness covers PROGRAMMATIC scroll paths only. `SCROLL_STATE_DRAGGING` is
 * produced exclusively by real touch input, which Robolectric does not faithfully simulate; that
 * half is covered by on-device QA.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [28])
abstract class RecyclerViewScrollHarness {

    protected lateinit var recyclerView: RecyclerView
        private set

    private val states = mutableListOf<Int>()

    /** Every `onScrollStateChanged` value seen so far, in order. */
    protected val observedStates: List<Int> get() = states.toList()

    @Before
    fun setUpRecyclerView() {
        val context = ApplicationProvider.getApplicationContext<TestScopeApplication>()
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    states += newState
                }
            })
        }
    }

    protected fun submitAndLayout(itemCount: Int, itemHeightPx: Int = ITEM_HEIGHT_PX) {
        recyclerView.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = View(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, itemHeightPx
                    )
                }
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit

            override fun getItemCount() = itemCount
        }
        layoutRecyclerView()
    }

    protected fun layoutRecyclerView() {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEWPORT_HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, VIEWPORT_HEIGHT_PX)
    }

    protected fun idleLooper(times: Int = 20) {
        repeat(times) { shadowOf(Looper.getMainLooper()).idle() }
    }

    protected fun layoutManager(): LinearLayoutManager =
        recyclerView.layoutManager as LinearLayoutManager

    protected fun firstVisible(): Int = layoutManager().findFirstVisibleItemPosition()

    protected fun lastVisible(): Int = layoutManager().findLastVisibleItemPosition()

    /** No programmatic scroll may ever report a drag; the `userScrolling` gate depends on it. */
    protected fun assertNeverDragged() {
        assertEquals(
            "programmatic scrolling reported DRAGGING; observed=$observedStates",
            0,
            states.count { it == RecyclerView.SCROLL_STATE_DRAGGING },
        )
    }

    protected companion object {
        const val WIDTH_PX = 1080
        const val VIEWPORT_HEIGHT_PX = 1000
        const val ITEM_HEIGHT_PX = 200
    }
}
