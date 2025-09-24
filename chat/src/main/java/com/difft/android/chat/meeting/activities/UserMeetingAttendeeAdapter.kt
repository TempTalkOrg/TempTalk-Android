package com.difft.android.meeting.activities

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarView
import com.difft.android.chat.contacts.contactsdetail.ContactDetailActivity
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.databinding.AttendeeListHeaderBinding
import com.difft.android.chat.databinding.AttendeeListItemBinding
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.network.responses.AttendanceStatus
import com.difft.android.network.responses.Attendee
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.schedulers.Schedulers

sealed class AttendeeListItem {

    abstract val isInGroup: Boolean

    data class Header(val title: String, val groupName: String?) :
        AttendeeListItem() {
        override val isInGroup: Boolean
            get() = false
    }

    data class AttendeeItem(val attendee: GroupMemberModel) :
        AttendeeListItem() {
        override val isInGroup: Boolean
            get() = attendee.isGroupUser == true
    }
}

interface AttendeeClickListener {
    fun onRemoveAttendanceClicked(attendee: GroupMemberModel)
}

class UserMeetingAttendeeAdapter(
    private val clickListener: AttendeeClickListener,
    private val isInEditMode: Boolean
) : ListAdapter<AttendeeListItem, RecyclerView.ViewHolder>(AttendeeDiffCallback()) {

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (currentList[position]) {
            is AttendeeListItem.Header -> TYPE_HEADER
            is AttendeeListItem.AttendeeItem -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup, viewType: Int
    ): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = AttendeeListHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                HeaderViewHolder(binding)
            }

            else -> {
                val binding = AttendeeListItemBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                AttendeeViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder, position: Int
    ) {
        when (val item = getItem(position)) {
            is AttendeeListItem.Header -> (holder as HeaderViewHolder).bind(
                item.title, item.groupName
            )

            is AttendeeListItem.AttendeeItem -> (holder as AttendeeViewHolder).bind(
                item.attendee
            )
        }
    }

    inner class HeaderViewHolder(private val binding: AttendeeListHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(title: String, groupName: String?) {
            binding.headerTextView.text = title
            if (!groupName.isNullOrEmpty()) {
                binding.headerGroupNameTextView.text = " - $groupName"
            } else {
                binding.headerGroupNameTextView.text = ""
            }
        }
    }

    inner class AttendeeViewHolder(
        private val binding: AttendeeListItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(attendee: GroupMemberModel) {

            binding.nameTextView.text = attendee.name
            setupEmailOrUid(
                binding.emailTextView,
                attendee.roleInString,
                attendee.email,
                attendee.uid
            )
            binding.btnToRemoveAttendance.isVisible =
                (attendee.isRemovable == true && isInEditMode)
            binding.btnToRemoveAttendance.setOnClickListener {
                clickListener.onRemoveAttendanceClicked(attendee)
            }
            setupAvatar(
                binding.root.context,
                attendee,
                binding.avatarImageView,
                binding.nameTextView,
                binding.emailTextView,
                binding.labelExt
            )

            if (!isInEditMode) {
                when (attendee.going) {
                    AttendanceStatus.YES.value -> {
                        binding.imgGoingYesOrNo.setImageResource(R.drawable.ic_going_yes_circle)
                        binding.imgGoingYesOrNo.isVisible = true
                    }

                    AttendanceStatus.NO.value -> {
                        binding.imgGoingYesOrNo.setImageResource(R.drawable.ic_going_no_circle)
                        binding.imgGoingYesOrNo.isVisible = true
                    }

                    else -> binding.imgGoingYesOrNo.isVisible = false
                }
            }

            binding.labelExt.isVisible = attendee.uid?.startsWith("+") == false

        }
    }

    private fun setupEmailOrUid(
        emailTextView: TextView,
        roleInString: String?,
        email: String?,
        uid: String?
    ) {
        val hostPrefix = if (roleInString == Attendee.HOST) {
            "Host・"
        } else {
            ""
        }
        if (email.isNullOrEmpty()) {
            emailTextView.text = "$hostPrefix$uid"
        } else {
            emailTextView.text = "$hostPrefix$email"
        }
    }

    private fun setupAvatar(
        context: Context,
        attendee: GroupMemberModel,
        avatarView: AvatarView,
        nameTextView: TextView,
        emailTextView: TextView,
        labelExt: TextView
    ) {

        if (!attendee.uid.isNullOrEmpty()) {
            avatarView.setOnClickListener {
                ContactDetailActivity.startActivity(it.context, attendee.uid)
            }
        }

        val composable = ContactorUtil.getContactWithID(
            context, attendee.uid!!
        ).observeOn(AndroidSchedulers.mainThread()).subscribeOn(Schedulers.io())
            .subscribe({
                if (!it.isPresent) {
                    val displayName = getDisplayName(
                        attendee.name, attendee.email, attendee.uid
                    )
                    val firstLetter = getFirstLetter(displayName)
                    nameTextView.text = displayName
                    avatarView.setAvatar(
                        null, null, firstLetter, attendee.uid ?: ""
                    )
                    labelExt.isVisible = true
                    return@subscribe
                }

                val contactor = it.get()
                val displayName = getDisplayName(
                    contactor.getDisplayNameForUI(), contactor.email, contactor.id
                )
                nameTextView.text = displayName
                setupEmailOrUid(
                    emailTextView,
                    attendee.roleInString,
                    contactor.email,
                    attendee.uid
                )
                avatarView.setAvatar(contactor)
            }, { error ->
                L.i { "getContactWithID Error: $error, attendee=$attendee" }
                val displayName = getDisplayName(
                    attendee.name, attendee.email, attendee.uid
                )
                nameTextView.text = displayName
                setupEmailOrUid(
                    emailTextView,
                    attendee.roleInString,
                    attendee.email,
                    attendee.uid
                )
                avatarView.setAvatar(
                    null, null, getFirstLetter(displayName), attendee.uid ?: ""
                )
                labelExt.isVisible = true

                avatarView.setOnClickListener {
                    L.i { "Can not show user info for this $attendee" }
                }
            })
    }

    private fun getDisplayName(
        name: String?, email: String?, uid: String?
    ): String {
        var displayName = name
        if (displayName.isNullOrEmpty()) {
            displayName = email?.substringBefore("@")
            if (displayName.isNullOrEmpty()) {
                displayName = uid
            }
        }
        return displayName ?: ""
    }

    private fun getFirstLetter(name: String?): String {
        return name?.firstOrNull()?.toString()?.uppercase()?.replace("+", "#")
            ?: "#"
    }
}

class AttendeeDiffCallback : DiffUtil.ItemCallback<AttendeeListItem>() {
    override fun areItemsTheSame(
        oldItem: AttendeeListItem, newItem: AttendeeListItem
    ): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(
        oldItem: AttendeeListItem, newItem: AttendeeListItem
    ): Boolean {
        return oldItem == newItem
    }
}