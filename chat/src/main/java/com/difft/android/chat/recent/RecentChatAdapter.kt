package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.app.Activity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R

abstract class RecentChatAdapter(val activity: Activity, val isForSearch: Boolean = false) : ListAdapter<ListItem, RecyclerView.ViewHolder>(
    object : DiffUtil.ItemCallback<ListItem>() {
        override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return if (oldItem is ListItem.ChatItem && newItem is ListItem.ChatItem) {
                oldItem.data.roomId == newItem.data.roomId
            } else {
                oldItem == newItem
            }
        }

        override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return oldItem == newItem
        }
    }) {
    companion object {
        const val VIEW_TYPE_SEARCH_INPUT = 0
        const val VIEW_TYPE_E2EE_FOOTER = 1
        const val VIEW_TYPE_CHAT_ITEM = 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_SEARCH_INPUT -> {
                val inflater = LayoutInflater.from(parent.context)
                val view = inflater.inflate(R.layout.chat_fragment_search_input_item, parent, false)
                SearchInputViewHolder(view)
            }

            VIEW_TYPE_CHAT_ITEM -> {
                RecentChatViewHolder(activity, parent, globalServices.myId, isForSearch)
            }

            VIEW_TYPE_E2EE_FOOTER -> {
                val inflater = LayoutInflater.from(parent.context)
                val view = inflater.inflate(R.layout.chat_fragment_recent_chat_footer_item, parent, false)
                RecentChatFooterViewHolder(view)
            }

            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    abstract fun onItemClicked(roomViewData: RoomViewData, position: Int)

    abstract fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int)

    /**
     * Whole-row tap target for the list-footer E2EE hint. Deliberately NOT abstract:
     * [com.difft.android.search.SearchActivity]'s `RecentChatAdapter` subclass never submits
     * [ListItem.E2eeFooter] and must not be forced to implement a callback it can never trigger.
     */
    open fun onFooterClicked() = Unit

    /**
     * Swipe / long-press actions (chat-list gesture design). `open` with no-op defaults for the
     * same reason as [onFooterClicked]: the search adapter disables swiping and never fires them.
     */
    open fun onReadToggle(roomViewData: RoomViewData) = Unit

    open fun onPinToggle(roomViewData: RoomViewData) = Unit

    open fun onDeleteRequested(roomViewData: RoomViewData) = Unit

    /** At most one row is open at a time; scrolling or opening another row closes it. */
    private var openSwipe: ChatListSwipeLayout? = null

    private val swipeCallback = object : ChatListSwipeLayout.Callback {
        override fun onOpened(layout: ChatListSwipeLayout) {
            if (openSwipe !== layout) openSwipe?.close()
            openSwipe = layout
        }

        override fun onClosed(layout: ChatListSwipeLayout) {
            if (openSwipe === layout) openSwipe = null
        }
    }

    private val closeOnScroll = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_DRAGGING) openSwipe?.close()
        }

        // Programmatic scrolls (jump to next unread, scroll to top) never enter DRAGGING.
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) openSwipe?.close()
        }
    }

    fun closeOpenSwipe() {
        openSwipe?.close()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.SearchInput -> {
                val searchHolder = holder as SearchInputViewHolder
                searchHolder.bind()
            }

            is ListItem.ChatItem -> {
                val chatHolder = holder as RecentChatViewHolder
                val data = item.data

                // The foreground owns the row's clicks, so the press position must be read there
                // (the swipe root never sees DOWN on a closed row).
                chatHolder.touchTarget.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        lastTouchX = event.rawX.toInt()
                        lastTouchY = event.rawY.toInt()
                    }
                    false
                }

                chatHolder.bind(
                    searchKey,
                    data,
                    isSelected = selectedId != null && data.roomId == selectedId,
                    onItemClick = { onItemClicked(data, position - 2) }, // Adjust position
                    onItemLongClick = {
                        closeOpenSwipe()
                        onItemLongClicked(holder.itemView, data, position - 2, lastTouchX, lastTouchY)
                    },
                    swipe = if (isForSearch) null else RecentChatViewHolder.SwipeActions(
                        callback = swipeCallback,
                        onReadToggle = { onReadToggle(data) },
                        onPinToggle = { onPinToggle(data) },
                        onDelete = { onDeleteRequested(data) },
                    ),
                )
            }

            is ListItem.E2eeFooter -> {
                val footerHolder = holder as RecentChatFooterViewHolder
                footerHolder.bind(onFooterClicked = { onFooterClicked() })
            }
        }
    }

    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.SearchInput -> VIEW_TYPE_SEARCH_INPUT
            is ListItem.ChatItem -> VIEW_TYPE_CHAT_ITEM
            is ListItem.E2eeFooter -> VIEW_TYPE_E2EE_FOOTER
        }
        // No `else` branch: ListItem is sealed and this `when` is an EXPRESSION, so the compiler
        // enforces exhaustiveness — a future ListItem subtype without a branch is now a COMPILE
        // error, not the runtime IllegalArgumentException the old `else -> throw` produced.
    }

    private var recyclerView: RecyclerView? = null
    fun updateCallBarTick() {
        updateVisibleViewHolders()
    }

    private fun updateVisibleViewHolders() {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        // Both return NO_POSITION before a layout settles — they delegate to the same helper, so
        // they are never -1 singly. The earlier `?: 0` / `?: (itemCount - 1)` guarded the layout
        // manager cast, not these ints, leaving -1..-1 to run one iteration at position -1, where
        // a non-null holder match would reach getItem(-1) and throw.
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return

        for (i in first..last) {
            (rv.findViewHolderForAdapterPosition(i) as? RecentChatViewHolder)?.let { holder ->
                getItem(i)?.also { data ->
                    val recentChatViewData = (data as? ListItem.ChatItem)?.data ?: return@also
                    holder.updateCallBarDuration(recentChatViewData)
                }
            }
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        recyclerView.addOnScrollListener(closeOnScroll)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeOnScrollListener(closeOnScroll)
        openSwipe = null
        this.recyclerView = null
    }

    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val oldId = field
            field = value
            currentList.forEachIndexed { index, item ->
                val itemId = (item as? ListItem.ChatItem)?.data?.roomId
                if (itemId != null && (itemId == oldId || itemId == value)) {
                    notifyItemChanged(index)
                }
            }
        }

    private var searchKey: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun setOrUpdateSearchKey(key: String) {
        searchKey = key
        notifyDataSetChanged()
    }
}

class SearchInputViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun bind() {
        // Initialize your search input view here
        // For example, set up listeners or bind data if needed
    }
}

sealed class ListItem {
    object SearchInput : ListItem()
    data class ChatItem(val data: RoomViewData) : ListItem()

    /** Stateless list-footer singleton — the same instance is referentially equal across every
     * `submitList` call, so [DiffUtil.ItemCallback.areItemsTheSame]'s `oldItem == newItem`
     * fallthrough is unconditionally true for it, giving "no change" for free. */
    object E2eeFooter : ListItem()

    fun chatItemData(): RoomViewData? {
        return (this as? ChatItem)?.data
    }
}
