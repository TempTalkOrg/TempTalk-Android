package com.difft.android.search

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.messageserialization.db.store.getDisplayNameForUI

import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.setSearchRowSelected
import com.difft.android.chat.R
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.search.setHighLightText
import com.difft.android.databinding.SearchItemContactBinding
import org.difft.app.database.models.ContactorModel


abstract class SearchContactAdapter : ListAdapter<ContactorModel, SearchContactViewHolder>(
    object : DiffUtil.ItemCallback<ContactorModel>() {
        override fun areItemsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ContactorModel, newItem: ContactorModel): Boolean =
            oldItem == newItem
    }) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchContactViewHolder {
        return SearchContactViewHolder(parent)
    }

    abstract fun onContactClicked(contact: ContactorModel, position: Int)

    override fun onBindViewHolder(holder: SearchContactViewHolder, position: Int) {
        val data = getItem(position)
        holder.bind(searchKey, data) {
            onContactClicked(data, position)
        }
        holder.itemView.setSearchRowSelected(data.id == selectedId)
    }

    
    var selectedId: String? = null
        set(value) {
            if (field == value) return
            val old = field
            field = value
            currentList.forEachIndexed { index, item -> if (item.id == old || item.id == value) notifyItemChanged(index) }
        }

    private var searchKey: String = ""

    @SuppressLint("NotifyDataSetChanged")
    fun setOrUpdateSearchKey(key: String) {
        searchKey = key
        notifyDataSetChanged()
    }
}

class SearchContactViewHolder(parentView: ViewGroup) : RecyclerView.ViewHolder(run {
    val inflater = LayoutInflater.from(parentView.context)
    SearchItemContactBinding.inflate(inflater, parentView, false).root
}) {

    private var binding: SearchItemContactBinding = SearchItemContactBinding.bind(itemView)

    fun bind(searchKey: String, data: ContactorModel, onItemClickListener: View.OnClickListener) {
        val title = if (data.id == globalServices.myId) {
            binding.avatarView.showFavorites()
            binding.root.context.getString(com.difft.android.base.R.string.chat_favorites)
        } else {
            binding.avatarView.setAvatar(data)
            data.getDisplayNameForUI()
        }
        binding.tvName.setHighLightText(title, searchKey)

        
        
        val details = listOfNotNull(data.remark, data.email?.let { ContactorUtil.getLastPart(it) })
            .filter { it.isNotEmpty() && it != title }
            .take(2)
        listOf(binding.tvInfo1, binding.tvInfo2).forEachIndexed { index, view ->
            val text = details.getOrNull(index)
            view.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null) view.setHighLightText(text, searchKey)
        }

        binding.tvTime.visibility = View.GONE

        binding.root.setOnClickListener(onItemClickListener)
    }

}