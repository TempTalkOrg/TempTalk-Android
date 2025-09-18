package com.difft.android.chat.recent

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.network.group.Member
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

@HiltViewModel
class InviteParticipantsViewModel @Inject constructor(
) : ViewModel() {

    private var originalGroupMembers: Map<String, Member> = mapOf()

    val attendees: MutableLiveData<List<GroupMemberModel>> =
        MutableLiveData(listOf())

    fun removeAttendee(attendee: GroupMemberModel) {
        val newAttendees = attendees.value?.toMutableList()
        newAttendees?.remove(attendee)
        setAttendees(newAttendees ?: listOf())
    }

    fun setAttendees(newAttendees: List<GroupMemberModel>) {
        newAttendees.forEach { attendee ->
            attendee.isGroupUser =
                originalGroupMembers.containsKey(attendee.uid)
            if (attendee.name?.isEmpty() == true) {
                attendee.name =
                    originalGroupMembers[attendee.uid]?.displayName ?: ""
            }
            attendee.isRemovable = attendee.isRemovable
        }
        attendees.postValue(newAttendees)
    }

    fun getAttendees(): List<GroupMemberModel>? {
        return attendees.value
    }

    private var meetingName = ""
    fun setMeetingName(newTitle: String) {
        meetingName = newTitle
    }

    fun getMeetingName(): String {
        return meetingName
    }

    private var myDisplayName: String = ""

    fun getMyDisplayName(context: Context, myUid: String): String {

        if (myDisplayName.isEmpty()) {
            val contact =
                ContactorUtil.getContactWithID(context, myUid).blockingGet()
            if (contact.isPresent) {
                myDisplayName = contact.get().getDisplayNameForUI()
            }
        }

        return myDisplayName
    }

    private val compositeDisposable: CompositeDisposable by lazy { CompositeDisposable() }
    override fun onCleared() {
        super.onCleared()
        compositeDisposable.dispose()
    }
}