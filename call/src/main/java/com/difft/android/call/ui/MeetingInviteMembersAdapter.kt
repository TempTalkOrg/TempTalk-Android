package com.difft.android.call.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.R
import com.difft.android.call.data.InviteMember
import com.difft.android.call.manager.ContactorCacheManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    class MemberViewHolder(
        itemView: View,
        private val onRemoveClick: (InviteMember) -> Unit,
        private val lifecycleScope: CoroutineScope
    ) : RecyclerView.ViewHolder(itemView) {

        private val avatarContainer: ViewGroup = itemView.findViewById(R.id.avatar_container)
        private val textViewAvatarLetter: TextView = itemView.findViewById(R.id.textview_avatar_letter)
        private val textViewName: TextView = itemView.findViewById(R.id.textview_name)
        private val buttonRemove: View = itemView.findViewById(R.id.button_remove)

        private val contactorCacheManager: ContactorCacheManager by lazy {
            EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
                ApplicationHelper.instance
            ).contactorCacheManager
        }

        private var loadAvatarJob: Job? = null

        fun bind(member: InviteMember) {
            textViewName.text = member.name

            // 取消之前的加载任务
            loadAvatarJob?.cancel()
            loadAvatarJob = null

            // 加载头像
            loadAvatar(member)

            buttonRemove.setOnClickListener {
                onRemoveClick(member)
            }
        }

        private fun loadAvatar(member: InviteMember) {
            // 清除之前的头像 View
            avatarContainer.removeAllViews()
            textViewAvatarLetter.visibility = View.GONE

            // 取消之前的加载任务
            loadAvatarJob?.cancel()
            
            // 使用 Fragment 的生命周期 scope 加载头像
            loadAvatarJob = lifecycleScope.launch {
                try {
                    val displayInfo = withContext(Dispatchers.IO) {
                        contactorCacheManager.getParticipantDisplayInfo(
                            itemView.context,
                            member.uid
                        )
                    }

                    withContext(Dispatchers.Main) {
                        // 检查 ViewHolder 是否仍然有效
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            if (displayInfo.avatar != null) {
                                // 如果有头像 View，添加到容器中
                                val avatarView = displayInfo.avatar
                                avatarView.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                avatarContainer.addView(avatarView)
                            } else {
                                // 显示首字母作为默认头像
                                showDefaultAvatar(member.sortLetter)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 检查 ViewHolder 是否仍然有效
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        // 出错时显示默认头像
                        showDefaultAvatar(member.sortLetter)
                    }
                }
            }
        }

        private fun showDefaultAvatar(letter: String) {
            textViewAvatarLetter.text = letter
            textViewAvatarLetter.visibility = View.VISIBLE
            // 设置文字颜色为白色
            textViewAvatarLetter.setTextColor(
                itemView.context.getColor(android.R.color.white)
            )
            // 设置圆形背景
            textViewAvatarLetter.background = itemView.context.getDrawable(R.drawable.bg_circle_avatar)
                ?: android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(itemView.context.getColor(com.difft.android.base.R.color.primary))
                }
        }
    }
}

