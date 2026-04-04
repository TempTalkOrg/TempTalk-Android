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

    class ParticipantViewHolder(
        itemView: View,
        private val onParticipantClick: (String) -> Unit,
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

        fun bind(item: MeetingInviteSelectedParticipantItem) {
            val displayName = item.contact.getDisplayNameForUI()
            textViewName.text = displayName

            loadAvatarJob?.cancel()
            loadAvatarJob = null
            loadAvatar(item.contact, displayName)

            itemView.setOnClickListener {
                onParticipantClick(item.contact.id)
            }
            buttonRemove.setOnClickListener {
                onParticipantClick(item.contact.id)
            }
        }

        private fun loadAvatar(contact: ContactorModel, displayName: String) {
            avatarContainer.removeAllViews()
            textViewAvatarLetter.visibility = View.GONE

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

            loadAvatarJob?.cancel()
            loadAvatarJob = lifecycleScope.launch {
                try {
                    val displayInfo = withContext(Dispatchers.IO) {
                        contactorCacheManager.getParticipantDisplayInfo(
                            itemView.context,
                            contact.id
                        )
                    }

                    withContext(Dispatchers.Main) {
                        if (adapterPosition != RecyclerView.NO_POSITION) {
                            if (displayInfo.avatar != null) {
                                val avatarView = displayInfo.avatar
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
            textViewAvatarLetter.background = itemView.context.getDrawable(
                com.difft.android.call.R.drawable.bg_circle_avatar
            ) ?: android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(itemView.context.getColor(com.difft.android.base.R.color.primary))
            }
        }
    }
}
