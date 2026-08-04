package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.app.Activity
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.appScope
import kotlinx.coroutines.launch
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.databinding.ChatFragmentRecentChatListItemBinding
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.search.setHighLightText
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.LCallManager.EntryPoint
import com.difft.android.call.state.OnGoingCallStateManager
import dagger.hilt.android.EntryPointAccessors

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

            else -> throw IllegalArgumentException("Invalid view type")
        }
    }

    abstract fun onItemClicked(roomViewData: RoomViewData, position: Int)

    abstract fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int)

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

                chatHolder.itemView.setOnTouchListener { _, event ->
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
                    { onItemClicked(data, position - 2) }, // Adjust position
                    { onItemLongClicked(holder.itemView, data, position - 2, lastTouchX, lastTouchY) })
            }
        }
    }

    private var lastTouchX: Int = 0
    private var lastTouchY: Int = 0

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ListItem.SearchInput -> VIEW_TYPE_SEARCH_INPUT
            is ListItem.ChatItem -> VIEW_TYPE_CHAT_ITEM
            else -> {
                throw IllegalArgumentException("Invalid item type")
            }
        }
    }

    private var recyclerView: RecyclerView? = null
    fun updateCallBarTick() {
        updateVisibleViewHolders()
    }

    private fun updateVisibleViewHolders() {
        val rv = recyclerView ?: return
        val layoutManager = rv.layoutManager ?: return
        val first = (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: 0
        val last = (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.findLastVisibleItemPosition() ?: (itemCount - 1)

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
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
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

class RecentChatViewHolder(val activity: Activity, container: ViewGroup, val myID: String, private val isForSearch: Boolean) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(container.context)
    ChatFragmentRecentChatListItemBinding.inflate(inflater, container, false).root
}) {

    private val binding = ChatFragmentRecentChatListItemBinding.bind(itemView)
    private var currentIsSelected: Boolean = false

    /** RecyclerView width, already measured by the time onCreateViewHolder runs. */
    private val containerWidthPx: Int = container.width

    @SuppressLint("ClickableViewAccessibility")
    fun bind(searchKey: String, data: RoomViewData, isSelected: Boolean = false, onItemClick: () -> Unit, onItemLongClick: () -> Unit) {
        currentIsSelected = isSelected
        updateTextSizes(TextSizeUtil.isLarger)

        if (isForSearch && searchKey.isNotEmpty()) {
            binding.textviewLabel.setHighLightText(data.roomName.toString(), searchKey)
        } else {
            binding.textviewLabel.text = data.roomName
        }

        binding.textviewTimer.visibility = View.GONE
        data.messageExpiry?.let {
            if (it > 0L) {
                binding.textviewTimer.visibility = View.VISIBLE
                val text = " [" + it.toArchiveTimeDisplayText() + "]"
                binding.textviewTimer.text = text
            }
        }

        binding.imageviewGroupAvatar.visibility = View.GONE
        binding.imageviewAvatar.visibility = View.GONE

        when (data.type) {
            is RoomViewData.Type.OneOnOne -> {
                binding.imageviewAvatar.visibility = View.VISIBLE
                if (data.roomId == myID) {
                    binding.imageviewBotBadge.isVisible = false
                    binding.imageviewAvatar.showFavorites()
                    if (searchKey.isNotEmpty()) {
                        binding.textviewLabel.setHighLightText(
                            binding.root.context.getString(com.difft.android.base.R.string.chat_favorites),
                            searchKey
                        )
                    } else {
                        binding.textviewLabel.text =
                            binding.root.context.getString(com.difft.android.base.R.string.chat_favorites)
                    }
                } else {
                    binding.imageviewBotBadge.isVisible = data.roomId.isOfficialAccount()
                    val contactAvatar = (data.remarkAvatarJson?.takeIf { it.isNotEmpty() } ?: data.roomAvatarJson)
                        ?.getContactAvatarData()
                    binding.imageviewAvatar.setAvatar(
                        contactAvatar?.getContactAvatarUrl(),
                        contactAvatar?.encKey,
                        data.roomName?.firstOrNull()?.toString() ?: "#",
                        data.roomId
                    )
                }
            }

            is RoomViewData.Type.Group -> {
                binding.imageviewGroupAvatar.visibility = View.VISIBLE
                binding.imageviewGroupAvatar.setAvatar(data.roomAvatarJson?.getAvatarData(), true, data.groupMembersNumber, data.roomId)
                binding.imageviewBotBadge.isVisible = false
            }
        }

        if (data.isInstantCall) {
            binding.imageviewAvatar.visibility = View.VISIBLE
            binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_instant_call)
        }
        binding.textviewDetail.isVisible = !data.isInstantCall

        val bgColorRes = when {
            isSelected -> com.difft.android.base.R.color.bg3
            !isForSearch && data.isPinned -> com.difft.android.base.R.color.bg2
            else -> com.difft.android.base.R.color.bg1
        }
        binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, bgColorRes))

        binding.textviewDate.text = data.lastActiveTimeText
        applyDetailRow(data)
        binding.textviewMissedNumber.text =
            if (data.unreadMessageNum > 99) "99+" else data.unreadMessageNum.toString()
        applyTagRow(data)

        showMissingNumber(data.unreadMessageNum, data.isMuted)

        binding.root.setOnClickListener {
            onItemClick()
        }
        binding.root.setOnLongClickListener {
            onItemLongClick()
            true
        }
        updateCallBarDuration(data)
    }

    private fun updateTextSizes(isLarger: Boolean) {
        if (isLarger) {
            binding.textviewLabel.textSize = 24f
            binding.textviewTimer.textSize = 24f
            binding.textviewDate.textSize = 21f
            binding.textviewAt.textSize = 21f
            binding.textviewDetail.textSize = 21f
            binding.textviewMissedNumber.textSize = 12f
        } else {
            binding.textviewLabel.textSize = 16f
            binding.textviewTimer.textSize = 16f
            binding.textviewDate.textSize = 14f
            binding.textviewAt.textSize = 14f
            binding.textviewDetail.textSize = 14f
            binding.textviewMissedNumber.textSize = 10f
        }
    }

    /**
     * Content only: the draft body when a draft exists, otherwise the message preview. A draft is
     * "present" only when non-null AND non-empty — the same test [applyTagRow] uses for the
     * `[Draft]` tag, so the two can never disagree about whether a draft EXISTS.
     *
     * They can still disagree about labelling: `[Draft]` is the first tag width degradation drops,
     * and this row is not degraded, so a crowded row shows the draft body with no `[Draft]` label.
     * Deliberate — the body is the useful part. Making them inseparable would mean moving the draft
     * indicator into this row as a prefix, which is a design change.
     */
    private fun applyDetailRow(data: RoomViewData) {
        val context = binding.root.context
        binding.textviewDetail.text =
            data.draftPreview?.takeIf { it.isNotEmpty() } ?: data.lastDisplayContent
        binding.textviewDetail.setTextColor(
            ContextCompat.getColor(context, detailColorRes(data.unreadMessageNum, data.isMuted))
        )
    }

    /**
     * Assembles the preview tag run into a single TextView and degrades it to the available width.
     *
     * Precondition: [updateTextSizes] has already run — `textviewAt.paint` carries the final text
     * size, and measuring before it would under-estimate at the larger size and drop too few tags.
     *
     * The tag run is derived solely from [RoomViewData] fields; it must never query the message or
     * room tables, which would put a per-row DB read on the list-refresh path.
     *
     * Colour comes solely from `android:textColor="@color/t.error"` in the layout.
     */
    private fun applyTagRow(data: RoomViewData) {
        val context = binding.root.context
        val labels = ChatListTagLabels(
            criticalAlert = context.getString(R.string.chat_list_critical_alert),
            sendFailed = context.getString(R.string.chat_list_send_failed),
            atYou = context.getString(R.string.chat_list_at_you),
            atAll = context.getString(R.string.chat_list_at_all),
            draft = context.getString(R.string.chat_list_draft),
        )
        val all = buildTagSegments(
            criticalAlertType = data.criticalAlertType,
            sendStatus = data.sendStatus,
            mentionType = data.mentionType,
            hasDraft = !data.draftPreview.isNullOrEmpty(),
            labels = labels,
        )
        val rowWidthPx = resolveRowWidthPx(
            itemViewWidthPx = itemView.width,
            containerWidthPx = containerWidthPx,
            displayWidthPx = context.resources.displayMetrics.widthPixels,
        )
        val visible = selectVisibleTags(
            tags = all,
            availableWidthPx = computeTagAvailableWidthPx(
                rowWidthPx = rowWidthPx,
                density = context.resources.displayMetrics.density,
                hasUnreadBadge = data.unreadMessageNum != 0,
                // Mirrors updateCallBarDuration()'s visibility test without reordering it: that
                // method also rewrites the row background and the click listener.
                hasCallBar = !data.callData?.roomId.isNullOrEmpty(),
                isLargerText = TextSizeUtil.isLarger,
            ),
            measureText = binding.textviewAt.paint::measureText,
        )
        // Driven by `visible`, not `all`: a run made entirely of droppable tags can degrade to
        // nothing, and an empty-but-VISIBLE TextView would still consume its marginEnd.
        binding.textviewAt.isVisible = visible.isNotEmpty()
        binding.textviewAt.text = joinTags(visible)
    }

    private fun showMissingNumber(missedNumber: Int, isMuted: Boolean) {
        if (missedNumber == 0) {
            binding.textviewMissedNumber.visibility = View.GONE
        } else {
            binding.textviewMissedNumber.visibility = View.VISIBLE
            if (isMuted) {
                binding.textviewMissedNumber.setBackgroundResource(R.drawable.chat_missing_number_bg_muted)
            } else {
                binding.textviewMissedNumber.setBackgroundResource(R.drawable.chat_missing_number_bg)
            }
        }
    }

    private fun setupCallingJoinButton(
        view: TextView,
        showTime: String?
    ) {
        view.text = if(!TextUtils.isEmpty(showTime)) showTime else view.context.getString(R.string.join)
    }


    fun updateCallBarDuration(data: RoomViewData) {
        val callData = data.callData
        if (callData != null) {
            val roomId = callData.roomId
            if (!roomId.isNullOrEmpty()) {
                if(!currentIsSelected && !data.isPinned) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg2))
                }
                binding.callBarDuration.isVisible = true
                val callStateManager = getCallStateManager()
                val showTime = callStateManager.getCallingTime(roomId)
                setupCallingJoinButton(binding.callBarDuration, showTime)
                binding.callBarDuration.setOnClickListener {
                    if (!callStateManager.isInCalling()) {
                        L.i { "[call] CallBar Joining call for roomId:${roomId}." }
                        appScope.launch {
                            val status = LCallManager.joinCall(activity, callData)
                            if (!status) {
                                L.e { "[Call] CallBar join call failed." }
                                ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                            }
                        }
                    } else {
                        if (callStateManager.getCurrentRoomId() == roomId) {
                            L.i { "[call] CallBar Bringing back current call for roomId:${roomId}." }
                            LCallManager.bringCallScreenToFront(ApplicationHelper.instance.applicationContext)
                        } else {
                            ToastUtil.show(com.difft.android.call.R.string.call_newcall_tip)
                        }
                    }
                }
            }
        } else {
            if(!currentIsSelected && !data.isPinned) {
                binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg1))
            }
            binding.callBarDuration.isVisible = false
        }
    }

    private fun getCallStateManager(): OnGoingCallStateManager {
        val entryPoint = EntryPointAccessors.fromApplication<EntryPoint>(
            activity.applicationContext
        )
        return entryPoint.onGoingCallStateManager
    }

}

sealed class ListItem {
    object SearchInput : ListItem()
    data class ChatItem(val data: RoomViewData) : ListItem()

    fun chatItemData(): RoomViewData? {
        return (this as? ChatItem)?.data
    }
}