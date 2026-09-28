package com.difft.android.chat.translate

import com.difft.android.chat.message.TextChatMessage
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.Mention
import difft.android.messageserialization.model.TranslateData
import difft.android.messageserialization.model.TranslateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import java.util.concurrent.ConcurrentHashMap

/**
 * Candidate rules 1-10 of the auto-translate coordinator.
 *
 * Pure: no Robolectric, no MockK, no ML Kit. [toCandidate] is the single admission point every
 * scenario funnels through, so these cases are the behavioural contract for "which messages does
 * auto-translate ever touch".
 *
 * What each case asserts:
 *  - T1  outgoing message is never a candidate
 *  - T2  attachment / multi-forward / confidential / blank body are never candidates
 *  - T3  a single forward translates the forwarded text, not the (blank) wrapper body
 *  - T4  4097 characters is over the cap
 *  - T5  pure emoji / digits / URL / @mention carry no translatable letter
 *  - T6  hasAnyCachedTranslation truth table
 *  - T6b a cached EN translation blocks a ZH auto-translate (never overwrite a manual choice)
 *  - T7  admission is an atomic check-and-claim: the second call is rejected and claims nothing
 */
class TranslateCandidateTest {

    private lateinit var attempted: MutableSet<String>
    private val userDisabled = mutableSetOf<String>()

    private fun freshAttempted(): MutableSet<String> =
        ConcurrentHashMap.newKeySet<String>().also { attempted = it }

    private fun input(
        messageId: String = "m1",
        isMine: Boolean = false,
        mode: Int = 0,
        hasAttachment: Boolean = false,
        forwardCount: Int = 0,
        singleForwardText: String? = null,
        body: String? = "hello",
        mentions: List<Mention>? = null,
        translateData: TranslateData? = null,
    ) = TranslateCandidateInput(
        messageId = messageId,
        isMine = isMine,
        mode = mode,
        hasAttachment = hasAttachment,
        forwardCount = forwardCount,
        singleForwardText = singleForwardText,
        body = body,
        mentions = mentions,
        translateData = translateData,
    )

    private fun candidate(
        input: TranslateCandidateInput,
        target: TranslateLang = TranslateLang.ZH,
        claims: MutableSet<String> = freshAttempted(),
    ): TranslateJobInput? = toCandidate(input, target, claims, userDisabled)

    // --- T1 -----------------------------------------------------------------

    @Test
    fun `T1 an outgoing message is never auto-translated`() {
        assertNull(candidate(input(isMine = true)))
    }

    // --- T2 -----------------------------------------------------------------

    @Test
    fun `T2 attachment multi-forward confidential and blank bodies are all rejected`() {
        assertNull("attachment", candidate(input(hasAttachment = true)))
        assertNull("multi forward", candidate(input(forwardCount = 3, singleForwardText = "hi")))
        assertNull(
            "confidential",
            candidate(input(mode = SignalServiceProtos.Mode.CONFIDENTIAL_VALUE)),
        )
        assertNull("null body", candidate(input(body = null)))
        assertNull("blank body", candidate(input(body = "   ")))
    }

    // --- T3 -----------------------------------------------------------------

    @Test
    fun `T3 a single forward translates the forwarded text`() {
        // generateMessageTwo blanks the wrapper body for a forward, so the forwarded text is the
        // only thing left to translate.
        val result = candidate(input(forwardCount = 1, singleForwardText = "hello", body = ""))

        assertEquals(TranslateJobInput("m1", "hello"), result)
    }

    @Test
    fun `T3 a single forward with a blank forwarded text is rejected`() {
        assertNull(candidate(input(forwardCount = 1, singleForwardText = "  ", body = "not used")))
    }

    // --- T4 -----------------------------------------------------------------

    @Test
    fun `T4 a body over the length cap is rejected and one under it is accepted`() {
        assertNull(candidate(input(body = "a".repeat(MAX_TRANSLATABLE_LENGTH + 1))))
        assertNotNull(candidate(input(body = "a".repeat(MAX_TRANSLATABLE_LENGTH))))
    }

    // --- T5 -----------------------------------------------------------------

    @Test
    fun `T5 text with no letter left after stripping is not translatable`() {
        assertFalse("emoji", isTranslatableText("😀😀", null))
        assertFalse("digits", isTranslatableText("12345", null))
        assertFalse("url", isTranslatableText("https://a.com", null))
        assertFalse("bare www url", isTranslatableText("www.example.com/path?q=1", null))
        assertFalse("punctuation", isTranslatableText("!!! ??? ...", null))
    }

    @Test
    fun `T5 CJK and latin words are translatable`() {
        assertTrue("chinese", isTranslatableText("哈哈", null))
        assertTrue("english", isTranslatableText("ok", null))
        assertTrue("text around a url", isTranslatableText("look https://a.com now", null))
    }

    @Test
    fun `T5 a body that is only an at-mention is not translatable`() {
        val text = "@Alice"
        val mentions = listOf(Mention(start = 0, length = text.length, uid = "+1", type = 0))

        assertFalse(isTranslatableText(text, mentions))
        assertNull(candidate(input(body = text, mentions = mentions)))
    }

    @Test
    fun `T5 a mention followed by real words is translatable`() {
        val text = "@Alice hello"
        val mentions = listOf(Mention(start = 0, length = 6, uid = "+1", type = 0))

        assertTrue(isTranslatableText(text, mentions))
    }

    // --- T6 -----------------------------------------------------------------

    @Test
    fun `T6 hasAnyCachedTranslation truth table`() {
        assertTrue("CN filled", hasAnyCachedTranslation(TranslateData(TranslateStatus.ShowCN, "你好", null)))
        assertTrue("EN filled", hasAnyCachedTranslation(TranslateData(TranslateStatus.ShowEN, null, "hi")))
        assertTrue(
            "Invisible but content kept",
            hasAnyCachedTranslation(TranslateData(TranslateStatus.Invisible, null, "hi")),
        )
        assertFalse("null data", hasAnyCachedTranslation(null))
        assertFalse(
            "both slots empty",
            hasAnyCachedTranslation(TranslateData(TranslateStatus.Invisible, null, null)),
        )
    }

    // --- T6b ----------------------------------------------------------------

    @Test
    fun `T6b a manual EN translation switched off blocks a ZH auto-translate`() {
        val manualChoice = TranslateData(TranslateStatus.Invisible, null, "hi")

        assertNull(candidate(input(translateData = manualChoice), target = TranslateLang.ZH))
    }

    // --- T7 -----------------------------------------------------------------

    @Test
    fun `T7 admission is atomic - the second call is rejected and claims nothing`() {
        val claims = freshAttempted()

        val first = candidate(input(), claims = claims)
        val second = candidate(input(), claims = claims)

        assertNotNull(first)
        assertNull(second)
        assertEquals(setOf(attemptKey("m1", TranslateLang.ZH)), claims)
        assertEquals(1, claims.size)
    }

    @Test
    fun `T7 the same message is still admitted once per target`() {
        val claims = freshAttempted()

        assertNotNull(candidate(input(), target = TranslateLang.ZH, claims = claims))
        assertNotNull(candidate(input(), target = TranslateLang.EN, claims = claims))
        assertEquals(2, claims.size)
    }

    @Test
    fun `a message the user turned translation off for is never re-admitted`() {
        userDisabled.add("off-msg")
        try {
            assertNull(candidate(input(messageId = "off-msg")))
        } finally {
            userDisabled.remove("off-msg")
        }
    }

    // --- adapter ------------------------------------------------------------

    @Test
    fun `the TextChatMessage adapter feeds the same rules`() {
        val message = TextChatMessage().apply {
            id = "m9"
            isMine = false
            message = "hello"
        }

        val result = toCandidate(
            message.toTranslateCandidateInput(),
            TranslateLang.ZH,
            freshAttempted(),
            userDisabled,
        )

        assertEquals(TranslateJobInput("m9", "hello"), result)
    }

    @Test
    fun `the TextChatMessage adapter reads a single forward's text`() {
        val message = TextChatMessage().apply {
            id = "m10"
            forwardContext = ForwardContext(
                listOf(
                    Forward(
                        id = 1L,
                        type = 0,
                        isFromGroup = false,
                        author = "+10001",
                        text = "forwarded",
                        attachments = null,
                        forwards = null,
                        mentions = null,
                    )
                ),
                isFromGroup = false,
            )
        }

        assertEquals("forwarded", message.toTranslateCandidateInput().singleForwardText)
    }
}
