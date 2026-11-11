package com.difft.android.search

import android.view.ViewGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.setSearchRowSelected
import com.difft.android.chat.group.GroupUIData
import com.difft.android.chat.search.SearchChatHistoryViewData
import com.difft.android.chat.search.SearchChatHistoryViewHolder
import com.difft.android.search.mvi.GlobalSearchContract.Scope
import com.difft.android.search.mvi.GlobalSearchContract.Section
import com.difft.android.search.mvi.SearchRow
import org.difft.app.database.models.ContactorModel






class GlobalSearchResultsAdapter(private val listener: Listener) : ListAdapter<SearchRow, RecyclerView.ViewHolder>(DIFF) {

    interface Listener {
        fun onScopeSelected(scope: Scope)
        fun onContactClicked(contact: ContactorModel)
        fun onGroupClicked(group: GroupUIData)
        fun onHistoryClicked(history: SearchChatHistoryViewData)
        fun onViewAllClicked(section: Section)
        fun onMoreClicked(section: Section)
    }

    
    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, row ->
                val id = row.conversationId()
                if (id != null && (id == old || id == value)) notifyItemChanged(index)
            }
        }

    
    val emptyMinHeightPx = mutableIntStateOf(0)

    
    fun showsDivider(position: Int): Boolean = when (val row = getItem(position)) {
        is SearchRow.Contact -> row.divider
        is SearchRow.Group -> row.divider
        is SearchRow.Message -> row.divider
        else -> false
    }

    
    fun textColumnStartDp(position: Int): Int =
        if (getItemViewType(position) == TYPE_MESSAGE) MESSAGE_TEXT_START_DP else PERSON_TEXT_START_DP

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is SearchRow.Contact -> TYPE_CONTACT
        is SearchRow.Group -> TYPE_GROUP
        is SearchRow.Message -> TYPE_MESSAGE
        else -> TYPE_COMPOSE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_CONTACT -> SearchContactViewHolder(parent)
        TYPE_GROUP -> SearchGroupItemViewHolder(parent)
        TYPE_MESSAGE -> SearchChatHistoryViewHolder(parent, isForMessageSearch = false)
        else -> ComposeRowHolder(ComposeView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        when (row) {
            is SearchRow.Contact -> (holder as SearchContactViewHolder).bind(row.query, row.contact) { listener.onContactClicked(row.contact) }
            is SearchRow.Group -> {
                (holder as SearchGroupItemViewHolder).bind(row.query, row.group)
                holder.itemView.setOnClickListener { listener.onGroupClicked(row.group) }
            }
            is SearchRow.Message -> (holder as SearchChatHistoryViewHolder).bind(row.history, row.query) { listener.onHistoryClicked(row.history) }
            else -> (holder as ComposeRowHolder).bind(row)
        }
        row.conversationId()?.let { holder.itemView.setSearchRowSelected(it == selectedId) }
    }

    private fun SearchRow.conversationId(): String? = when (this) {
        is SearchRow.Contact -> contact.id
        is SearchRow.Group -> group.gid
        is SearchRow.Message -> history.conversationId
        else -> null
    }

    private inner class ComposeRowHolder(private val view: ComposeView) : RecyclerView.ViewHolder(view) {
        fun bind(row: SearchRow) {
            
            view.setContent {
                DifftTheme(applyWindowBackground = false) {
                    when (row) {
                        is SearchRow.Scopes -> SearchScopePills(row.selected, listener::onScopeSelected)
                        is SearchRow.Header -> SearchSectionHeader(row.section)
                        is SearchRow.ViewAll -> SearchDestinationRow(row.count) { listener.onViewAllClicked(row.section) }
                        is SearchRow.More -> SearchDestinationRow(null) { listener.onMoreClicked(row.section) }
                        is SearchRow.Empty -> SearchEmptyState(row.query, emptyMinHeightPx.intValue)
                        is SearchRow.Contact, is SearchRow.Group, is SearchRow.Message -> Unit
                    }
                }
            }
        }
    }

    companion object {
        private const val TYPE_COMPOSE = 0
        private const val TYPE_CONTACT = 1
        private const val TYPE_GROUP = 2
        private const val TYPE_MESSAGE = 3

        
        private const val PERSON_TEXT_START_DP = 80

        
        private const val MESSAGE_TEXT_START_DP = 76

        private val DIFF = object : DiffUtil.ItemCallback<SearchRow>() {
            override fun areItemsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: SearchRow, newItem: SearchRow): Boolean = oldItem == newItem
        }
    }
}
