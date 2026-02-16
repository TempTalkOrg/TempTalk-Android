package com.difft.android.network.responses
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


enum class AttendanceStatus(val value: String) {
    YES("yes"),
    NO("no"),
    MAYBE("maybe");

    override fun toString(): String {
        return value
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