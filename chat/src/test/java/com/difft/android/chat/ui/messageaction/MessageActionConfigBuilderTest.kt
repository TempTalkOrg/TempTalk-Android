package com.difft.android.chat.ui.messageaction

import android.graphics.Rect
import com.difft.android.base.utils.IGlobalConfigsManager
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.ui.messageaction.MessageAction.Type
import com.difft.android.test.rules.GlobalStaticMockRule
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.FLAG_GIF
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SpeechToTextData
import difft.android.messageserialization.model.SpeechToTextStatus
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Config-layer contract (design-report §8.2, C1-C33). Verifies the full-expansion action
 * set + ordering (Master Order) + reaction-bar visibility that
 * [MessageActionConfigBuilder.build] produces for every B1-B6 variant, plus the
 * deleteSaved/moreInfo factory property changes.
 *
 * Pure list construction — no Android runtime needed. globalServices.myId is mocked by
 * [GlobalStaticMockRule]; ServerTimeProvider is pinned deterministically via resetForTest;
 * the recall timeout falls back to the built-in 24h default (getNewGlobalConfigs()==null).
 *
 * Run: ./gradlew :chat:testDebugUnitTest --tests "*MessageActionConfigBuilderTest"
 */
class MessageActionConfigBuilderTest {

    @get:Rule
    val globalMocks = GlobalStaticMockRule()

    private val globalConfigsManager: IGlobalConfigsManager = mockk {
        every { getNewGlobalConfigs() } returns null // → default 24h recall timeout
    }

    private lateinit var builder: MessageActionConfigBuilder

    @Before
    fun setUp() {
        // Pin trusted time so recall-timeout math is deterministic.
        ServerTimeProvider.resetForTest(wallClock = { NOW }, elapsedClock = { 0L })
        builder = MessageActionConfigBuilder(globalConfigsManager)
    }

    @After
    fun tearDown() {
        ServerTimeProvider.resetForTest(wallClock = { System.currentTimeMillis() }, elapsedClock = { 0L })
    }

    // ───────────────────────── helpers ─────────────────────────

    private fun types(
        message: TextChatMessage,
        isForForward: Boolean = false,
        isSaved: Boolean = false,
        mostUseEmojis: List<String>? = DEFAULT_EMOJIS
    ): List<Type> = build(message, isForForward, isSaved, mostUseEmojis).allActions.map { it.type }

    private fun build(
        message: TextChatMessage,
        isForForward: Boolean = false,
        isSaved: Boolean = false,
        mostUseEmojis: List<String>? = DEFAULT_EMOJIS
    ) = builder.build(message, mostUseEmojis, isForForward, isSaved, Rect())

    private fun att(contentType: String, flags: Int = 0) = Attachment(
        id = "att-1", authorityId = 1L, contentType = contentType, key = null, size = 1,
        thumbnail = null, digest = null, fileName = "f", flags = flags, width = 0, height = 0,
        path = null, status = AttachmentStatus.SUCCESS.code
    )

    private fun forward() = Forward(
        id = 1L, type = 0, isFromGroup = false, author = "a", text = "t",
        attachments = null, forwards = null, mentions = null, serverTimestamp = 0L
    )

    private fun base(mine: Boolean, timestamp: Long = NOW): TextChatMessage =
        TextChatMessage().apply {
            id = "msg-1"
            authorId = "author-1"
            isMine = mine
            systemShowTimestamp = timestamp
        }

    private fun text(mine: Boolean) = base(mine).apply { message = "hello" }
    private fun image(mine: Boolean) = base(mine).apply { attachment = att("image/jpeg") }
    private fun gif(mine: Boolean) = base(mine).apply { attachment = att("image/gif", flags = FLAG_GIF) }
    private fun file(mine: Boolean) = base(mine).apply { attachment = att("application/pdf") }
    private fun voice(mine: Boolean) = base(mine).apply { attachment = att("audio/aac", flags = 1) }
    private fun chatHistory(mine: Boolean) = base(mine).apply {
        forwardContext = ForwardContext(forwards = listOf(forward(), forward()), isFromGroup = false)
    }
    private fun card(mine: Boolean) = base(mine).apply {
        sharedContacts = listOf(mockk<SharedContact>(relaxed = true))
    }
    private fun confidential(mine: Boolean) = text(mine).apply {
        mode = SignalServiceProtos.Mode.CONFIDENTIAL_VALUE
    }

    // ───────────── B1: own, within timeout, !isSaved ─────────────

    @Test fun `C1 text own`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.TRANSLATE, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(text(mine = true))
    ).also { assertTrue(build(text(mine = true)).showReactionBar) }

    @Test fun `C2 image own`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(image(mine = true))
    ).also { assertTrue(build(image(mine = true)).showReactionBar) }

    @Test fun `C3 gif own`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.FAVORITE_GIF, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(gif(mine = true))
    ).also { assertTrue(build(gif(mine = true)).showReactionBar) }

    @Test fun `C4 file own`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(file(mine = true))
    ).also { assertTrue(build(file(mine = true)).showReactionBar) }

    @Test fun `C5 voice own`() = assertEquals(
        listOf(Type.QUOTE, Type.SPEECH_TO_TEXT, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(voice(mine = true))
    ).also { assertFalse(build(voice(mine = true)).showReactionBar) }

    @Test fun `C6 chat-history own`() = assertEquals(
        listOf(Type.QUOTE, Type.FORWARD, Type.MULTISELECT, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(chatHistory(mine = true))
    ).also { assertTrue(build(chatHistory(mine = true)).showReactionBar) }

    @Test fun `C7 card own`() = assertEquals(
        listOf(Type.QUOTE, Type.FORWARD, Type.MULTISELECT, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
        types(card(mine = true))
    ).also { assertFalse(build(card(mine = true)).showReactionBar) }

    @Test fun `C8 confidential own`() = assertEquals(
        listOf(Type.RECALL, Type.MORE_INFO),
        types(confidential(mine = true))
    ).also { assertFalse(build(confidential(mine = true)).showReactionBar) }

    // ───────────── B2: received (isMine=false) = B1 minus RECALL ─────────────

    @Test fun `C9 text received`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.TRANSLATE, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(text(mine = false))
    ).also { assertTrue(build(text(mine = false)).showReactionBar) }

    @Test fun `C10 image received`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(image(mine = false))
    ).also { assertTrue(build(image(mine = false)).showReactionBar) }

    @Test fun `C11 gif received`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.FAVORITE_GIF, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(gif(mine = false))
    ).also { assertTrue(build(gif(mine = false)).showReactionBar) }

    @Test fun `C12 file received`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(file(mine = false))
    ).also { assertTrue(build(file(mine = false)).showReactionBar) }

    @Test fun `C13 voice received`() = assertEquals(
        listOf(Type.QUOTE, Type.SPEECH_TO_TEXT, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(voice(mine = false))
    ).also { assertFalse(build(voice(mine = false)).showReactionBar) }

    @Test fun `C14 chat-history received`() = assertEquals(
        listOf(Type.QUOTE, Type.FORWARD, Type.MULTISELECT, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(chatHistory(mine = false))
    ).also { assertTrue(build(chatHistory(mine = false)).showReactionBar) }

    @Test fun `C15 card received`() = assertEquals(
        listOf(Type.QUOTE, Type.FORWARD, Type.MULTISELECT, Type.SAVE_TO_NOTE, Type.MORE_INFO),
        types(card(mine = false))
    ).also { assertFalse(build(card(mine = false)).showReactionBar) }

    @Test fun `C16 confidential received`() = assertEquals(
        listOf(Type.MORE_INFO),
        types(confidential(mine = false))
    ).also { assertFalse(build(confidential(mine = false)).showReactionBar) }

    // ───────── B3: saved (isSaved=true) = drop saveToNote + add deleteSaved (recall→delete→info) + noReact ─────────

    @Test fun `C17 text saved`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.TRANSLATE, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(text(mine = true), isSaved = true)
    ).also { assertFalse(build(text(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C18 image saved`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(image(mine = true), isSaved = true)
    ).also { assertFalse(build(image(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C19 gif saved`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.FAVORITE_GIF, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(gif(mine = true), isSaved = true)
    ).also { assertFalse(build(gif(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C20 file saved`() = assertEquals(
        listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.SAVE, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(file(mine = true), isSaved = true)
    ).also { assertFalse(build(file(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C21 voice saved`() = assertEquals(
        listOf(Type.QUOTE, Type.SPEECH_TO_TEXT, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(voice(mine = true), isSaved = true)
    ).also { assertFalse(build(voice(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C22 card saved`() = assertEquals(
        listOf(Type.QUOTE, Type.FORWARD, Type.MULTISELECT, Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(card(mine = true), isSaved = true)
    ).also { assertFalse(build(card(mine = true), isSaved = true).showReactionBar) }

    @Test fun `C23 confidential saved`() = assertEquals(
        listOf(Type.RECALL, Type.DELETE_SAVED, Type.MORE_INFO),
        types(confidential(mine = true), isSaved = true)
    ).also { assertFalse(build(confidential(mine = true), isSaved = true).showReactionBar) }

    // ───────── B6: forward mode (isForForward=true, mostUseEmojis=null) = drop take(3), full expansion ─────────

    @Test fun `C24 text forward-mode`() = assertEquals(
        listOf(Type.COPY, Type.FORWARD),
        types(text(mine = true), isForForward = true, mostUseEmojis = null)
    ).also { assertFalse(build(text(mine = true), isForForward = true, mostUseEmojis = null).showReactionBar) }

    @Test fun `C25 image forward-mode`() = assertEquals(
        listOf(Type.COPY, Type.FORWARD, Type.SAVE),
        types(image(mine = true), isForForward = true, mostUseEmojis = null)
    )

    @Test fun `C26 gif forward-mode`() = assertEquals(
        listOf(Type.COPY, Type.FORWARD, Type.SAVE, Type.FAVORITE_GIF),
        types(gif(mine = true), isForForward = true, mostUseEmojis = null)
    )

    @Test fun `C27 file forward-mode`() = assertEquals(
        listOf(Type.COPY, Type.FORWARD, Type.SAVE),
        types(file(mine = true), isForForward = true, mostUseEmojis = null)
    )

    // ───────────── state mutual-exclusion / property assertions ─────────────

    @Test fun `C28 text own translate showing yields TRANSLATE_OFF in translate slot`() {
        val msg = text(mine = true).apply {
            translateData = TranslateData(TranslateStatus.ShowCN, "cn", null)
        }
        assertEquals(
            listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.TRANSLATE_OFF, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
            types(msg)
        )
    }

    @Test fun `C29 voice own speech showing yields SPEECH_TO_TEXT_OFF in slot`() {
        val msg = voice(mine = true).apply {
            speechToTextData = SpeechToTextData(SpeechToTextStatus.Show, "text")
        }
        assertEquals(
            listOf(Type.QUOTE, Type.SPEECH_TO_TEXT_OFF, Type.SAVE_TO_NOTE, Type.RECALL, Type.MORE_INFO),
            types(msg)
        )
    }

    @Test fun `C30 recall timeout expired own text equals received B2`() {
        val expired = text(mine = true).apply { systemShowTimestamp = NOW - EXPIRED_DELTA_MS }
        assertEquals(
            listOf(Type.QUOTE, Type.COPY, Type.FORWARD, Type.MULTISELECT, Type.TRANSLATE, Type.SAVE_TO_NOTE, Type.MORE_INFO),
            types(expired)
        )
    }

    @Test fun `C31 deleteSaved is destructive red`() {
        val action = MessageAction.deleteSaved()
        assertEquals(com.difft.android.base.R.color.error, action.tintRes)
        assertTrue(action.isDestructive)
    }

    @Test fun `C32 moreInfo uses info icon and info label`() {
        val action = MessageAction.moreInfo()
        assertEquals(com.difft.android.chat.R.drawable.chat_message_action_info, action.iconRes)
        assertEquals(com.difft.android.chat.R.string.chat_message_action_info, action.labelRes)
    }

    @Test fun `C33 build full-expansion image own quickActions same as allActions`() {
        val config = build(image(mine = true))
        assertSame(config.allActions, config.quickActions)
        assertTrue(config.moreActions.isEmpty())
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val EXPIRED_DELTA_MS = 25L * 60 * 60 * 1000 // 25h > default 24h recall window
        val DEFAULT_EMOJIS = listOf("👍", "❤️", "😂")
    }
}
