package com.difft.android.chat.search

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.databinding.SearchItemMessageBinding
import com.difft.android.chat.group.getAvatarData
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.models.GroupModel

abstract class SearchChatHistoryAdapter(private val isForMessageSearch: Boolean = false) : ListAdapter<SearchChatHistoryViewData, SearchChatHistoryViewHolder>(
    object : DiffUtil.ItemCallback<SearchChatHistoryViewData>() {
        override fun areItemsTheSame(oldItem: SearchChatHistoryViewData, newItem: SearchChatHistoryViewData): Boolean {
            return oldItem.conversationId == newItem.conversationId
        }

        override fun areContentsTheSame(oldItem: SearchChatHistoryViewData, newItem: SearchChatHistoryViewData): Boolean {
            return oldItem == newItem
        }
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchChatHistoryViewHolder {
        return SearchChatHistoryViewHolder(parent, isForMessageSearch)
    }

    abstract fun onItemClicked(data: SearchChatHistoryViewData, position: Int)

    override fun onBindViewHolder(holder: SearchChatHistoryViewHolder, position: Int) {
        val data = getItem(position)

        holder.bind(data) { onItemClicked(data, position) }
    }
}

class SearchChatHistoryViewHolder(container: ViewGroup, private val isForMessageSearch: Boolean) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(container.context)
    SearchItemMessageBinding.inflate(inflater, container, false).root
}) {

    private val binding = SearchItemMessageBinding.bind(itemView)

    fun bind(data: SearchChatHistoryViewData, onItemClick: () -> Unit) {
        binding.tvName.text = data.label
        binding.tvDetail.text = data.detail

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
                    binding.groupAvatar.setAvatar(data.group?.avatar?.getAvatarData())
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
    var messageTimeStamp: Long? = null  //只包含一条搜索结果时的消息id
) {
    sealed class Type {
        data object OneOnOne : Type()
        data object Group : Type()
    }
}
