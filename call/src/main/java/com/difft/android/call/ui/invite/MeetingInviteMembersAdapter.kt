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
import com.difft.android.call.data.InviteMember
import kotlinx.coroutines.CoroutineScope

/**
 * 已选成员列表适配器
 */
class MeetingInviteMembersAdapter(
    private val onRemoveClick: (InviteMember) -> Unit,
    private val lifecycleScope: CoroutineScope
) : ListAdapter<InviteMember, MeetingInviteMembersAdapter.MemberViewHolder>(
    object : DiffUtil.ItemCallback<InviteMember>() {
        override fun areItemsTheSame(oldItem: InviteMember, newItem: InviteMember): Boolean =
            oldItem.uid == newItem.uid

        override fun areContentsTheSame(oldItem: InviteMember, newItem: InviteMember): Boolean =
            oldItem == newItem
    }
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_invite_member, parent, false)
        return MemberViewHolder(view, onRemoveClick, lifecycleScope)
    }

    override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: MemberViewHolder) {
        super.onViewRecycled(holder)
        holder.recycle()
    }

    class MemberViewHolder(
        itemView: View,
        private val onRemoveClick: (InviteMember) -> Unit,
        lifecycleScope: CoroutineScope
    ) : RecyclerView.ViewHolder(itemView) {

        private val textViewName: TextView = itemView.findViewById(R.id.textview_name)
        private val buttonRemove: View = itemView.findViewById(R.id.button_remove)
        private val avatar = InviteRowAvatarBinder.create(
            container = itemView.findViewById(R.id.avatar_container),
            scope = lifecycleScope,
            logTag = "MeetingInviteMembers",
        )

        fun bind(member: InviteMember) {
            textViewName.text = member.name
            avatar.bind(member.uid, AvatarData.FromNameOrUid(member.name, member.uid))
            buttonRemove.setOnClickListener {
                onRemoveClick(member)
            }
        }

        fun recycle() = avatar.cancel()
    }
}
