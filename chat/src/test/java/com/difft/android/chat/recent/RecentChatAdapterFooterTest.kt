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

    
    private fun buildListItems(rooms: List<RoomViewData>): List<ListItem> {
        val items = mutableListOf<ListItem>()
        items.addAll(rooms.map { ListItem.ChatItem(it) })
        items.add(ListItem.E2eeFooter)
        return items
    }

    
    @Test
    fun `getItemViewType returns footer type for E2eeFooter item`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))

        assertEquals(RecentChatAdapter.VIEW_TYPE_E2EE_FOOTER, adapter.getItemViewType(1))
    }

    

    @Test
    fun `footer view type creates RecentChatFooterViewHolder with hint textview present`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(1)

        assertTrue(holder is RecentChatFooterViewHolder)
        assertNotNull(holder!!.itemView.findViewById<TextView>(R.id.textview_e2ee_hint))
    }

    
    @Test
    fun `clicking the bound footer row invokes onFooterClicked once`() {
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))
        layoutRecyclerView()

        val holder = recyclerView.findViewHolderForAdapterPosition(1)!!
        holder.itemView.performClick()

        assertEquals(1, footerClickCount)
    }

    




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

        
        submitAndAwait(listOf(ListItem.SearchInput, ListItem.E2eeFooter))

        assertEquals(0, changedRanges)
    }

    
    @Test
    fun `footer is appended even for an empty room list`() {
        val unattached = newUnattachedAdapter()
        submitAndAwait(buildListItems(emptyList()), target = unattached)

        assertEquals(1, unattached.currentList.size) 
        assertTrue(unattached.currentList.last() is ListItem.E2eeFooter)
    }

    
    @Test
    fun `footer stays last regardless of room count`() {
        val rooms = listOf(
            RoomViewData(roomId = "r1"),
            RoomViewData(roomId = "r2"),
            RoomViewData(roomId = "r3"),
        )
        val unattached = newUnattachedAdapter()

        submitAndAwait(buildListItems(rooms), target = unattached)

        assertEquals(4, unattached.currentList.size) 
        assertTrue(unattached.currentList.last() is ListItem.E2eeFooter)
    }

    
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
