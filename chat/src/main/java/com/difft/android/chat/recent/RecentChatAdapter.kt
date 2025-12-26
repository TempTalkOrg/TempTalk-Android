package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.databinding.ChatFragmentRecentChatListItemBinding
import com.difft.android.chat.group.getAvatarData
import com.difft.android.chat.search.setHighLightText
import com.difft.android.chat.setting.archive.toArchiveTimeDisplayText
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import java.util.concurrent.TimeUnit
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
    private var updateDisposable: Disposable? = null

    fun startUpdateCallBar() {
        updateDisposable = Observable.interval(1, 1, TimeUnit.SECONDS)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe {
                updateVisibleViewHolders()
            }
    }

    fun stopUpdateCallBar() {
        updateDisposable?.dispose()
    }

    private fun updateVisibleViewHolders() {
        for (i in 0 until itemCount) {
            (recyclerView?.findViewHolderForAdapterPosition(i) as? RecentChatViewHolder)?.let { holder ->
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

    @SuppressLint("ClickableViewAccessibility")
    fun bind(searchKey: String, data: RoomViewData, onItemClick: () -> Unit, onItemLongClick: () -> Unit) {
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
                    binding.imageviewAvatar.showFavorites()
                    if (searchKey.isNotEmpty()) {
                        binding.textviewLabel.setHighLightText(
                            binding.root.context.getString(R.string.chat_favorites),
                            searchKey
                        )
                    } else {
                        binding.textviewLabel.text =
                            binding.root.context.getString(R.string.chat_favorites)
                    }
                } else {
                    val contactAvatar = data.roomAvatarJson?.getContactAvatarData()
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
            }
        }

        if (data.isInstantCall) {
            binding.imageviewAvatar.visibility = View.VISIBLE
            binding.imageviewAvatar.setAvatar(com.difft.android.base.R.drawable.base_ic_instant_call)
        }
        binding.textviewDetail.isVisible = !data.isInstantCall

        if (!isForSearch && data.isPinned) {
            binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg2))
        } else {
            binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg1))
        }

        binding.textviewDate.text = data.lastActiveTimeText
        if (!data.draftPreview.isNullOrEmpty()) {
            binding.textviewDetail.text = applyDraftStyle(data.draftPreview, binding.root.context)
        } else {
            binding.textviewDetail.text = data.lastDisplayContent
            if (data.unreadMessageNum != 0 && !data.isMuted) {
                binding.textviewDetail.setTextColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.t_primary))
            } else {
                binding.textviewDetail.setTextColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.t_third))
            }
        }
        binding.textviewMissedNumber.text =
            if (data.unreadMessageNum > 99) "99+" else data.unreadMessageNum.toString()

        // 构建标签文本: Critical Alert 在最前面，然后是 mention
        val alertTagBuilder = StringBuilder()

        // 检查是否有 Critical Alert
        if (data.criticalAlertType == difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT) {
            alertTagBuilder.append(binding.root.context.getString(R.string.chat_list_critical_alert))
        }

        // 检查是否有 mention
        when (data.mentionType) {
            MENTIONS_TYPE_ME -> {
                if (alertTagBuilder.isNotEmpty()) alertTagBuilder.append(" ")
                alertTagBuilder.append(binding.root.context.getString(R.string.chat_list_at_you))
            }
            MENTIONS_TYPE_ALL -> {
                if (alertTagBuilder.isNotEmpty()) alertTagBuilder.append(" ")
                alertTagBuilder.append(binding.root.context.getString(R.string.chat_list_at_all))
            }
        }

        // 设置显示
        if (alertTagBuilder.isNotEmpty()) {
            binding.textviewAt.visibility = View.VISIBLE
            binding.textviewAt.text = alertTagBuilder.toString()
        } else {
            binding.textviewAt.visibility = View.GONE
        }

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

    private fun applyDraftStyle(content: String, context: Context): SpannableString {
        val draftPrefix = "[${context.getString(R.string.chat_draft)}] "
        val fullText = draftPrefix + content  // Combine prefix with the actual message
        val spannable = SpannableString(fullText)
        // Apply red color to the '[Draft] ' prefix
        spannable.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(context, R.color.red_500)),
            0, draftPrefix.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
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
                if(!data.isPinned) {
                    binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, com.difft.android.base.R.color.bg2))
                }
                binding.callBarDuration.isVisible = true
                val showTime = LCallManager.callingTime.value?.takeIf { it.first == roomId }?.second
                setupCallingJoinButton(binding.callBarDuration, showTime)
                binding.callBarDuration.setOnClickListener {
                    val callStateManager = getCallStateManager()
                    if (!callStateManager.isInCalling()) {
                        L.i { "[call] CallBar Joining call for roomId:${roomId}." }
                        LCallManager.joinCall(activity, callData) { status ->
                            if(!status) {
                                L.e { "[Call] CallBar join call failed." }
                                ToastUtil.show(com.difft.android.call.R.string.call_join_failed_tip)
                            }
                        }
                    } else {
                        if (callStateManager.getCurrentRoomId() == roomId) {
                            L.i { "[call] CallBar Bringing back current call for roomId:${roomId}." }
                            LCallManager.bringInCallScreenBack(ApplicationHelper.instance.applicationContext)
                        } else {
                            ToastUtil.show(com.difft.android.call.R.string.call_newcall_tip)
                        }
                    }
                }
            }
        } else {
            if(!data.isPinned) {
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