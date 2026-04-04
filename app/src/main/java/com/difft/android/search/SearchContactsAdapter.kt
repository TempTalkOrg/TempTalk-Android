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
        if (data.id == globalServices.myId) {
            binding.avatarView.showFavorites()
            binding.tvName.setHighLightText(binding.root.context.getString(com.difft.android.base.R.string.chat_favorites), searchKey)
        } else {
            binding.avatarView.setAvatar(data)
            binding.tvName.setHighLightText(data.getDisplayNameForUI(), searchKey)
        }

        binding.tvInfo1.visibility = View.GONE
        binding.tvInfo2.visibility = View.GONE

        val infoList = listOf(data.email)
            .sortedByDescending { it?.contains(searchKey, ignoreCase = true) }
            .filter { !it.isNullOrEmpty() }


        if (infoList.size == 1) {
            val text = ContactorUtil.getLastPart(infoList[0].toString())
            binding.tvInfo1.visibility = View.VISIBLE
            binding.tvInfo1.setHighLightText(text, searchKey)
            binding.tvInfo2.visibility = View.GONE
        }
        if (infoList.size > 1) {
            val text1 = ContactorUtil.getLastPart(infoList[0].toString())
            val text2 = ContactorUtil.getLastPart(infoList[1].toString())
            binding.tvInfo1.visibility = View.VISIBLE
            binding.tvInfo1.setHighLightText(text1, searchKey)
            binding.tvInfo2.visibility = View.VISIBLE
            binding.tvInfo2.setHighLightText(text2, searchKey)
        }

        binding.tvTime.visibility = View.GONE

        binding.root.setOnClickListener(onItemClickListener)
    }

}