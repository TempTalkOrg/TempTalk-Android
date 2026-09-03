package com.difft.android.chat.message

import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.Reaction
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.difft.app.database.attachment
import org.difft.app.database.forwardContext
import org.difft.app.database.hydration.MessageSubData
import org.difft.app.database.mentions
import org.difft.app.database.models.MessageModel
import org.difft.app.database.quote
import org.difft.app.database.reactions
import org.difft.app.database.screenShot
import org.difft.app.database.sharedContacts
import org.difft.app.database.speechToTextData
import org.difft.app.database.test.builders.buildMessageModel
import org.difft.app.database.translateData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * Cases #36..#38 — `generateMessageTwo`'s zero-DB-access contract.
 *
 * The whole point of the `MessageSubData` parameter is that this function reads no child table at
 * all any more: the eight point queries it used to issue per message (with `forwardContext()` issued
 * TWICE) are replaced by field reads on data the hydrator resolved once for the whole window. The
 * `mockkStatic` here is what makes that observable — with the facade mocked, any surviving point
 * query would either be caught by `verify(exactly = 0)` or blow up as an unstubbed call.
 */
class GenerateMessageTwoSubDataTest {

    private val forWhat = For.Account(PEER_ID)

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        mockkStatic("org.difft.app.database.WCDBExtensionsKt")
        val globalServicesMock: GlobalHiltEntryPoint = mockk(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns MY_ID
        every { globalServicesMock.gson } returns Gson()
        // The ONE call generateMessageTwo still makes into this facade: an in-memory json parse.
        every { any<MessageModel>().screenShot() } returns null
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
        unmockkStatic("org.difft.app.database.WCDBExtensionsKt")
    }

    // #36 — the double forwardContext() call is gone structurally: the "is this a combined forward"
    // branch and the assigned field read the SAME reference out of subData.
    @Test
    fun `forward context comes from subData and is never re-queried`() {
        val forwardContext = ForwardContext(emptyList(), false)
        val record = buildMessageModel(id = "m1", systemShowTimestamp = 1_000L)

        val message = generateMessageTwo(
            forWhat, record, emptyList(), null, false, 0,
            MessageSubData(forwardContext = forwardContext),
        ) as TextChatMessage

        verify(exactly = 0) { any<MessageModel>().forwardContext() }
        assertEquals("", message.message)
        assertSame(forwardContext, message.forwardContext)
    }

    // #37 — the zero-DB-access contract in full, plus the one deliberate exception.
    @Test
    fun `no child-table point query runs and screenShot is still read in memory`() {
        val record = buildMessageModel(id = "m1", systemShowTimestamp = 1_000L)
        val subData = MessageSubData(
            mentions = listOf(Mention(start = 0, length = 1, uid = "uid", type = 0)),
            reactions = listOf(Reaction(emoji = "emoji", uid = "uid", originTimestamp = 1L)),
        )

        val message = generateMessageTwo(
            forWhat, record, emptyList(), null, false, 0, subData,
        ) as TextChatMessage

        verify(exactly = 0) { any<MessageModel>().attachment() }
        verify(exactly = 0) { any<MessageModel>().quote() }
        verify(exactly = 0) { any<MessageModel>().forwardContext() }
        verify(exactly = 0) { any<MessageModel>().mentions() }
        verify(exactly = 0) { any<MessageModel>().reactions() }
        verify(exactly = 0) { any<MessageModel>().sharedContacts() }
        verify(exactly = 0) { any<MessageModel>().translateData() }
        verify(exactly = 0) { any<MessageModel>().speechToTextData() }
        verify(exactly = 1) { any<MessageModel>().screenShot() }
        // The sub-data really did land on the message (a silently empty result would also satisfy
        // the verifications above).
        assertEquals(subData.mentions, message.mentions)
        assertEquals(subData.reactions, message.reactions)
        assertEquals("msg-m1", message.message)
    }

    // #38 — the two non-TEXT branches never consume subData; passing EMPTY must produce exactly the
    // same object as before the parameter existed.
    @Test
    fun `notify branch is unaffected by subData`() {
        val record = buildMessageModel(
            id = "n1",
            systemShowTimestamp = 2_000L,
            fromWho = PEER_ID,
            type = MessageModel.TYPE_NOTIFY,
            messageText = """{"showContent":"joined the group"}""",
        ).apply {
            sequenceId = 42L
            notifySequenceId = 7L
        }

        val message = generateMessageTwo(
            forWhat, record, emptyList(), null, false, 0, MessageSubData.EMPTY,
        ) as NotifyChatMessage

        assertEquals("n1", message.id)
        assertEquals(PEER_ID, message.authorId)
        assertFalse(message.isMine)
        assertEquals(2_000L, message.systemShowTimestamp)
        assertEquals(2_000L, message.timeStamp)
        assertEquals(7L, message.notifySequenceId)
        assertEquals(42L, message.readMaxSId)
        assertEquals("joined the group", message.notifyMessage?.showContent)
        verify(exactly = 0) { any<MessageModel>().screenShot() }
    }

    @Test
    fun `confidential placeholder branch is unaffected by subData`() {
        val record = buildMessageModel(
            id = "c1",
            systemShowTimestamp = 3_000L,
            fromWho = PEER_ID,
            type = MessageModel.TYPE_CONFIDENTIAL_PLACEHOLDER,
        ).apply { sequenceId = 9L }

        val message = generateMessageTwo(
            forWhat, record, emptyList(), null, false, 0, MessageSubData.EMPTY,
        ) as ConfidentialPlaceholderChatMessage

        assertEquals("c1", message.id)
        assertEquals(PEER_ID, message.authorId)
        assertEquals(3_000L, message.systemShowTimestamp)
        assertEquals(9L, message.readMaxSId)
        verify(exactly = 0) { any<MessageModel>().screenShot() }
    }

    // EMPTY sub-data on a TEXT message must degrade to "no child rows", not to a crash or a stale
    // read — this is the shape every message without child rows takes.
    @Test
    fun `empty subData yields a text message with no child data`() {
        val record = buildMessageModel(id = "m2", systemShowTimestamp = 4_000L)

        val message = generateMessageTwo(
            forWhat, record, emptyList(), null, false, 0, MessageSubData.EMPTY,
        ) as TextChatMessage

        assertNotNull(message.message)
        assertNull(message.attachment)
        assertNull(message.quote)
        assertNull(message.forwardContext)
        assertNull(message.translateData)
        assertNull(message.speechToTextData)
        assertEquals(emptyList<Mention>(), message.mentions)
        assertEquals(emptyList<Reaction>(), message.reactions)
        assertFalse(message.isScreenShotMessage)
    }

    private companion object {
        const val MY_ID = "my-uid"
        const val PEER_ID = "peer-uid"
    }
}
