package com.difft.android.chat.recent

import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_ALERT
import difft.android.messageserialization.model.CRITICAL_ALERT_TYPE_NONE
import difft.android.messageserialization.model.MENTIONS_TYPE_ALL
import difft.android.messageserialization.model.MENTIONS_TYPE_ME
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_FAILED
import difft.android.messageserialization.model.ROOM_SEND_STATUS_NONE
import difft.android.messageserialization.model.ROOM_SEND_STATUS_SENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T2-1 … T2-9 — chat-list preview tag assembly.
 *
 * Pure JVM: [buildTagSegments] / [joinTags] / [detailColorRes] take no View, Context or TextPaint.
 * Width-driven degradation is a separate concern, covered by [SelectVisibleTagsTest].
 *
 * Verify: :chat:testDebugUnitTest
 */
class ChatListTagAssemblyTest {

    /** Mirrors the English `values/strings.xml` values, brackets included. */
    private val enLabels = ChatListTagLabels(
        criticalAlert = "[🚨 Critical Alert]",
        sendFailed = "[Send failed]",
        atYou = "[@You]",
        atAll = "[@All]",
        draft = "[Draft]",
    )

    private fun segments(
        criticalAlertType: Int = CRITICAL_ALERT_TYPE_NONE,
        sendStatus: Int = ROOM_SEND_STATUS_NONE,
        mentionType: Int = MENTIONS_TYPE_NONE,
        hasDraft: Boolean = false,
    ): List<TagSegment> = buildTagSegments(
        criticalAlertType = criticalAlertType,
        sendStatus = sendStatus,
        mentionType = mentionType,
        hasDraft = hasDraft,
        labels = enLabels,
    )

    // ── T2-1 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-1 all four tags come back in priority order`() {
        val result = segments(
            criticalAlertType = CRITICAL_ALERT_TYPE_ALERT,
            sendStatus = ROOM_SEND_STATUS_FAILED,
            mentionType = MENTIONS_TYPE_ME,
            hasDraft = true,
        )
        assertEquals(4, result.size)
        assertEquals(
            listOf(
                ChatListTag.CRITICAL_ALERT,
                ChatListTag.SEND_FAILED,
                ChatListTag.MENTION,
                ChatListTag.DRAFT,
            ),
            result.map { it.tag },
        )
    }

    // ── T2-2 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-2 joined four-tag run is the exact designed string`() {
        val joined = joinTags(
            segments(
                criticalAlertType = CRITICAL_ALERT_TYPE_ALERT,
                sendStatus = ROOM_SEND_STATUS_FAILED,
                mentionType = MENTIONS_TYPE_ME,
                hasDraft = true,
            )
        ).toString()
        assertEquals("[🚨 Critical Alert] · [Send failed] · [@You] · [Draft]", joined)
        // The separator is U+00B7 MIDDLE DOT surrounded by spaces, not an ASCII interpunct look-alike.
        assertEquals(" · ", TAG_SEPARATOR)
    }

    @Test
    fun `T2-2b a single tag carries no separator`() {
        val joined = joinTags(segments(mentionType = MENTIONS_TYPE_ME)).toString()
        assertEquals("[@You]", joined)
        assertFalse(joined.contains(TAG_SEPARATOR))
    }

    // ── T2-3 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-3 mention has three states`() {
        assertEquals(
            enLabels.atAll,
            segments(mentionType = MENTIONS_TYPE_ALL).single { it.tag == ChatListTag.MENTION }.text,
        )
        assertEquals(
            enLabels.atYou,
            segments(mentionType = MENTIONS_TYPE_ME).single { it.tag == ChatListTag.MENTION }.text,
        )
        assertTrue(segments(mentionType = MENTIONS_TYPE_NONE).none { it.tag == ChatListTag.MENTION })
    }

    // ── T2-4 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-4 only FAILED earns the send-failed tag`() {
        assertTrue(
            segments(sendStatus = ROOM_SEND_STATUS_NONE).none { it.tag == ChatListTag.SEND_FAILED }
        )
        // SENDING deliberately renders nothing this release.
        assertTrue(
            segments(sendStatus = ROOM_SEND_STATUS_SENDING).none { it.tag == ChatListTag.SEND_FAILED }
        )
        assertEquals(
            enLabels.sendFailed,
            segments(sendStatus = ROOM_SEND_STATUS_FAILED)
                .single { it.tag == ChatListTag.SEND_FAILED }.text,
        )
    }

    // ── T2-5 / T2-6 ──────────────────────────────────────────────────────────

    @Test
    fun `T2-5 no critical alert means no critical-alert tag`() {
        assertTrue(
            segments(criticalAlertType = CRITICAL_ALERT_TYPE_NONE)
                .none { it.tag == ChatListTag.CRITICAL_ALERT }
        )
        assertEquals(
            enLabels.criticalAlert,
            segments(criticalAlertType = CRITICAL_ALERT_TYPE_ALERT)
                .single { it.tag == ChatListTag.CRITICAL_ALERT }.text,
        )
    }

    @Test
    fun `T2-6 no draft means no draft tag`() {
        assertTrue(segments(hasDraft = false).none { it.tag == ChatListTag.DRAFT })
        assertEquals(
            enLabels.draft,
            segments(hasDraft = true).single { it.tag == ChatListTag.DRAFT }.text,
        )
    }

    // ── T2-7 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-7 a room with nothing to show yields an empty run`() {
        val result = segments()
        assertTrue(result.isEmpty())
        assertEquals("", joinTags(result).toString())
    }

    // ── T2-8 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-8 declaration order holds for every state combination`() {
        val alertStates = listOf(CRITICAL_ALERT_TYPE_NONE, CRITICAL_ALERT_TYPE_ALERT)
        val sendStates =
            listOf(ROOM_SEND_STATUS_NONE, ROOM_SEND_STATUS_SENDING, ROOM_SEND_STATUS_FAILED)
        val mentionStates = listOf(MENTIONS_TYPE_NONE, MENTIONS_TYPE_ME, MENTIONS_TYPE_ALL)
        val draftStates = listOf(false, true)

        var combinations = 0
        for (alert in alertStates) {
            for (send in sendStates) {
                for (mention in mentionStates) {
                    for (draft in draftStates) {
                        combinations++
                        val result = segments(alert, send, mention, draft)
                        val label = "alert=$alert send=$send mention=$mention draft=$draft"
                        // Ordinal-sorted == itself: the invariant selectVisibleTags relies on when it
                        // picks the LAST droppable segment as the next victim.
                        assertEquals(label, result.sortedBy { it.tag.ordinal }, result)
                        assertEquals(label, result.distinctBy { it.tag }, result)
                    }
                }
            }
        }
        assertEquals(36, combinations)
    }

    // ── T2-9 ─────────────────────────────────────────────────────────────────

    @Test
    fun `T2-9 preview colour is emphasized only for unread and unmuted`() {
        val primary = com.difft.android.base.R.color.t_primary
        val third = com.difft.android.base.R.color.t_third
        assertEquals(primary, detailColorRes(unreadMessageNum = 3, isMuted = false))
        assertEquals(third, detailColorRes(unreadMessageNum = 3, isMuted = true))
        assertEquals(third, detailColorRes(unreadMessageNum = 0, isMuted = false))
        assertEquals(third, detailColorRes(unreadMessageNum = 0, isMuted = true))
    }
}
