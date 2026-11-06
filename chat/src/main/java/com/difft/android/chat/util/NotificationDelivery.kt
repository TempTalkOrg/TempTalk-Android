package com.difft.android.chat.util

/** How a message reached this device, which decides who judged whether it may notify. */
enum class NotificationDelivery {
    /** Chat socket (or the pending-message pull): the server does not judge, the local policy does. */
    WEB_SOCKET,

    /** FCM: the server already applied mute / focus / level before sending, so the client trusts it. */
    FCM,
}
