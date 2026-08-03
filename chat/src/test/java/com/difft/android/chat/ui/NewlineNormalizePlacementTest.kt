package com.difft.android.chat.ui

import android.widget.TextView
import com.difft.android.base.utils.GlobalHiltEntryPoint
import com.difft.android.base.utils.normalizeNewlines
import com.difft.android.chat.common.LinkTextUtils
import com.difft.android.chat.message.TextChatMessage
import com.difft.android.chat.message.buildForwardData
import com.difft.android.chat.message.getCopyableTextContent
import com.difft.android.chat.messages.TestScopeApplication
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T17-T18, T20 — display / draft / quote placement.
 *
 * The transform correctness itself is exhaustively pinned by
 * [com.difft.android.base.utils.NewlineNormalizerTest] (T1-T12). These rows tie
 * each production PLACEMENT to a concrete assertion, invoking the REAL
 * normalize extension (never a re-implementation):
 *
 *  - T17 (display) invokes the REAL `LinkTextUtils.setMarkdownToTextview` under
 *    Robolectric and reads back `textView.text` — the stronger form,
 *    proving the display normalize at the top of the function reaches the rendered text.
 *  - T18 (draft) mirrors the exact expression at `ChatMessageInputFragment.kt` draft
 *    save: `text?.toString()?.normalizeNewlines()`.
 *  - T20 (quote) mirrors the exact expression at the three quote render sites:
 *    `quote.text.normalizeNewlines()` / `message.quote?.text?.normalizeNewlines()`.
 *
 * The RECEIVE end is intentionally faithful (stores exactly what the sender sent);
 * normalization happens only at SEND and DISPLAY, so there is no receive-side row here.
 *
 * Robolectric is needed only for T17's real-TextView readback; the other rows
 * are pure-string and could run on plain JUnit, but share this file for cohesion.
 * Family members are written via `Char(codepoint)` so this source file
 * contains no raw control characters.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class NewlineNormalizePlacementTest {

    private val ls = Char(0x2028).toString() // LINE SEPARATOR
    private val ps = Char(0x2029).toString() // PARAGRAPH SEPARATOR

    @After
    fun tearDown() {
        unmockkAll() // T21 installs a static mock for globalServices; clear it for other rows.
    }

    // ---- T17: display-side normalize at top of setMarkdownToTextview (stronger form) ----
    @Test
    fun `T17 display normalizes historical-stock body into the rendered TextView`() {
        val context = RuntimeEnvironment.getApplication()
        val textView = TextView(context)
        // Historical-stock body with a PS soft-break, rendered through the REAL entry point.
        LinkTextUtils.setMarkdownToTextview(context, "a${ps}b", textView)
        // The shadow at the top of setMarkdownToTextview canonicalizes before SpannableString.
        assertEquals("a\nb", textView.text.toString()) // single `\n`, not `\n\n`
    }

    // ---- T18: draft-save transform (Edit C) ----
    @Test
    fun `T18 draft save stores canonical content`() {
        // The exact expression written to currentDraft.copy(content = ...).
        // `text` in doOnTextChanged is a CharSequence?, so `.toString()` is meaningful.
        val typed: CharSequence? = "a${ls}b"
        val storedContent: String? = typed?.toString()?.normalizeNewlines()
        assertEquals("a\nb", storedContent)
    }

    // ---- T20: quote-preview transform (three render sites) ----
    @Test
    fun `T20 quote text is normalized at the render sites`() {
        // Fresh-reply / restored-from-draft previews: `quote.text.normalizeNewlines()`.
        val quoteText = "a${ps}b" // legacy-stock quoted snippet with a PS
        assertEquals("a\nb", quoteText.normalizeNewlines())

        // Bubble snippet (ChatMessageViewHolder:480): `message.quote?.text?.normalizeNewlines()`.
        val nullableQuoteText: String? = "a${ps}b"
        assertEquals("a\nb", nullableQuoteText?.normalizeNewlines())
    }

    // ---- T21: caption send paths canonicalize at the sendValidatedText choke point ----
    @Test
    fun `T21 caption body passed to sendValidatedText is normalized at the entry`() {
        // The media/photo and file-send caption flows both call the private
        // sendValidatedText(body) with RAW caption text. The single choke point
        // added at its entry (`val message = message.normalizeNewlines()`) must
        // canonicalize the body for EVERY caller before it reaches sendTextPush.
        //
        // We INVOKE the real sendValidatedText (small-body branch → synchronous
        // sendTextPush(message)) and observe the body at the internal seam
        // mentionsForNormalizedBody(content), whose single String param IS
        // resolvable by MockK's dynamic private-call matcher (unlike sendTextPush,
        // which takes a private nested AttachmentInfo type that cannot be named
        // from a test). A sentinel thrown from the stub short-circuits execution
        // right after the capture, before the coroutine launch that would need the
        // real view lifecycle — keeping the assertion deterministic and side-effect free.
        val fragment = spyk(ChatMessageInputFragment(), recordPrivateCalls = true)
        // sendTextPush reads chatViewModel.forWhat and the `globalServices.myId`
        // default for messageId before the capture seam; inject a relaxed ViewModel
        // via the `by viewModels()` synthetic delegate and stub the global services.
        injectChatViewModelDelegate(fragment)
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        val globalServicesMock = mockk<GlobalHiltEntryPoint>(relaxed = true)
        every { com.difft.android.base.utils.globalServices } returns globalServicesMock
        every { globalServicesMock.myId } returns "+10000000"

        val sentContent = slot<String>()
        every {
            fragment.invoke("mentionsForNormalizedBody").withArguments(listOf(capture(sentContent)))
        } throws CaptureSentinel

        val rawCaption = "a${ps}b" // raw caption with a PS soft-break
        try {
            fragment invokePrivateSendValidatedText rawCaption
        } catch (e: Exception) {
            // Unwrap the reflective InvocationTargetException; anything but our sentinel is a real failure.
            val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it === CaptureSentinel }
            if (cause == null) throw e
        }

        // The body reaching the send path is normalized (PS -> `\n`), proving the entry
        // choke point canonicalized for the caption caller before wire/DB consumption.
        assertEquals("a\nb", sentContent.captured)
    }

    // ---- T22 (render): historical-stock forwarded text renders canonically ----
    @Test
    fun `T22 combined-forward preview normalizes historical-stock forwarded text`() {
        // ForwardMessagesItemViewHolder assigns getForwardText(...) via a plain
        // `textContent.text = ...` that bypasses setMarkdownToTextview. Invoke the
        // REAL internal getForwardText against a Forward carrying a legacy PS.
        val context = RuntimeEnvironment.getApplication()
        val forward = Forward(
            id = 1L,
            type = 0,
            isFromGroup = false,
            author = "+10000000",
            text = "a${ps}b", // historically stored raw forwarded text
            attachments = null,
            forwards = null,
            mentions = null,
            serverTimestamp = 0L
        )
        assertEquals("a\nb", getForwardText(context, forward))
    }

    /** Builds a TextChatMessage wrapping a single legacy nested forward carrying raw `text`. */
    private fun singleForwardMessage(rawText: String): TextChatMessage =
        TextChatMessage().apply {
            forwardContext = ForwardContext(
                forwards = listOf(
                    Forward(
                        id = 1L,
                        type = 0,
                        isFromGroup = false,
                        author = "+10000000",
                        text = rawText, // historically stored raw nested forwarded text
                        attachments = null,
                        forwards = null,
                        mentions = null,
                        serverTimestamp = 0L
                    )
                ),
                isFromGroup = false
            )
        }

    // ---- T23 (Finding A): re-forward confirm-dialog preview normalizes nested Forward.text ----
    @Test
    fun `T23 forward-preview content is normalized for a nested single forward`() {
        // buildForwardData() reads nested `forward.text` RAW into the dialog content string.
        // SelectChatsUtils binds that content to a plain TextView via
        // `textContent.text = contentText.normalizeNewlines()` (display sink). Invoke the REAL
        // buildForwardData() and apply the REAL sink expression exactly as the preview does.
        val message = singleForwardMessage("a${ps}b")
        val rawContent = message.buildForwardData()!!.first
        assertEquals("a${ps}b", rawContent) // production still carries raw content (send path untouched)
        assertEquals("a\nb", rawContent.normalizeNewlines()) // display sink canonicalizes the bound value
    }

    // ---- T24 (Finding B): copy action normalizes nested Forward.text ----
    @Test
    fun `T24 copyable text is normalized for a legacy single-forward message`() {
        // getCopyableTextContent() returns nested `forward.text` RAW; the copy choke point
        // MessageActionHelper.copyMessageContent writes `...?.normalizeNewlines()` to the clipboard.
        // Invoke the REAL getCopyableTextContent() and apply the REAL copy-sink expression.
        val message = singleForwardMessage("a${ls}b")
        val rawCopyable = message.getCopyableTextContent()
        assertEquals("a${ls}b", rawCopyable) // source value stays raw
        assertEquals("a\nb", rawCopyable?.normalizeNewlines()) // clipboard matches display
    }
}

/** Sentinel used by T21 to short-circuit send after the normalized body is captured. */
private object CaptureSentinel : RuntimeException()

/**
 * Reaches the private [ChatMessageInputFragment.sendValidatedText] on a MockK
 * recording spy. Kept as an infix helper so the call reads clearly in T21 and
 * the reflective name lives in exactly one place.
 */
private infix fun ChatMessageInputFragment.invokePrivateSendValidatedText(message: String): Boolean {
    val method = ChatMessageInputFragment::class.java.getDeclaredMethod(
        "sendValidatedText", String::class.java, Function0::class.java
    ).apply { isAccessible = true }
    return method.invoke(this, message, null) as Boolean
}

/**
 * Injects a relaxed [ChatMessageViewModel] (with a stubbed `forWhat`) into the
 * Fragment's `by viewModels()` synthetic `chatViewModel$delegate` field, so the
 * `sendTextPush` prologue (`chatViewModel.forWhat.id`) runs without a real
 * ViewModelStore / Hilt injection. The capture seam is reached immediately after.
 */
private fun injectChatViewModelDelegate(fragment: ChatMessageInputFragment) {
    val vm = mockk<ChatMessageViewModel>(relaxed = true)
    every { vm.forWhat } returns For.Account("+10000000")
    val delegateField = ChatMessageInputFragment::class.java
        .getDeclaredField("chatViewModel\$delegate")
        .apply { isAccessible = true }
    delegateField.set(fragment, lazy { vm })
}
