package com.difft.android.base.notification

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationChannelIdsTest {

    @Test
    fun `every app-owned channel and the group has a display name`() {
        listOf(
            NotificationChannelIds.MESSAGE, NotificationChannelIds.MESSAGE_SUMMARY, NotificationChannelIds.CALL,
            NotificationChannelIds.ONGOING_CALL, NotificationChannelIds.BACKGROUND, NotificationChannelIds.CRITICAL_ALERT,
            NotificationChannelIds.ACCOUNT, NotificationChannelIds.MESSAGE_GROUP,
        ).forEach { assertNotNull(NotificationChannelIds.nameRes(it), "id=$it") }
        assertNull(NotificationChannelIds.nameRes("MESSAGE+1234"))
    }
}
