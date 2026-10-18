package com.difft.android.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.chat.R
import com.difft.android.chat.common.SendType
import com.difft.android.chat.setting.archive.MessageArchiveManager
import com.difft.android.websocket.api.messages.TTNotifyMessage
import com.google.gson.Gson
import difft.android.messageserialization.For
import difft.android.messageserialization.MessageStore
import difft.android.messageserialization.model.Message
import difft.android.messageserialization.model.NotifyMessage
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [LocalTranslateMessageCreator] — the local system message announcing a translate-setting
 * change.
 *
 * Follows [LocalMessageCreatorForwardNoticeTest]: the creator is constructed directly with mocked
 * dependencies, Robolectric only so the description string resolves.
 *
 * What each case asserts:
 *  - T51 the serialized notify JSON carries the comma-anchored `"notifyType":10022,` substring that
 *        `WCDBUpdateService`'s preview `notLike` filter matches — the property that keeps this
 *        message from reordering the conversation list
 *  - T42 / T58 the shown sentence is exactly the settings screen's description for the same value,
 *        so the two can never drift
 *  - the message is a [NotifyMessage] (never counted as unread) persisted with `putWhenNonExist`,
 *        which is what makes a repeated call idempotent at the store
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTranslateMessageCreatorTest {

    @RelaxedMockK
    lateinit var messageStore: MessageStore

    @MockK
    lateinit var messageArchiveManager: MessageArchiveManager

    private lateinit var context: Application
    private lateinit var creator: LocalTranslateMessageCreator
    private val gson = Gson()

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        context = ApplicationProvider.getApplicationContext()

        val globals = mockk<GlobalHiltEntryPoint>(relaxed = true)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { com.difft.android.base.utils.globalServices } returns globals
        every { globals.myId } returns MY_ID

        coEvery { messageArchiveManager.getMessageArchiveTime(any(), any()) } returns 0L

        creator = LocalTranslateMessageCreator(context, messageStore, messageArchiveManager, gson)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private suspend fun create(value: Int, forWhat: For = For.Group(ROOM)): NotifyMessage {
        val captured = slot<Message>()
        every { messageStore.putWhenNonExist(capture(captured)) } returns 1

        creator.createTranslateSettingChangedMessage(forWhat, value)

        assertTrue("the stored message must be a NotifyMessage", captured.captured is NotifyMessage)
        return captured.captured as NotifyMessage
    }

    // --- T51 ----------------------------------------------------------------

    @Test
    fun `T51 the notify JSON carries the comma-anchored notifyType the preview filter matches`() =
        runTest {
            val stored = create(1)

            assertTrue(
                "preview exclusion depends on this exact substring: ${stored.notifyContent}",
                stored.notifyContent.contains("\"notifyType\":10022,"),
            )
        }

    @Test
    fun `T51 notifyType is never the last key, so the trailing comma anchor always holds`() = runTest {
        val stored = create(0)
        val notify = gson.fromJson(stored.notifyContent, TTNotifyMessage::class.java)

        // showContent and display are always set, which is what keeps `notifyType` mid-object.
        assertEquals(TTNotifyMessage.NOTIFY_ACTION_TYPE_TRANSLATE_SETTING_CHANGED, notify.notifyType)
        assertEquals(1, notify.display)
        assertTrue(notify.showContent.orEmpty().isNotEmpty())
        assertNull("no payload is needed, the sentence is the whole message", notify.data)
    }

    // --- T42 / T58 ----------------------------------------------------------

    @Test
    fun `T42 the sentence is the settings screen description for the same value`() = runTest {
        val expected = mapOf(
            0 to R.string.chat_translate_setting_desc_off,
            1 to R.string.chat_translate_setting_desc_zh,
            2 to R.string.chat_translate_setting_desc_en,
        )

        expected.forEach { (value, res) ->
            val stored = create(value)
            val notify = gson.fromJson(stored.notifyContent, TTNotifyMessage::class.java)

            assertEquals("value=$value", context.getString(res), notify.showContent)
        }
    }

    @Test
    fun `the three values produce three different sentences`() = runTest {
        val sentences = (0..2).map {
            gson.fromJson(create(it).notifyContent, TTNotifyMessage::class.java).showContent
        }

        assertNotEquals(sentences[0], sentences[1])
        assertNotEquals(sentences[1], sentences[2])
    }

    // --- shape / idempotency ------------------------------------------------

    @Test
    fun `the message is addressed from me to the conversation and marked sent`() = runTest {
        val forWhat = For.Group(ROOM)

        val stored = create(1, forWhat)

        assertEquals(forWhat, stored.forWhat)
        assertEquals(For.Account(MY_ID), stored.fromWho)
        assertEquals(SendType.Sent.rawValue, stored.sendType)
    }

    @Test
    fun `each call goes through putWhenNonExist, where the store does the deduplication`() = runTest {
        every { messageStore.putWhenNonExist(any<Message>()) } returns 1

        creator.createTranslateSettingChangedMessage(For.Group(ROOM), 1)
        creator.createTranslateSettingChangedMessage(For.Group(ROOM), 1)

        verify(exactly = 2) { messageStore.putWhenNonExist(any<Message>()) }
    }

    @Test
    fun `a store failure is swallowed, never propagated to the settings screen`() = runTest {
        every { messageStore.putWhenNonExist(any<Message>()) } throws IllegalStateException("db down")

        // Must not throw: the setting itself is already stored and broadcast by this point.
        creator.createTranslateSettingChangedMessage(For.Group(ROOM), 1)
    }

    private companion object {
        const val MY_ID = "+19999"
        const val ROOM = "group-1"
    }
}
