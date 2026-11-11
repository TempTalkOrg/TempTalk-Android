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
        override fun areItemsTheSame(oldItem: ListItem, newItem: ListItem): Boolean = oldItem.isSameRow(newItem)

        override fun areContentsTheSame(oldItem: ListItem, newItem: ListItem): Boolean {
            return oldItem == newItem
        }
    }) {
    companion object {
        const val VIEW_TYPE_SEARCH_INPUT = 0
        const val VIEW_TYPE_E2EE_FOOTER = 1
        const val VIEW_TYPE_CHAT_ITEM = 2
        const val VIEW_TYPE_FOCUS_CHAT = 3
        const val VIEW_TYPE_FOLDED_GROUPS = 4
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

            VIEW_TYPE_FOCUS_CHAT -> RecentChatViewHolder(activity, parent, globalServices.myId, isForSearch, isFocusCard = true)

            VIEW_TYPE_FOLDED_GROUPS -> FoldedGroupsViewHolder(parent)

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

    




    open fun onFooterClicked() = Unit

    



    open fun onReadToggle(roomViewData: RoomViewData) = Unit

    open fun onPinToggle(roomViewData: RoomViewData) = Unit

    open fun onDeleteRequested(roomViewData: RoomViewData) = Unit

    
    open fun onFocusToggle(roomViewData: RoomViewData) = Unit

    
    open fun onFoldedGroupsClicked() = Unit

    open fun onFoldedGroupsLongClicked(view: View, touchX: Int, touchY: Int) = Unit

    open fun onFoldedGroupsEndFocus() = Unit

    
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

        
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy != 0) openSwipe?.close()
        }
    }

    fun closeOpenSwipe() {
        openSwipe?.close()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ListItem.SearchInput -> {
                val searchHolder = holder as SearchInputViewHolder
                searchHolder.bind()
            }

            is ListItem.ChatItem -> bindChat(holder as RecentChatViewHolder, item.data, position)

            is ListItem.FocusChat -> bindChat(holder as RecentChatViewHolder, item.data, position)

            is ListItem.FoldedGroups -> {
                val foldedHolder = holder as FoldedGroupsViewHolder
                foldedHolder.bind(
                    summary = item.summary,
                    swipeCallback = if (isForSearch) null else swipeCallback,
                    onClick = { onFoldedGroupsClicked() },
                    onLongClick = {
                        closeOpenSwipe()
                        onFoldedGroupsLongClicked(holder.itemView, foldedHolder.lastTouchX, foldedHolder.lastTouchY)
                    },
                    onEndFocus = { onFoldedGroupsEndFocus() },
                )
            }

            is ListItem.E2eeFooter -> {
                val footerHolder = holder as RecentChatFooterViewHolder
                footerHolder.bind(onFooterClicked = { onFooterClicked() })
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindChat(chatHolder: RecentChatViewHolder, data: RoomViewData, position: Int) {
        
        
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
            onItemClick = { onItemClicked(data, position - 2) }, 
            onItemLongClick = {
                closeOpenSwipe()
                onItemLongClicked(chatHolder.itemView, data, position - 2, lastTouchX, lastTouchY)
            },
            swipe = if (isForSearch) null else RecentChatViewHolder.SwipeActions(
                callback = swipeCallback,
                onReadToggle = { onReadToggle(data) },
                onPinToggle = { onPinToggle(data) },
                onDelete = { onDeleteRequested(data) },
                onFocusToggle = { onFocusToggle(data) },
            ),
        )
    }

    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.SearchInput -> VIEW_TYPE_SEARCH_INPUT
            is ListItem.ChatItem -> VIEW_TYPE_CHAT_ITEM
            is ListItem.FocusChat -> VIEW_TYPE_FOCUS_CHAT
            is ListItem.FoldedGroups -> VIEW_TYPE_FOLDED_GROUPS
            is ListItem.E2eeFooter -> VIEW_TYPE_E2EE_FOOTER
        }
        
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
        
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return

        for (i in first..last) {
            (rv.findViewHolderForAdapterPosition(i) as? RecentChatViewHolder)?.let { holder ->
                getItem(i)?.also { data ->
                    val recentChatViewData = data.chatItemData() ?: return@also
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
                val itemId = item.chatItemData()?.roomId
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
        
    }
}
