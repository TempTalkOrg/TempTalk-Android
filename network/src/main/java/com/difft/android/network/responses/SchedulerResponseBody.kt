package com.difft.android.network.responses

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UserMeetingListResp(
    val limit: Int,
    val page: Int,
    val sort: String,
    val total_rows: Int,
    val total_pages: Int,
    val rows: List<MeetingRow>? = listOf(),
    val version: Int = -1
)

data class MultiUserMeetingListResp(
    val version: Int = -1,
    val myCalendar: List<Calendar>,
    val otherCalendar: List<Calendar>
)

data class Calendar(
    val cid: String,
    val name: String,
    val timeZone: String,
    val events: List<MeetingRow>? = listOf()
)

data class HostInfo(
    val uid: String?,
    val name: String?,
)
data class MeetingRow(
    val cid: String,
    val eid: String,
    val topic: String,
    val host: String,
    val hostInfo: HostInfo? = null,
    val start: String,// timestamp in seconds
    val end: String, // timestamp in seconds
    val type: CalendarType,
    val channelName: String,
    val googleLink: String? = null, // google meet link
    var hostAvatarUrl: String? = null,
    var hostAvatarEncKey: String? = null,
    var hostName: String? = null,
    var notificationType: String? = null,
    val going: String? = null, // Consider using an enum for yes, no, maybe if applicable
    val receiveNotification: Boolean? = null,
    var index: Int = 0,
    var muted: Boolean? = null,
    val isLiveStream: Boolean? = false,
) {
    val formattedTime: String
        get() {
            if (start.isBlank() || end.isBlank()) {
                return "Invalid Date"
            }

            val startTimeMillis = start.toLong() * 1000
            val endTimeMillis = end.toLong() * 1000

            val duration = getDuration(startTimeMillis, endTimeMillis)

            return "${convertTimestampToTime(startTimeMillis)} ($duration)"
        }

    private fun getDuration(
        startTimeMillis: Long,
        endTimeMillis: Long
    ): String {
        val durationMillis = endTimeMillis - startTimeMillis
        val minutes = (durationMillis / (1000 * 60)) % 60
        val hours = (durationMillis / (1000 * 60 * 60)) % 24
        val days = (durationMillis / (1000 * 60 * 60 * 24))

        return buildString {
            if (days > 0) {
                append("$days day${if (days > 1) "s" else ""} ")
            }
            if (hours > 0) {
                append("$hours hr${if (hours != 1L) "s" else ""} ")
            }
            if (minutes > 0) {
                append("$minutes min${if (minutes != 1L) "s" else ""}")
            }
        }.trim()
    }


    private fun convertTimestampToTime(timestamp: Long): String {
        val date = Date(timestamp)
        val format = SimpleDateFormat("h:mm a", Locale.ENGLISH)
        var formattedTime = format.format(date)

        if (formattedTime.startsWith("12:") && formattedTime.endsWith("AM")) {
            formattedTime = formattedTime.replace("AM", "MIDNIGHT")
        } else if (formattedTime.startsWith("12:") && formattedTime.endsWith("PM")) {
            formattedTime = formattedTime.replace("PM", "NOON")
        }

        return formattedTime
    }
}

private fun convertTimestampToDateTime(timestamp: Long): String {
    val date = Date(timestamp)
    //val format = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(date)
}

enum class CalendarType {
    difft, gg
}

enum class CalendarAction {
    share, copy
}

data class MeetingEventCreateResp(val eid: String)
data class MeetingEventUpdateResp(val eid: String)

data class Permissions(
    val editMode: ModePermissions,
    val viewMode: ModePermissions
)

enum class PermissionType {
    BUTTON_UPDATE,
    BUTTON_EDIT,
    BUTTON_DELETE,
    BUTTON_COPY,
    BUTTON_COPY_LIVE_STREAM_INFO,
    TEXT_FIELD_TITLE,
    PICKER_START_DATE_TIME,
    SELECTOR_DURATION,
    SELECTOR_REPEAT,
    SELECTOR_ATTENDEE,
    TEXT_HOST,
    EDITOR_ATTACHMENT,
    TEXT_FIELD_DESC,
    CHECKBOX_EVERYONE_CAN_MODIFY_MEETING,
    CHECKBOX_EVERYONE_CAN_INVITE_OTHERS,
    CHECKBOX_SEND_INVITATION_TO_THE_CHAT_ROOM,
    TOGGLE_GOING_OR_NOT,
    CHECKBOX_RECEIVE_NOTIFICATION,
    BUTTON_ADD_LIVE_STREAM,
    BUTTON_JOIN,
    BUTTON_FIND_A_TIME,
}

data class ModePermissions(
    val buttonUpdate: String,
    val buttonEdit: String,
    val buttonDelete: String,
    val buttonCopy: String,
    val buttonCopyLiveStream: String,
    val textFieldTitle: String,
    val pickerStartDateTime: String,
    val selectorDuration: String,
    val selectorRepeat: String,
    val selectorAttendee: String,
    val textHost: String,
    val editorAttachment: String,
    val textFieldDesc: String,
    val checkboxEveryoneCanModifyMeeting: String,
    val checkboxEveryoneCanInviteOthers: String,
    val checkboxSendInvitationToTheChatRoom: String,
    val toggleGoingOrNot: String,
    val checkboxReceiveNotification: String,
    val buttonAddLiveStream: String,
    val buttonJoin: String,
    val buttonFindATime: String
)

enum class PermissionStatus(val value: String) {
    READ_WRITE("readwrite"),
    READ("read"),
    WRITE("write"),
    NONE("-");

    companion object {
        fun from(value: String): PermissionStatus {
            return values().firstOrNull { it.value == value } ?: NONE
        }
    }
}

// Define data classes for the Event Data response:
data class MeetingEventDataResp(
    val canModify: Boolean,
    val canInvite: Boolean,
    val going: String,
    val receiveNotification: Boolean,
    val cid: String,
    val event: EventDetails,
    val permissions: Permissions  // Adding permissions to the response
)

enum class AttendanceStatus(val value: String) {
    YES("yes"),
    NO("no"),
    MAYBE("maybe");

    override fun toString(): String {
        return value
    }
}

data class EventDetails(
    val eid: String,
    val type: String,
    val topic: String,
    val start: String,
    val end: String,
    val description: String,
    val host: String,
    val channelName: String,
    val attendees: List<Attendee>,
    val isGroup: Boolean,
    val group: GroupResponse?, // This should be nullable since it might not always be present
    val isRecurring: Boolean,
    val recurringRule: RecurringRuleResponse?, // This should be nullable since it might not always be present
    val attachment: List<FileAttachmentResponse>?, // This should be nullable since it might not always be present
    val everyoneCanInviteOthers: Boolean,
    val everyoneCanModify: Boolean,
    val isLiveStream: Boolean? = false,
) {
    val formattedTime: String
        get() {
            return "${convertTimestampToDateTime(start.toLong() * 1000)} - ${
                convertTimestampToDateTime(end.toLong() * 1000)
            }"
        }
}

data class Attendee(
    val uid: String,
    val name: String,
    val email: String? = null,
    var role: String,
    val going: String? = null,
    val isGroupUser: Boolean? = true,
    var isRemovable: Boolean? = true
) {
    init {
        role = when (role) {
            OWNER -> HOST
            MEMBER -> ATTENDEE
            ADMIN -> ATTENDEE
            else -> role
        }
    }

    companion object {
        const val HOST = "host"
        const val ATTENDEE = "attendee"
        const val OWNER = "owner"
        const val MEMBER = "member"
        const val ADMIN = "admin"
    }
}


data class GroupResponse(val gid: String, val name: String)
data class FileAttachmentResponse(val name: String, val link: String)
data class RecurringRuleResponse(
    val rrule: String,
    val repeat: String?,
    val repeatOptions: List<RepeatOption>? = listOf(),
)

data class RepeatOption(
    val label: String,
    val value: String
)

data class MeetingEventCopyContentWithURLResp(
    val content: String
)

data class CheckUserAvailabilityResp(
    val freebusy: String
) {

    companion object {
        const val FREE = "free"
        const val BUSY = "busy"
    }
}

data class GetAvailabilityResp(
    val start: Long,
    val end: Long,
    val userEvents: List<ReservedEventsById>
)

data class ReservedEventsById(
    val uid: String,
    val timeZone: String? = null,
    val events: List<ReservedEvent>,
)

data class ReservedEvent(
    val eid: String,
    val topic: String,
    val start: Long, // timestamp in seconds
    val end: Long, // timestamp in seconds
)

data class CheckUserInfo (
    val uid: String,
    val name: String,
    val email: String,
    val validUser: Boolean,
)

data class LiveStreamEventDataAddedResp(
    val eid: String
)