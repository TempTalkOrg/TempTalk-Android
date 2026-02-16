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
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel

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

    private val selectedIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_invite_contact, parent, false)
        return ContactViewHolder(view, onContactClick, lifecycleScope)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, selectedIds.contains(item.contact.id))
    }

    fun updateSelectedIds(newSelectedIds: Set<String>) {
        val oldSelected = selectedIds.toSet()
        selectedIds.clear()
        selectedIds.addAll(newSelectedIds)

        // 通知变化的项
        val changedPositions = mutableListOf<Int>()
        for (i in 0 until itemCount) {
            val item = getItem(i)
            val wasSelected = oldSelected.contains(item.contact.id)
            val isSelected = newSelectedIds.contains(item.contact.id)
            if (wasSelected != isSelected) {
                changedPositions.add(i)
            }
        }

        changedPositions.forEach { position ->
            notifyItemChanged(position)
        }
    }

    class ContactViewHolder(
        itemView: View,
        private val onContactClick: (String) -> Unit,
        private val lifecycleScope: CoroutineScope
    ) : RecyclerView.ViewHolder(itemView) {

        private val checkbox: View = itemView.findViewById(R.id.checkbox)
        private val checkboxSelected: ViewGroup = itemView.findViewById(R.id.checkbox_selected)
        private val checkboxSelectedIcon: android.widget.ImageView = itemView.findViewById(R.id.checkbox_selected_icon)
        private val avatarContainer: ViewGroup = itemView.findViewById(R.id.avatar_container)
        private val textViewAvatarLetter: TextView = itemView.findViewById(R.id.textview_avatar_letter)
        private val textViewName: TextView = itemView.findViewById(R.id.textview_name)

        private val contactorCacheManager: ContactorCacheManager by lazy {
            EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
                ApplicationHelper.instance
            ).contactorCacheManager
        }

        private var loadAvatarJob: Job? = null

        fun bind(item: MeetingInviteContactItem, isSelected: Boolean) {
            val displayName = item.contact.getDisplayNameForUI()
            textViewName.text = displayName

            // 更新选择状态
            if (item.isInMeeting) {
                checkbox.visibility = View.GONE
                checkboxSelected.visibility = View.VISIBLE
                checkboxSelected.setBackgroundResource(R.drawable.bg_checkbox_selected_disabled)
                checkboxSelectedIcon.setColorFilter(
                    itemView.context.getColor(com.difft.android.base.R.color.t_disable)
                )
            } else {
                if (isSelected) {
                    checkbox.visibility = View.GONE
                    checkboxSelected.visibility = View.VISIBLE
                    checkboxSelected.setBackgroundResource(R.drawable.bg_checkbox_selected)
                    checkboxSelectedIcon.setColorFilter(itemView.context.getColor(android.R.color.white))
                } else {
                    checkbox.visibility = View.VISIBLE
                    checkboxSelected.visibility = View.GONE
                }
            }

            // 取消之前的加载任务
            loadAvatarJob?.cancel()
            loadAvatarJob = null

            // 加载头像
            loadAvatar(item.contact)

            itemView.setOnClickListener {
                if (!item.isInMeeting) {
                    onContactClick(item.contact.id)
                }
            }
        }

        private fun loadAvatar(contact: ContactorModel) {
            // 清除之前的头像 View
            avatarContainer.removeAllViews()
            textViewAvatarLetter.visibility = View.GONE

            // 获取首字母
            val displayName = contact.getDisplayNameForUI()
            val firstLetter = if (displayName.isNotBlank()) {
                val firstChar = displayName.firstOrNull()?.uppercaseChar() ?: '#'
                if (firstChar.isLetter() || firstChar in '\u4e00'..'\u9fa5') {
                    firstChar.toString()
                } else {
                    "#"
                }
            } else {
                "#"
            }

            // 取消之前的加载任务
            loadAvatarJob?.cancel()
            
            // 使用 Fragment 的生命周期 scope 加载头像
            loadAvatarJob = lifecycleScope.launch {
                try {
                    val displayInfo = withContext(Dispatchers.IO) {
                        contactorCacheManager.getParticipantDisplayInfo(
                            itemView.context,
                            contact.id
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
                                showDefaultAvatar(firstLetter)
                            }
                        }
                    }
                } catch (e: Exception) {
                    // 检查 ViewHolder 是否仍然有效
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        // 出错时显示默认头像
                        showDefaultAvatar(firstLetter)
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
            textViewAvatarLetter.background = itemView.context.getDrawable(
                com.difft.android.call.R.drawable.bg_circle_avatar
            ) ?: android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(itemView.context.getColor(com.difft.android.base.R.color.primary))
            }
        }
    }
}

