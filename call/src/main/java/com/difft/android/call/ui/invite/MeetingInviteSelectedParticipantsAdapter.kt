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

data class MeetingInviteSelectedParticipantItem(
    val contact: ContactorModel
)

class MeetingInviteSelectedParticipantsAdapter(
    private val onParticipantClick: (String) -> Unit,
    private val lifecycleScope: CoroutineScope
) : ListAdapter<MeetingInviteSelectedParticipantItem, MeetingInviteSelectedParticipantsAdapter.ParticipantViewHolder>(
    object : DiffUtil.ItemCallback<MeetingInviteSelectedParticipantItem>() {
        override fun areItemsTheSame(
            oldItem: MeetingInviteSelectedParticipantItem,
            newItem: MeetingInviteSelectedParticipantItem
        ): Boolean = oldItem.contact.id == newItem.contact.id

        override fun areContentsTheSame(
            oldItem: MeetingInviteSelectedParticipantItem,
            newItem: MeetingInviteSelectedParticipantItem
        ): Boolean = oldItem.contact == newItem.contact
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ParticipantViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_invite_selected_participant, parent, false)
        return ParticipantViewHolder(view, onParticipantClick, lifecycleScope)
    }

    override fun onBindViewHolder(holder: ParticipantViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ParticipantViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    class ParticipantViewHolder(
        itemView: View,
        private val onParticipantClick: (String) -> Unit,
        lifecycleScope: CoroutineScope
    ) : RecyclerView.ViewHolder(itemView) {

        private val textViewName: TextView = itemView.findViewById(R.id.textview_name)
        private val buttonRemove: View = itemView.findViewById(R.id.button_remove)
        private val avatar = InviteRowAvatarBinder.create(
            container = itemView.findViewById(R.id.avatar_container),
            scope = lifecycleScope,
            logTag = "MeetingInviteSelected",
        )

        fun bind(item: MeetingInviteSelectedParticipantItem) {
            textViewName.text = item.contact.getDisplayNameForUI()

            avatar.bind(item.contact.id, AvatarData.FromContactor(item.contact))

            itemView.setOnClickListener {
                onParticipantClick(item.contact.id)
            }
            buttonRemove.setOnClickListener {
                onParticipantClick(item.contact.id)
            }
        }

        fun recycle() = avatar.cancel()
    }
}
