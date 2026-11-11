package com.difft.android.chat.jobs

import com.difft.android.websocket.internal.push.NotificationType
import com.google.gson.Gson
import io.mockk.mockk
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.MENTIONS_ALL_ID
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Quote
import difft.android.messageserialization.model.TextMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OutgoingNotificationBuilderMentionTest {

    private fun groupText(vararg mentionUids: String) = message(For.Group("gid-1"), *mentionUids)

    private fun message(forWhat: For, vararg mentionUids: String) = TextMessage(
        id = "m1",
        fromWho = For.Account("+sender"),
        forWhat = forWhat,
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

    private fun quote(author: String) = Quote(id = 9L, author = author, text = "q", attachments = null)

    @Test
    fun `a quote without mentions is a reply that lists the quoted author`() {
        val notification = buildTextMessageNotification(groupText().apply { quote = quote("+li") }, Gson())

        assertEquals(NotificationType.GROUP_REPLY_DESTINATION.code, notification.type)
        assertContentEquals(arrayOf("+li"), notification.args.mentionedPersons)
    }

    @Test
    fun `explicit mentions win over the quote`() {
        val notification = buildTextMessageNotification(groupText("+zhang").apply { quote = quote("+li") }, Gson())

        assertEquals(NotificationType.GROUP_MENTIONS_DESTINATION.code, notification.type)
        assertContentEquals(arrayOf("+zhang"), notification.args.mentionedPersons)
    }

    @Test
    fun `a quote with an attachment stays a file message`() {
        val message = groupText().apply {
            quote = quote("+li")
            attachments = listOf(mockk<Attachment>(relaxed = true))
        }
        val notification = buildTextMessageNotification(message, Gson())

        assertEquals(NotificationType.GROUP_FILE.code, notification.type)
        assertNull(notification.args.mentionedPersons)
    }

    @Test
    fun `the list survives the conversion to the transmitted payload and its JSON`() {
        val sent = buildTextMessageNotification(groupText().apply { quote = quote("+li") }, Gson()).toNewNotification()
        assertEquals(listOf("+li"), sent.args.mentionedPersons)
        assertTrue(Gson().toJson(sent).contains("\"mentionedPersons\":[\"+li\"]"))

        val mention = buildTextMessageNotification(groupText("+zhang"), Gson()).toNewNotification()
        assertEquals(listOf("+zhang"), mention.args.mentionedPersons)

        assertNull(buildTextMessageNotification(groupText(), Gson()).toNewNotification().args.mentionedPersons)
    }

    @Test
    fun `a quote in a one-on-one chat stays a personal message`() {
        val message = message(For.Account("+peer")).apply { quote = quote("+peer") }
        val notification = buildTextMessageNotification(message, Gson())

        assertEquals(NotificationType.PERSONAL_NORMAL.code, notification.type)
        assertNull(notification.args.mentionedPersons)
    }
}
