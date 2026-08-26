package com.difft.android.chat.recent

import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.chat.R
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T4-1..T4-7 — the [ListItem.E2eeFooter] adapter/view-type/DiffUtil plumbing added to the REAL
 * production [RecentChatAdapter]. T4-8/T4-9 (span construction) live in
 * [RecentChatFooterViewHolderTest].
 *
 * T4-5/T4-6 exercise the adapter's list-submission contract directly (mirroring
 * [RecentChatFragment.submitSortedChatRooms]'s exact 3-line assembly) rather than driving a live
 * `RecentChatFragment` — that Fragment is `@AndroidEntryPoint` with ~11 injected singletons and no
 * existing Hilt+Robolectric fragment-launch harness exists anywhere in this codebase yet; standing
 * one up is net-new test infrastructure out of proportion to a footer-feature task. The adapter is
 * the actual regression surface this task changes (view type / DiffUtil / ViewHolder), so testing
 * it directly still covers the real production code path.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RecentChatAdapterFooterTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var recyclerView: RecyclerView
    private var footerClickCount = 0

    private val adapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(activity) {
            override fun onItemClicked(roomViewData: RoomViewData, position: Int) = Unit
            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) = Unit
            override fun onFooterClicked() {
                footerClickCount++
            }
        }
    }

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = this@RecentChatAdapterFooterTest.adapter
            layoutParams = FrameLayout.LayoutParams(WIDTH_PX, HEIGHT_PX)
        }
        activity.setContentView(recyclerView)
    }

    @After
    fun tearDown() {
        unmockkObject(TextSizeUtil)
        runCatching { controller.destroy() }
    }

    /** Submits synchronously where possible and drains the async-diff commit callback otherwise. */
    private fun submitAndAwait(items: List<ListItem>, target: RecentChatAdapter = adapter) {
        val committed = AtomicBoolean(false)
        target.submitList(items) { committed.set(true) }
        val deadline = System.currentTimeMillis() + 5_000
        while (!committed.get() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Fresh adapter never attached to a RecyclerView — used by the list-assembly tests (T4-5/T4-6)
     * so submitting real [ListItem.ChatItem] rows never reaches a real window-attached layout pass.
     * [RecentChatViewHolder]'s constructor reads `globalServices.myId` (a Hilt entry point), which
     * this plain-Robolectric test has no Hilt component to satisfy — the shared [adapter]/
     * [recyclerView] fixture is safe only for footer-only lists (no [ListItem.ChatItem]).
     */
    private fun newUnattachedAdapter(): RecentChatAdapter = object : RecentChatAdapter(activity) {
        override fun onItemClicked(roomViewData: RoomViewData, position: Int) = Unit
        override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) = Unit
    }

    private fun layoutRecyclerView() {
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Mirrors [RecentChatFragment.submitSortedChatRooms]'s exact assembly. */
    private fun buildListItems(rooms: List<RoomViewData>): List<ListItem> {
        val items = mutableListOf<ListItem>()
        items.add(ListItem.SearchInput)
        items.addAll(rooms.map { ListItem.ChatItem(it) })
        items.add(ListItem.E2eeFooter)
        return items
    }

    /** T4-1 — getItemViewType returns VIEW_TYPE_E2EE_FOOTER (1) for the footer item. */
    @Test
    fun `getItemViewType returns footer type for E2eeFooter item`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))

        assertEquals(RecentChatAdapter.VIEW_TYPE_E2EE_FOOTER, adapter.getItemViewType(1))
    }

    /** T4-2 — onCreateViewHolder(VIEW_TYPE_E2EE_FOOTER) inflates a RecentChatFooterViewHolder
     * whose root contains R.id.textview_e2ee_hint. */
    @Test
    fun `footer view type creates RecentChatFooterViewHolder with hint textview present`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(1)

        assertTrue(holder is RecentChatFooterViewHolder)
        assertNotNull(holder!!.itemView.findViewById<TextView>(R.id.textview_e2ee_hint))
    }

    /** T4-3 — tapping the bound footer row invokes onFooterClicked exactly once. */
    @Test
    fun `clicking the bound footer row invokes onFooterClicked once`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(1)!!
        holder.itemView.performClick()

        assertEquals(1, footerClickCount)
    }

    /**
     * T4-4 — the singleton [ListItem.E2eeFooter] never triggers a diff-driven rebind across
     * resubmissions of an equal list (referential-equality fallthrough in the adapter's
     * `DiffUtil.ItemCallback`).
     */
    @Test
    fun `resubmitting an equal list reports zero changes for the footer`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()

        var changedRanges = 0
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                changedRanges++
            }

            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                changedRanges++
            }
        })

        // New list instance, same singleton item references -> DiffUtil must see zero changes.
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))

        assertEquals(0, changedRanges)
    }

    /** Footer is present even when the room list is empty (shown, deliberately not gated on room count). */
    @Test
    fun `footer is appended even for an empty room list`() {
        val unattached = newUnattachedAdapter()
        submitAndAwait(buildListItems(emptyList()), target = unattached)

        assertEquals(2, unattached.currentList.size) // SearchInput + footer
        assertTrue(unattached.currentList.last() is ListItem.E2eeFooter)
    }

    /** T4-6 — footer stays last regardless of room count. */
    @Test
    fun `footer stays last regardless of room count`() {
        val rooms = listOf(
            RoomViewData(roomId = "r1"),
            RoomViewData(roomId = "r2"),
            RoomViewData(roomId = "r3"),
        )
        val unattached = newUnattachedAdapter()

        submitAndAwait(buildListItems(rooms), target = unattached)

        assertEquals(5, unattached.currentList.size) // SearchInput + 3 rooms + footer
        assertTrue(unattached.currentList.last() is ListItem.E2eeFooter)
    }

    /** T4-7 — rebinding the footer view holder after a text-size change updates the hint text size. */
    @Test
    fun `footer hint text size follows TextSizeUtil isLarger on rebind`() {
        mockkObject(TextSizeUtil)
        every { TextSizeUtil.isLarger } returns false

        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()
        val holder = recyclerView.findViewHolderForAdapterPosition(1) as RecentChatFooterViewHolder
        val textView = holder.itemView.findViewById<TextView>(R.id.textview_e2ee_hint)
        val scaledDensity = activity.resources.displayMetrics.scaledDensity

        assertEquals(12f, textView.textSize / scaledDensity, 0.5f)

        every { TextSizeUtil.isLarger } returns true
        adapter.onBindViewHolder(holder, 1)

        assertEquals(18f, textView.textSize / scaledDensity, 0.5f)
    }

    private companion object {
        const val WIDTH_PX = 1000
        const val HEIGHT_PX = 2000
    }
}
