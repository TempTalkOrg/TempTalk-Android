package com.difft.android.search

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryAdapter
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.test.rules.GlobalStaticMockRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import com.difft.android.base.R as BaseR

/**
 * Two-pane highlight of the open conversation in the global search's group and message-history
 * rows (design "global search in the list pane" §4.3, S13): the selected row binds on bg3, the
 * others stay transparent, and changing the selection rebinds both rows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SearchRowSelectionAdapterTest {

    @get:Rule
    val globals = GlobalStaticMockRule()

    private val activity: FragmentActivity by lazy {
        Robolectric.buildActivity(FragmentActivity::class.java).setup().get().apply {
            setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }
    }

    private fun bg3(): Int = ContextCompat.getColor(activity, BaseR.color.bg3)

    private fun color(view: View): Int = (view.background as? ColorDrawable)?.color ?: Color.TRANSPARENT

    private fun <VH : RecyclerView.ViewHolder> bind(adapter: RecyclerView.Adapter<VH>, position: Int): View {
        val holder = adapter.onCreateViewHolder(FrameLayout(activity), adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        return holder.itemView
    }

    @Test
    fun `the selected group row sits on bg3 and the others stay transparent`() {
        val adapter = object : SearchGroupsAdapter() {
            override fun onItemClick(group: GroupUIData) = Unit
        }
        adapter.submitList(listOf(GroupUIData(gid = "g1", name = "One", members = emptyList()), GroupUIData(gid = "g2", name = "Two", members = emptyList())))
        adapter.selectedId = "g2"

        assertEquals(Color.TRANSPARENT, color(bind(adapter, 0)))
        assertEquals(bg3(), color(bind(adapter, 1)))

        adapter.selectedId = null
        assertEquals(Color.TRANSPARENT, color(bind(adapter, 1)))
    }

    @Test
    fun `the selected conversation's history row sits on bg3`() {
        val adapter = object : SearchChatHistoryAdapter() {
            override fun onItemClicked(data: SearchChatHistoryViewData, position: Int) = Unit
        }
        adapter.updateWithSearchKey("a", listOf(SearchChatHistoryViewData("c1", label = "c1"), SearchChatHistoryViewData("c2", label = "c2", type = SearchChatHistoryViewData.Type.Group)))
        adapter.selectedId = "c1"

        assertEquals(bg3(), color(bind(adapter, 0)))
        assertEquals(Color.TRANSPARENT, color(bind(adapter, 1)))
    }
}
