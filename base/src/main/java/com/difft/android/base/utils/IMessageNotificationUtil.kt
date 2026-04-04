package com.difft.android.base.utils

interface IMessageNotificationUtil {
    fun cancelNotificationsByConversation(conversationId: String?)
    fun cancelAllNotifications()
    fun getNotificationChannelName(): String
}