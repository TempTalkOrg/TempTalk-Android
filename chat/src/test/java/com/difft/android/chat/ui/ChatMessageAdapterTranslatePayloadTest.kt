package com.difft.android.chat.ui

import com.difft.android.chat.message.NotifyChatMessage
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.ui.ChatMessageAdapter.Companion.PAYLOAD_HIGHLIGHT
import com.difft.android.chat.ui.ChatMessageAdapter.Companion.PAYLOAD_SELECTION
import com.difft.android.chat.ui.ChatMessageAdapter.Companion.PAYLOAD_TRANSLATE
import com.difft.android.chat.ui.ChatMessageAdapter.Companion.changePayloadFor
import com.difft.android.chat.ui.ChatMessageAdapter.Companion.partialPayloadsFor
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The translate diff payload of [ChatMessageAdapter].
 *
 * Both decisions are pure functions on the adapter's companion, called by the DiffUtil callback and
 * by `onBindViewHolder`; testing them there avoids instantiating a RecyclerView adapter and its
 * whole ViewHolder tree for what is, in both cases, a two-line rule.
 *
 * What each case asserts:
 *  - T35  contentEqualsIgnoringTranslate: true when only translateData differs, false otherwise
 *  - T36  a translate-only difference yields PAYLOAD_TRANSLATE
 *  - T37  any other difference yields null (full rebind) — the regression guard
 *  - T38  a non-text message on either side yields null
 *  - T38b two recognised payloads both apply, and the row is NOT rebound in full afterwards
 *  - T38c a payload list on a holder with no partial path falls through to the full bind
 */
class ChatMessageAdapterTranslatePayloadTest {

    private fun message(
        id: String = "m1",
        body: String = "hello",
        data: TranslateData? = null,
        readStatus: Int = 0,
    ) = TextChatMessage().apply {
        this.id = id
        authorId = AUTHOR
        this.message = body
        this.readStatus = readStatus
        translateData = data
    }

    private val translated = TranslateData(TranslateStatus.ShowCN, "你好", null)

    // --- T35 ----------------------------------------------------------------

    @Test
    fun `T35 only a translateData difference is content-equal`() {
        val before = message()
        val after = message(data = translated)

        assertTrue(before.contentEqualsIgnoringTranslate(after))
        assertFalse("equals must still see the translation", before == after)
    }

    @Test
    fun `T35 a body difference is not content-equal`() {
        assertFalse(message(body = "hello").contentEqualsIgnoringTranslate(message(body = "hi")))
    }

    @Test
    fun `T35 a non-translate field difference is not content-equal`() {
        assertFalse(
            message(readStatus = 0).contentEqualsIgnoringTranslate(message(readStatus = 1))
        )
    }

    // --- T36 ----------------------------------------------------------------

    @Test
    fun `T36 a translate-only change asks for the translate payload`() {
        assertEquals(
            PAYLOAD_TRANSLATE,
            changePayloadFor(message(), message(data = translated)),
        )
    }

    @Test
    fun `T36 clearing a translation is also a translate-only change`() {
        assertEquals(
            PAYLOAD_TRANSLATE,
            changePayloadFor(message(data = translated), message(data = translated.copy(translateStatus = TranslateStatus.Invisible))),
        )
    }

    // --- T37 ----------------------------------------------------------------

    @Test
    fun `T37 a body change alongside the translation forces a full rebind`() {
        assertNull(changePayloadFor(message(body = "hello"), message(body = "hi", data = translated)))
    }

    @Test
    fun `T37 a plain content change forces a full rebind`() {
        assertNull(changePayloadFor(message(readStatus = 0), message(readStatus = 1)))
    }

    // --- T38 ----------------------------------------------------------------

    @Test
    fun `T38 a notify message on either side forces a full rebind`() {
        val notify = NotifyChatMessage().apply {
            id = "m1"
            authorId = AUTHOR
        }

        assertNull(changePayloadFor(notify, message()))
        assertNull(changePayloadFor(message(), notify))
        assertNull(changePayloadFor(notify, notify))
    }

    // --- T38b ---------------------------------------------------------------

    @Test
    fun `T38b two recognised payloads both apply in one partial bind`() {
        val partial = partialPayloadsFor(listOf(PAYLOAD_SELECTION, PAYLOAD_TRANSLATE), isMessageHolder = true)

        assertEquals(setOf(PAYLOAD_SELECTION, PAYLOAD_TRANSLATE), partial)
    }

    @Test
    fun `T38b all three recognised payloads can arrive together`() {
        val partial = partialPayloadsFor(
            listOf(PAYLOAD_HIGHLIGHT, PAYLOAD_TRANSLATE, PAYLOAD_SELECTION),
            isMessageHolder = true,
        )

        assertEquals(setOf(PAYLOAD_SELECTION, PAYLOAD_HIGHLIGHT, PAYLOAD_TRANSLATE), partial)
    }

    @Test
    fun `a single recognised payload applies only itself`() {
        assertEquals(
            setOf(PAYLOAD_TRANSLATE),
            partialPayloadsFor(listOf(PAYLOAD_TRANSLATE), isMessageHolder = true),
        )
    }

    // --- T38c ---------------------------------------------------------------

    @Test
    fun `T38c a payload on a holder with no partial path falls through to the full bind`() {
        assertEquals(
            emptySet<String>(),
            partialPayloadsFor(listOf(PAYLOAD_TRANSLATE), isMessageHolder = false),
        )
    }

    @Test
    fun `T38c an unrecognised payload falls through to the full bind`() {
        assertEquals(
            emptySet<String>(),
            partialPayloadsFor(listOf("payload_from_a_future_build"), isMessageHolder = true),
        )
    }

    @Test
    fun `no payload at all falls through to the full bind`() {
        assertEquals(emptySet<String>(), partialPayloadsFor(emptyList(), isMessageHolder = true))
    }

    private companion object {
        const val AUTHOR = "+10001"
    }
}
