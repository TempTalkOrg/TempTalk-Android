package com.difft.android.base.user

enum class NotificationContentDisplayType(val value: Int) {
    NAME_AND_CONTENT(0),

    NAME_ONLY(1),

    NO_NAME_OR_CONTENT(2);
}

enum class GlobalNotificationType(val value: Int) {
    ALL(0),

    MENTION(1),

    OFF(2);
}
