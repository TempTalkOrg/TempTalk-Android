package com.difft.android.network.requests

import com.difft.android.network.responses.Attendee

data class RecurringMeetingReq(
    val eventId: String,
    val recurring: Boolean,
    val allEvent: Boolean,
)

data class MeetingEventDataUpdateReq(
    val isAllEvent: Boolean,
    val isRecurring: Boolean,
    val event: MeetingEventDataReq,
)

data class MeetingEventDataReq(
    val topic: String,
    val description: String,
    val start: Long,
    val end: Long,
    val timezone: String,
    val isAllEvent: Boolean,
    val isAllDay: Boolean = false,
    val isRecurring: Boolean,
    val recurringRule: RecurringRule?,
    val host: String,
    val attendees: List<Attendee>,
    val isGroup: Boolean,
    val group: MeetingGroup?,
    val attachment: List<FileAttachment>?,
    val everyoneCanInviteOthers: Boolean,
    val everyoneCanModify: Boolean,
    // 20231019@w ------------------------------------------------------------->
    // Purpose: Add channelName and eid to MeetingEventDataReq for event update
    // link to issue: https://trello.com/c/Hj9Chhpx
    val eid: String? = null,
    val channelName: String? = null,
    // 20231019@w -------------------------------------------------------------<
)

data class RecurringRule(
    val rrule: String
)

data class AttendeeToAdd(
    val uid: String,
    val name: String,
    val email: String? = null,
    val role: String
)

data class MeetingGroup(
    val gid: String,
    val name: String
)

data class FileAttachment(
    val name: String,
    val link: String,
)

data class MeetingEventGoingReq(
    val going: String,
    val isRecurring: Boolean,
    val isAllEvent: Boolean,
)

data class MeetingEventNotificationReq(
    val receiveNotification: Boolean,
    val isRecurring: Boolean,
    val isAllEvent: Boolean,
)

enum class SchedulerEntry(val value: String) {
    GROUP("group"),
    ONE_ON_ONE("1on1"),
    MEETING_BOT("meetingbot"),
    LIST("list"),
    NONE("");
}


enum class GroupAction(val actionCode: Int) {
    JOIN_GROUP(1),
    LEAVE_GROUP(2),
    INVITE_TO_GROUP(3),
    KICK_MEMBER(4),
    DISBAND_GROUP(5),
    LINK_JOIN_GROUP(29)
}

data class ChangeGroupReq(
    val gid: String,
    val actionCode: Int,
    val target: List<String>
)

/*
{
    "start": 1697997822, //查询是否空闲的开始时间，单位：秒
    "end": 1697997822, //查询是否空闲的结束时间，单位：秒
    "users": ["+1234567", "+1234567"]
}
 */
data class GetAvailabilityReq(
    val start: Long = 0,
    val end: Long = 0,
    val users: List<String?> = emptyList()
)

data class LiveStreamEventDataAddedReq(
    val eid: String
)