package com.difft.android.chat.mediasend

import android.content.Context
import com.difft.android.base.android.permission.MediaReadDenialKind
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T64-T69 — the wording a failure turns into, and the retry entry it may or may not offer.
 *
 * T64 is the constraint row: until an app restart tells the two mechanisms apart, the wording must
 * not assert that a permission was never granted — the reporting devices report full access while
 * still refusing the read.
 *
 * Run: :chat:testDebugUnitTest
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class MediaSendFailureNoticeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    // ---------------------------------------------------------------- T64

    /**
     * T64 — full access that still refuses the read, and no attribution at all, both get the wording
     * that only states the observable fact and offers an app restart.
     */
    @Test
    fun `unattributed read denial neither blames a permission nor omits the restart`() {
        val expected = context.getString(R.string.media_send_next_restart_or_file)

        listOf(MediaReadDenialKind.GRANTED_BUT_UNREADABLE, null).forEach { kind ->
            val text = MediaSendFailureNotice.nextStepText(
                context, failure(MediaFailureReason.SOURCE_UNREADABLE, kind)
            )
            assertEquals("denialKind=$kind", expected, text)
        }

        assertTrue(expected, expected.contains("Restart the app"))
        assertFalse(expected, expected.lowercase().contains("permission"))

        val zh = localized("zh").getString(R.string.media_send_next_restart_or_file)
        assertTrue(zh, zh.contains("重启应用"))
        assertFalse(zh, zh.contains("权限"))
    }

    // ---------------------------------------------------------------- T65

    /** T65 — the three attributable denials each get their own actionable next step. */
    @Test
    fun `each attributable denial maps to its own next step`() {
        val expectations = mapOf(
            MediaReadDenialKind.PERMISSION_MISSING to R.string.media_send_next_grant,
            MediaReadDenialKind.PARTIAL_SELECTION to R.string.media_send_next_reselect_photos,
            MediaReadDenialKind.NOT_MEDIA_SCOPED to R.string.media_send_next_reselect_item,
        )

        expectations.forEach { (kind, expectedRes) ->
            val text = MediaSendFailureNotice.nextStepText(
                context, failure(MediaFailureReason.SOURCE_UNREADABLE, kind)
            )
            assertEquals("denialKind=$kind", context.getString(expectedRes), text)
        }

        // "No longer available" must not claim the file was moved or deleted: nothing established that.
        val reselectItem = context.getString(R.string.media_send_next_reselect_item)
        assertFalse(reselectItem, reselectItem.lowercase().contains("moved"))
        assertFalse(reselectItem, reselectItem.lowercase().contains("deleted"))
        val reselectItemZh = localized("zh").getString(R.string.media_send_next_reselect_item)
        assertFalse(reselectItemZh, reselectItemZh.contains("已被移动或删除"))
    }

    /** The other four reasons each own one next step, so no reason falls through to a default. */
    @Test
    fun `every reason other than a read denial has its own next step`() {
        val expectations = mapOf(
            MediaFailureReason.MEDIA_UNSUPPORTED to context.getString(R.string.media_send_next_unsupported),
            MediaFailureReason.OUT_OF_SPACE to context.getString(R.string.media_send_next_free_space),
            MediaFailureReason.TRANSFORM_FAILED to context.getString(R.string.media_send_next_retry_or_plain),
            MediaFailureReason.UNKNOWN to context.getString(
                R.string.media_send_next_unknown, MediaFailureReason.UNKNOWN.code
            ),
        )

        expectations.forEach { (reason, expected) ->
            assertEquals(reason.name, expected, MediaSendFailureNotice.nextStepText(context, failure(reason, null)))
        }
    }

    // ---------------------------------------------------------------- T66

    /**
     * T66 — none of the new wording reuses the existing "file unavailable" string, which would
     * attribute every failure to a file that was moved or deleted.
     */
    @Test
    fun `no new string reuses the file unavailable wording`() {
        val forbidden = context.getString(R.string.file_unavailable)

        NEW_STRING_KEYS.forEach { res ->
            assertNotEquals(context.resources.getResourceEntryName(res), forbidden, rawString(context, res))
        }
    }

    // ---------------------------------------------------------------- T67

    /** T67 — both locales carry every key, with identical placeholders. */
    @Test
    fun `both locales define every key with the same placeholders`() {
        val zh = localized("zh")

        NEW_STRING_KEYS.forEach { res ->
            val name = context.resources.getResourceEntryName(res)
            val en = rawString(context, res)
            val cn = rawString(zh, res)
            assertTrue("$name (en) is blank", en.isNotBlank())
            assertTrue("$name (zh) is blank", cn.isNotBlank())
            assertEquals("$name placeholders", placeholdersOf(en), placeholdersOf(cn))
        }
    }

    // ---------------------------------------------------------------- T68

    /** T68 — retryability and the support code are exhaustive over the enum, by construction. */
    @Test
    fun `retryability and codes are exhaustive and unique`() {
        val nonRetryable = setOf(
            MediaFailureReason.SOURCE_UNREADABLE,
            MediaFailureReason.MEDIA_UNSUPPORTED,
            MediaFailureReason.OUT_OF_SPACE,
        )

        MediaFailureReason.entries.forEach { reason ->
            assertEquals(reason.name, reason !in nonRetryable, reason.retryable)
            assertTrue(reason.code, reason.code.matches(Regex("""MSND-0\d""")))
        }
        assertEquals(
            MediaFailureReason.entries.size,
            MediaFailureReason.entries.map { it.code }.distinct().size
        )
    }

    // ---------------------------------------------------------------- T69

    /** T69 — the body lists at most three items, counts the rest, and ends with exactly one next step. */
    @Test
    fun `body lists three items then counts the remainder and gives one next step`() {
        val failures = listOf(
            failure(MediaFailureReason.SOURCE_UNREADABLE, null, position = 1, displayName = "a.mp4"),
            failure(MediaFailureReason.SOURCE_UNREADABLE, null, position = 3, displayName = "b.jpg"),
            failure(MediaFailureReason.SOURCE_UNREADABLE, null, position = 5),
            failure(MediaFailureReason.SOURCE_UNREADABLE, null, position = 7),
            failure(MediaFailureReason.SOURCE_UNREADABLE, null, position = 9),
        )
        val reason = context.getString(R.string.media_send_reason_unreadable)

        val body = MediaSendFailureNotice.bodyOf(context, failures)
        val lines = body.lines().filter { it.isNotBlank() }

        assertEquals(body, 5, lines.size)          // 3 items + remainder + one next step
        assertEquals(context.getString(R.string.media_send_failed_item_named, 1, "a.mp4", reason), lines[0])
        assertEquals(context.getString(R.string.media_send_failed_item_named, 3, "b.jpg", reason), lines[1])
        assertEquals(context.getString(R.string.media_send_failed_item_unnamed, 5, reason), lines[2])
        assertEquals(context.getString(R.string.media_send_failed_more, 2), lines[3])
        assertEquals(context.getString(R.string.media_send_next_restart_or_file), lines[4])
        assertFalse(body, body.contains("7"))      // the truncated items are counted, not listed
    }

    private fun failure(
        reason: MediaFailureReason,
        denialKind: MediaReadDenialKind?,
        position: Int = 1,
        displayName: String? = null,
    ) = MediaFailure(position, displayName, reason, denialKind, cause = null)

    private fun localized(language: String): Context {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(java.util.Locale(language))
        return context.createConfigurationContext(configuration)
    }

    /**
     * Placeholder tokens (`%1$s`, `%2$d`, …), sorted: count and type must match per key, while
     * source order legitimately differs between locales because word order does.
     */
    private fun placeholdersOf(value: String): List<String> =
        Regex("""%\d+\$[a-zA-Z]""").findAll(value).map { it.value }.sorted().toList()

    /** Bypasses argument formatting so keys with placeholders can be compared as raw templates. */
    private fun rawString(source: Context, res: Int): String = source.resources.getString(res)

    private companion object {
        /**
         * Every string this failure surface introduced. Listed explicitly rather than derived from a
         * count so that adding a key without covering it here is a visible omission.
         */
        val NEW_STRING_KEYS = listOf(
            R.string.media_send_failed_title_single,
            R.string.media_send_failed_title_all,
            R.string.media_send_failed_title_partial,
            R.string.media_send_failed_item_named,
            R.string.media_send_failed_item_unnamed,
            R.string.media_send_failed_more,
            R.string.media_send_failed_named,
            R.string.media_send_failed_unnamed,
            R.string.media_send_failed_retry,
            R.string.media_send_failed_back,
            R.string.media_send_failed_send_rest,
            R.string.media_send_reason_unreadable,
            R.string.media_send_reason_unsupported,
            R.string.media_send_reason_no_space,
            R.string.media_send_reason_transform,
            R.string.media_send_reason_unknown,
            R.string.media_send_next_restart_or_file,
            R.string.media_send_next_grant,
            R.string.media_send_next_reselect_photos,
            R.string.media_send_next_reselect_item,
            R.string.media_send_next_unsupported,
            R.string.media_send_next_free_space,
            R.string.media_send_next_retry_or_plain,
            R.string.media_send_next_unknown,
        )
    }
}
