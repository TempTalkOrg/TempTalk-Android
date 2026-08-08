package com.difft.android.chat.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.AsyncDifferConfig
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * H8/H9 (design-report §8.3, §8.4) — Robolectric integration coverage for the RecyclerView-reuse-safe
 * re-query behind [ChatMessageListFragment.currentItemView].
 *
 * **Why Robolectric (not just [ResolveByMessageIdTest]):** the pure-function test proves the
 * position-hit / position-miss branching of [resolveByMessageId], but design §8.4 flags the *real*
 * regression class as the Android-framework behaviour this task newly depends on — resolving a message
 * id to its **currently bound** itemView through a live [ListAdapter.getCurrentList] +
 * [RecyclerView.findViewHolderForAdapterPosition] after list mutation (RecyclerView reuse / async diff).
 * This test wires the REAL production [resolveByMessageId] to a REAL [RecyclerView] + [ListAdapter]
 * (id-based [DiffUtil.ItemCallback], mirroring the production [ChatMessageAdapter]) attached to a live
 * Activity, measured + laid out so ViewHolders are actually created and bound.
 *
 * A synchronous background diff executor + main-looper idle keeps `submitList` deterministic.
 *
 * Verify: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class CurrentItemViewRecyclerViewTest {

    private data class Row(val id: String)

    private class VH(view: View) : RecyclerView.ViewHolder(view)

    /** Minimal real ListAdapter mirroring ChatMessageAdapter's id-based diff. Synchronous diff executor. */
    private class RowAdapter : ListAdapter<Row, VH>(
        AsyncDifferConfig.Builder(
            object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.id == newItem.id
                override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
            }
        ).setBackgroundThreadExecutor { it.run() }.build()
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ROW_HEIGHT_PX
                )
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            (holder.itemView as TextView).text = getItem(position).id
        }
    }

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RowAdapter

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
        adapter = RowAdapter()
        recyclerView = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            this.adapter = this@CurrentItemViewRecyclerViewTest.adapter
        }
        activity.setContentView(recyclerView)
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    /** Exercises the REAL production helper: id -> current position -> currently bound itemView. */
    private fun currentItemView(messageId: String): View? =
        resolveByMessageId(
            messageId,
            indexOf = { id -> adapter.currentList.indexOfFirst { it.id == id } },
            itemAt = { pos -> recyclerView.findViewHolderForAdapterPosition(pos)?.itemView }
        )

    private fun submitAndLayout(rows: List<Row>) {
        adapter.submitList(rows)
        shadowOf(Looper.getMainLooper()).idle()
        recyclerView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT_PX, View.MeasureSpec.EXACTLY)
        )
        recyclerView.layout(0, 0, WIDTH_PX, HEIGHT_PX)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun H8_positionHit_returnsCurrentlyBoundItemView() {
        submitAndLayout(listOf(Row("a"), Row("b"), Row("c")))

        val resolved = currentItemView("b")

        assertNotNull("re-query by id must resolve a live itemView", resolved)
        // It is exactly the view currently bound at b's position — not a captured/stale reference.
        val boundAtPos1 = recyclerView.findViewHolderForAdapterPosition(1)?.itemView
        assertSame(boundAtPos1, resolved)
        assertEquals("b", (resolved as TextView).text.toString())
    }

    @Test
    fun H8b_afterListUpdate_resolvesNewPositionView_notStaleReference() {
        submitAndLayout(listOf(Row("a"), Row("b"), Row("c")))
        // Capture b's original itemView while it sits at position 1.
        val originalBView = recyclerView.findViewHolderForAdapterPosition(1)?.itemView
        assertEquals("b", (originalBView as TextView).text.toString())

        // Prepend a row: "b" shifts to position 2. RecyclerView reuse may rebind views across positions.
        submitAndLayout(listOf(Row("x"), Row("a"), Row("b"), Row("c")))

        val resolved = currentItemView("b")
        assertNotNull(resolved)
        // Re-query resolves whatever view is CURRENTLY bound at b's NEW position (2)...
        val boundAtPos2 = recyclerView.findViewHolderForAdapterPosition(2)?.itemView
        assertSame(boundAtPos2, resolved)
        // ...and that view actually renders "b" (proves reuse-safe re-query, not a stale hero view).
        assertEquals("b", (resolved as TextView).text.toString())
    }

    @Test
    fun H9_positionMiss_returnsNull_noCrash() {
        submitAndLayout(listOf(Row("a"), Row("b"), Row("c")))

        // Id absent (deleted / scrolled out of the reuse window) -> position = -1.
        assertNull(currentItemView("missing"))
    }

    private companion object {
        const val ROW_HEIGHT_PX = 100
        const val WIDTH_PX = 1000
        const val HEIGHT_PX = 2000
    }
}
