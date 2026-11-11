package com.difft.android.chat.search

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.difft.android.base.utils.LanguageUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.setSearchRowSelected
import com.difft.android.base.R
import com.difft.android.chat.databinding.SearchItemMessageBinding
import com.difft.android.chat.group.getDisplayAvatarData
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel
import util.TimeFormatter

abstract class SearchChatHistoryAdapter(private val isForMessageSearch: Boolean = false) : ListAdapter<SearchChatHistoryViewData, SearchChatHistoryViewHolder>(
    object : DiffUtil.ItemCallback<SearchChatHistoryViewData>() {
        override fun areItemsTheSame(oldItem: SearchChatHistoryViewData, newItem: SearchChatHistoryViewData): Boolean {
            
            
            return oldItem.conversationId == newItem.conversationId &&
                    oldItem.messageTimeStamp == newItem.messageTimeStamp
        }

        override fun areContentsTheSame(oldItem: SearchChatHistoryViewData, newItem: SearchChatHistoryViewData): Boolean {
            return oldItem == newItem
        }
    }) {

    private var searchKey: String = ""

    
    
    private var pendingHighlightRebind = false

    @SuppressLint("NotifyDataSetChanged")
    fun updateWithSearchKey(key: String, data: List<SearchChatHistoryViewData>) {
        if (key != searchKey) pendingHighlightRebind = true
        searchKey = key
        submitList(data) {
            
            
            if (pendingHighlightRebind) {
                pendingHighlightRebind = false
                notifyDataSetChanged()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChatHistoryViewHolder {
        return SearchChatHistoryViewHolder(parent, isForMessageSearch)
    }

    abstract fun onItemClicked(data: SearchChatHistoryViewData, position: Int)

    override fun onBindViewHolder(holder: SearchChatHistoryViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(data, searchKey) { onItemClicked(data, position) }
        holder.itemView.setSearchRowSelected(data.conversationId == selectedId)
    }

    
    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, item ->
                if (item.conversationId == old || item.conversationId == value) notifyItemChanged(index)
            }
        }
}

class SearchChatHistoryViewHolder(container: ViewGroup, private val isForMessageSearch: Boolean) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(container.context)
    SearchItemMessageBinding.inflate(inflater, container, false).root
}) {

    private val binding = SearchItemMessageBinding.bind(itemView)

    fun bind(data: SearchChatHistoryViewData, searchKey: String, onItemClick: () -> Unit) {
        binding.tvName.text = data.label
        if (isForMessageSearch) {
            binding.tvDetail.setHighLightText(data.detail?.toString(), searchKey)
        } else {
            
            
            if (data.onlyOneResult) {
                binding.tvDetail.setHighLightText(searchSnippet(data.detail?.toString().orEmpty(), searchKey), listOf(searchKey))
            } else {
                binding.tvDetail.text = data.detail
            }
            val matchTime = data.matchTime
            binding.tvTime.visibility = if (matchTime != null && matchTime > 0) View.VISIBLE else View.GONE
            if (matchTime != null && matchTime > 0) {
                val context = binding.root.context
                binding.tvTime.text = TimeFormatter.formatConversationTime(LanguageUtils.getLanguage(context).language, matchTime)
            }
        }

        binding.imageviewAvatar.visibility = View.GONE
        binding.groupAvatar.visibility = View.GONE

        if (isForMessageSearch) {
            data.contactor?.let {
                binding.imageviewAvatar.visibility = View.VISIBLE
                binding.imageviewAvatar.setAvatar(it)
            }
        } else {
            when (data.type) {
                is SearchChatHistoryViewData.Type.OneOnOne -> {
                    binding.imageviewAvatar.visibility = View.VISIBLE
                    data.contactor?.let {
                        if (it.id == globalServices.myId) {
                            binding.imageviewAvatar.showFavorites()
                            binding.tvName.text = binding.root.context.getString(R.string.chat_favorites)
                        } else {
                            binding.imageviewAvatar.setAvatar(it)
                        }
                    }
                }

                is SearchChatHistoryViewData.Type.Group -> {
                    binding.groupAvatar.visibility = View.VISIBLE
                    binding.groupAvatar.setAvatar(data.group?.getDisplayAvatarData())
                }
            }
        }


        binding.root.setOnClickListener {
            onItemClick()
        }
    }
}

data class SearchChatHistoryViewData(
    val conversationId: String,
    var type: Type = Type.OneOnOne,
    var label: CharSequence? = null,
    var detail: CharSequence? = null,
    var missedNumber: CharSequence? = null,
    var contactor: ContactorModel? = null,
    var group: GroupModel? = null,
    var onlyOneResult: Boolean = false,
    
    var messageTimeStamp: Long? = null,
    
    var matchTime: Long? = null,
) {
    sealed class Type {
        data object OneOnOne : Type()
        data object Group : Type()
    }
}
