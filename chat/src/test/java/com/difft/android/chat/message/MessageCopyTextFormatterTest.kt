package com.difft.android.chat.message

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import difft.android.messageserialization.For
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.SharedContact
import difft.android.messageserialization.model.SharedContactName
import difft.android.messageserialization.model.TextMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Unit tests for [MessageCopyTextFormatter] (PRD §3).
 *
 * Robolectric provides Context for `getString` lookups. The formatter is otherwise pure:
 * nameResolver is injected, time formatting is deterministic (UTC-independent because we
 * assert against expected strings produced by SimpleDateFormat in the JVM's default tz).
 *
 * Locale is forced to en per test for deterministic placeholder text. A separate test
 * pins zh to verify locale-aware time + Chinese placeholders.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageCopyTextFormatterTest {

    private lateinit var context: Application

    private val alice = "+10001"
    private val bob = "+10002"
    private val carol = "+10003"

    private val nameResolver: (String) -> String = {
        when (it) {
            alice -> "Alice"
            bob -> "Bob"
            carol -> "Carol"
            else -> "U_$it"
        }
    }

    // 2026-05-08 14:30 UTC for assertion deterministic; pattern uses local tz so the
    // exact string varies by host tz. We assert structure (sender+content) and let
    // time format come from TimeFormatter — separate tests pin the time format.
    private val ts1 = 1_762_590_600_000L // arbitrary "May 2026" timestamp
    private val ts2 = ts1 + 60_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        forceLocale(Locale.ENGLISH)
    }

    private fun forceLocale(locale: Locale) {
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    // ----- 类型分支测试 -----

    @Test
    fun `text message uses raw text`() {
        val msg = textMsg(sender = alice, text = "Hello world")
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "Hello world"),
            out
        )
    }

    @Test
    fun `text message preserves newlines and emoji`() {
        val msg = textMsg(sender = alice, text = "line1\nline2 🎉")
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "line1\nline2 🎉"),
            out
        )
    }

    @Test
    fun `image attachment renders Image placeholder`() {
        val msg = textMsg(sender = alice, attachment = attachment(contentType = "image/jpeg"))
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Image]"),
            out
        )
    }

    @Test
    fun `video attachment renders Video placeholder`() {
        val msg = textMsg(sender = alice, attachment = attachment(contentType = "video/mp4"))
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Video]"),
            out
        )
    }

    @Test
    fun `voice attachment defensively renders Message fallback`() {
        // Voice is excluded from multi-select at the entry layer, but the formatter
        // must still degrade gracefully if a voice somehow makes it in.
        val msg = textMsg(sender = alice, attachment = attachment(contentType = "audio/m4a", flags = 1))
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Message]"),
            out
        )
    }

    @Test
    fun `file attachment renders Attachment with filename`() {
        val msg = textMsg(
            sender = alice,
            attachment = attachment(contentType = "application/pdf", fileName = "design.fig"),
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Attachment: design.fig]"),
            out
        )
    }

    @Test
    fun `file attachment without filename falls back to bare Attachment`() {
        val msg = textMsg(
            sender = alice,
            attachment = attachment(contentType = "application/pdf", fileName = null),
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Attachment]"),
            out
        )
    }

    @Test
    fun `contact card renders with sharedContact name`() {
        val msg = textMsg(
            sender = alice,
            sharedContactName = "Alice Wang",
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Contact Card] Alice Wang"),
            out
        )
    }

    @Test
    fun `contact card without name shows bare placeholder`() {
        val msg = textMsg(
            sender = alice,
            sharedContactName = null,
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Contact Card]"),
            out
        )
    }

    @Test
    fun `combined forward renders Chat History`() {
        val msg = textMsg(
            sender = alice,
            forwardContext = ForwardContext(emptyList(), false),
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Chat History]"),
            out
        )
    }

    @Test
    fun `empty message with no fields falls back to Message`() {
        val msg = textMsg(sender = alice, text = null)
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Message]"),
            out
        )
    }

    @Test
    fun `forwardContext takes precedence over text`() {
        // Even if text is non-empty, presence of forwardContext means this is a
        // combined-forward bubble and should render as [Chat History].
        val msg = textMsg(
            sender = alice,
            text = "should be ignored",
            forwardContext = ForwardContext(emptyList(), false),
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Chat History]"),
            out
        )
    }

    @Test
    fun `attachment takes precedence over text`() {
        val msg = textMsg(
            sender = alice,
            text = "caption ignored",
            attachment = attachment(contentType = "image/jpeg"),
        )
        val out = format(listOf(msg))
        assertEquals(
            buildExpected(sender = "Alice", ts = msg.systemShowTimestamp, content = "[Image]"),
            out
        )
    }

    // ----- 顺序与分隔 -----

    @Test
    fun `multiple messages from same sender are not merged — each gets its own header`() {
        val m1 = textMsg(sender = alice, text = "first", timestamp = ts1)
        val m2 = textMsg(sender = alice, text = "second", timestamp = ts2)
        val out = format(listOf(m1, m2))

        val expected = buildExpected("Alice", ts1, "first") + "\n\n" +
            buildExpected("Alice", ts2, "second")
        assertEquals(expected, out)
    }

    @Test
    fun `messages from different senders separated by blank line`() {
        val m1 = textMsg(sender = alice, text = "from A", timestamp = ts1)
        val m2 = textMsg(sender = bob, text = "from B", timestamp = ts2)
        val out = format(listOf(m1, m2))

        val expected = buildExpected("Alice", ts1, "from A") + "\n\n" +
            buildExpected("Bob", ts2, "from B")
        assertEquals(expected, out)
    }

    // ----- 名称卫生化(PRD §3.3) -----

    @Test
    fun `sender name with newline is sanitized to space`() {
        val msg = textMsg(sender = "+99999", text = "x")
        val sneakyResolver: (String) -> String = { "Bad\nName" }
        val out = MessageCopyTextFormatter.format(
            messages = listOf(msg),
            nameResolver = sneakyResolver,
            context = context,
            language = "en",
        )
        // First line should not contain a literal \n inside the sender portion
        val firstLine = out.lineSequence().first()
        assertEquals(true, firstLine.startsWith("Bad Name,"))
    }

    @Test
    fun `contact card name with control chars is sanitized`() {
        val msg = textMsg(
            sender = alice,
            sharedContactName = "Multi\nLine\tName",
        )
        val out = format(listOf(msg))
        // The content line should be on a single line — no literal newline inside it
        val lines = out.lines()
        // expected: 2 lines (header + content), not more
        assertEquals(2, lines.size)
        assertEquals("[Contact Card] Multi Line Name", lines[1])
    }

    // ----- locale 测试 -----

    @Test
    fun `chinese locale uses Chinese time format and Chinese placeholders`() {
        forceLocale(Locale.SIMPLIFIED_CHINESE)
        val msg = textMsg(sender = alice, attachment = attachment(contentType = "image/jpeg"))
        val out = MessageCopyTextFormatter.format(
            messages = listOf(msg),
            nameResolver = nameResolver,
            context = context,
            language = "zh",
        )
        val lines = out.lines()
        // 中文 placeholder
        assertEquals("[图片]", lines[1])
        // 中文时间格式应含"年""月""日"
        val header = lines[0]
        assertEquals(true, header.contains("年") && header.contains("月") && header.contains("日"))
    }

    // ----- helpers -----

    private fun format(messages: List<TextMessage>): String =
        MessageCopyTextFormatter.format(
            messages = messages,
            nameResolver = nameResolver,
            context = context,
            language = "en",
        )

    /**
     * Builds the expected formatted output for one message, using the same
     * TimeFormatter call the formatter uses — so the test does not pin a specific
     * timezone or string, only the structural contract.
     */
    private fun buildExpected(sender: String, ts: Long, content: String): String {
        val time = util.TimeFormatter.formatCopyHeaderTime("en", ts)
        return "$sender, [$time]\n$content"
    }

    private fun textMsg(
        sender: String,
        text: String? = null,
        attachment: Attachment? = null,
        sharedContactName: String? = NO_CONTACT,
        forwardContext: ForwardContext? = null,
        timestamp: Long = ts1,
    ): TextMessage = TextMessage(
        id = "msg-${timestamp}",
        fromWho = For.Account(sender),
        forWhat = For.Account("ME"),
        systemShowTimestamp = timestamp,
        timeStamp = timestamp,
        receivedTimeStamp = timestamp,
        sendType = 0,
        expiresInSeconds = 0,
        notifySequenceId = 0,
        sequenceId = 0,
        mode = 0,
        text = text,
        attachments = attachment?.let { listOf(it) },
        forwardContext = forwardContext,
        sharedContact = if (sharedContactName === NO_CONTACT) {
            null
        } else {
            listOf(
                SharedContact(
                    name = SharedContactName(
                        givenName = null,
                        familyName = null,
                        prefix = null,
                        suffix = null,
                        middleName = null,
                        displayName = sharedContactName,
                    ),
                    phone = null,
                    avatar = null,
                    email = null,
                    address = null,
                    organization = null,
                )
            )
        },
    )

    private fun attachment(
        contentType: String,
        fileName: String? = "file.bin",
        flags: Int = 0,
    ): Attachment = Attachment(
        id = "att-1",
        authorityId = 1L,
        contentType = contentType,
        key = null,
        size = 0,
        thumbnail = null,
        digest = null,
        fileName = fileName,
        flags = flags,
        width = 0,
        height = 0,
        path = null,
        status = 0,
    )

    companion object {
        // Sentinel so tests can distinguish "no shared contact" from "shared contact with null name"
        private const val NO_CONTACT = " NO_CONTACT_SENTINEL "
    }
}
