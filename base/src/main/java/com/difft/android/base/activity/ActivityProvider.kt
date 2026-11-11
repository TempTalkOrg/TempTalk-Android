package com.difft.android.base.activity


interface ActivityProvider {
    




    fun getActivityClass(activityType: ActivityType): Class<*>
}

enum class ActivityType {
    MAIN,
    SEARCH,
    CONTACT_DETAIL,
    L_INCOMING_CALL,
    INDEX,
    CRITICAL_ALERT,
    BACKGROUND_CONNECTION_SETTINGS,
    PROXY_SETTINGS,
    NOTIFICATION_SETTINGS
}
