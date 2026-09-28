package com.difft.android.call.ui.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import kotlinx.coroutines.CoroutineScope
import org.difft.app.database.models.ContactorModel
import com.difft.android.base.widget.DifftCheckBoxView

/**
 * 联系人选择项数据模型
 */
data class MeetingInviteContactItem(
    val contact: ContactorModel,
    val isSelected: Boolean,
    val isInMeeting: Boolean
)

/**
 * 联系人列表适配器（支持多选）
 */
class MeetingInviteContactsAdapter(
    private val onContactClick: (String) -> Unit,
    private val lifecycleScope: CoroutineScope
) : ListAdapter<MeetingInviteContactItem, MeetingInviteContactsAdapter.ContactViewHolder>(
    object : DiffUtil.ItemCallback<MeetingInviteContactItem>() {
        override fun areItemsTheSame(
            oldItem: MeetingInviteContactItem,
            newItem: MeetingInviteContactItem
        ): Boolean = oldItem.contact.id == newItem.contact.id

        override fun areContentsTheSame(
            oldItem: MeetingInviteContactItem,
            newItem: MeetingInviteContactItem
        ): Boolean = oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_invite_contact, parent, false)
        return ContactViewHolder(view, onContactClick, lifecycleScope)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ContactViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    class ContactViewHolder(
        itemView: View,
        private val onContactClick: (String) -> Unit,
        lifecycleScope: CoroutineScope
    ) : RecyclerView.ViewHolder(itemView) {

        private val checkbox: DifftCheckBoxView = itemView.findViewById(R.id.checkbox)
        private val textViewName: TextView = itemView.findViewById(R.id.textview_name)
        private val avatar = InviteRowAvatarBinder.create(
            container = itemView.findViewById(R.id.avatar_container),
            scope = lifecycleScope,
            logTag = "MeetingInviteContacts",
        )

        fun bind(item: MeetingInviteContactItem) {
            textViewName.text = item.contact.getDisplayNameForUI()

            // Already in the meeting: shown checked but disabled, not selectable.
            checkbox.isEnabled = !item.isInMeeting
            checkbox.isChecked = item.isInMeeting || item.isSelected

            avatar.bind(item.contact.id, AvatarData.FromContactor(item.contact))

            itemView.setOnClickListener {
                if (!item.isInMeeting) {
                    onContactClick(item.contact.id)
                }
            }
        }

        fun recycle() = avatar.cancel()
    }
}
