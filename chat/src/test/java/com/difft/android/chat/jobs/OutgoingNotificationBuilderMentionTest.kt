package com.difft.android.chat.jobs

import com.difft.android.websocket.internal.push.NotificationType
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.TextMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The mention list travels with every group mention type, so the server can find a direct mention. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutgoingNotificationBuilderMentionTest {

    private fun groupText(vararg mentionUids: String) = TextMessage(
        id = "m1",
        fromWho = For.Account("+sender"),
        forWhat = For.Group("gid-1"),
        systemShowTimestamp = 1L,
        timeStamp = 1L,
        receivedTimeStamp = 1L,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0L,
        sequenceId = 0L,
        mode = 0,
        text = "t",
        mentions = mentionUids.map { Mention(start = 0, length = 1, uid = it, type = 0) }.ifEmpty { null },
    )

    @Test
    fun `a leading @all keeps the @all type and carries the whole mention list`() {
        val notification = buildTextMessageNotification(groupText(MENTIONS_ALL_ID, "+zhang"), Gson())

        assertEquals(NotificationType.GROUP_MENTIONS_ALL.code, notification.type)
        assertContentEquals(arrayOf(MENTIONS_ALL_ID, "+zhang"), notification.args.mentionedPersons)
    }

    @Test
    fun `a direct mention first keeps the destination type and the mention list`() {
        val notification = buildTextMessageNotification(groupText("+zhang", MENTIONS_ALL_ID), Gson())

        assertEquals(NotificationType.GROUP_MENTIONS_DESTINATION.code, notification.type)
        assertContentEquals(arrayOf("+zhang", MENTIONS_ALL_ID), notification.args.mentionedPersons)
    }

    @Test
    fun `a message without mentions carries no list`() {
        val notification = buildTextMessageNotification(groupText(), Gson())

        assertEquals(NotificationType.GROUP_NORMAL.code, notification.type)
        assertNull(notification.args.mentionedPersons)
    }
}
