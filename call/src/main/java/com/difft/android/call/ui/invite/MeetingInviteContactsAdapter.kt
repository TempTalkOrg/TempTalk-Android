package com.difft.android.call.ui.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_meeting_invite_contact, parent, false)
        return ContactViewHolder(view, onContactClick, lifecycleScope)
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        holder.bind(getItem(position))
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

        private val entryPoint by lazy {
            EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
                ApplicationHelper.instance
            )
        }
        private val contactorCacheManager: ContactorCacheManager by lazy {
            entryPoint.contactorCacheManager
        }
        private val callToChatController: LCallToChatController by lazy {
            entryPoint.callToChatController
        }

        private var loadAvatarJob: Job? = null

        fun bind(item: MeetingInviteContactItem) {
            val displayName = item.contact.getDisplayNameForUI()
            textViewName.text = displayName

            if (item.isInMeeting) {
                checkbox.visibility = View.GONE
                checkboxSelected.visibility = View.VISIBLE
                checkboxSelected.setBackgroundResource(R.drawable.bg_checkbox_selected_disabled)
                checkboxSelectedIcon.setColorFilter(
                    itemView.context.getColor(com.difft.android.base.R.color.t_disable)
                )
            } else {
                if (item.isSelected) {
                    checkbox.visibility = View.GONE
                    checkboxSelected.visibility = View.VISIBLE
                    checkboxSelected.setBackgroundResource(R.drawable.bg_checkbox_selected)
                    checkboxSelectedIcon.setColorFilter(itemView.context.getColor(android.R.color.white))
                } else {
                    checkbox.visibility = View.VISIBLE
                    checkboxSelected.visibility = View.GONE
                }
            }

            loadAvatarJob?.cancel()
            loadAvatar(item.contact)

            itemView.setOnClickListener {
                if (!item.isInMeeting) {
                    onContactClick(item.contact.id)
                }
            }
        }

        private fun loadAvatar(contact: ContactorModel) {
            avatarContainer.removeAllViews()
            textViewAvatarLetter.visibility = View.GONE

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

            loadAvatarJob = lifecycleScope.launch {
                try {
                    val displayInfo = contactorCacheManager.getParticipantDisplayInfo(contact.id)

                    withContext(Dispatchers.Main) {
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            val avatarView = when (val data = displayInfo.avatarData) {
                                is AvatarData.FromContactor ->
                                    callToChatController.getAvatarByContactor(itemView.context, data.contactor)
                                is AvatarData.FromNameOrUid ->
                                    callToChatController.createAvatarByNameOrUid(itemView.context, data.name, data.userId)
                                null -> null
                            }
                            if (avatarView != null) {
                                avatarView.layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                avatarContainer.addView(avatarView)
                            } else {
                                showDefaultAvatar(firstLetter)
                            }
                        }
                    }
                } catch (e: Exception) {
                    L.e { "[MeetingInviteContacts] loadAvatar failed: ${e.stackTraceToString()}" }
                    if (adapterPosition != RecyclerView.NO_POSITION) {
                        showDefaultAvatar(firstLetter)
                    }
                }
            }
        }

        private fun showDefaultAvatar(letter: String) {
            textViewAvatarLetter.text = letter
            textViewAvatarLetter.visibility = View.VISIBLE
            textViewAvatarLetter.setTextColor(
                itemView.context.getColor(android.R.color.white)
            )
            textViewAvatarLetter.background = AppCompatResources.getDrawable(
                itemView.context,
                com.difft.android.call.R.drawable.bg_circle_avatar
            ) ?: android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(itemView.context.getColor(com.difft.android.base.R.color.primary))
            }
        }
    }
}

