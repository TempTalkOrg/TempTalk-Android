package com.difft.android.chat.recent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T4-1 … T4-11 — whole-tag degradation when the row runs out of horizontal space.
 *
 * Pure JVM: [selectVisibleTags] takes a `(String) -> Float` measurer instead of a `TextPaint`, so
 * every width threshold below is expressed in exact character counts and needs no Robolectric.
 * That the real `TextPaint` honours the same contract is pinned by
 * [ChatListTagMeasureAssumptionTest].
 *
 * Verify: :chat:testDebugUnitTest
 */
class SelectVisibleTagsTest {

    /** 10px per character — every threshold below is therefore exactly `text.length * 10f`. */
    private val measure: (String) -> Float = { it.length * 10f }

    private val criticalAlert = TagSegment(ChatListTag.CRITICAL_ALERT, "[Alert]")
    private val sendFailed = TagSegment(ChatListTag.SEND_FAILED, "[Send failed]")
    private val mention = TagSegment(ChatListTag.MENTION, "[@You]")
    private val draft = TagSegment(ChatListTag.DRAFT, "[Draft]")

    private val allFour = listOf(criticalAlert, sendFailed, mention, draft)

    private fun widthOf(vararg tags: TagSegment): Float = measure(joinTags(tags.toList()).toString())

    // ── T4-1 … T4-3 : drop count ─────────────────────────────────────────────

    @Test
    fun `T4-1 nothing is dropped when everything fits`() {
        assertEquals(allFour, selectVisibleTags(allFour, 10_000f, measure))
    }

    @Test
    fun `T4-2 the draft tag goes first`() {
        val result = selectVisibleTags(
            allFour,
            widthOf(criticalAlert, sendFailed, mention),
            measure,
        )
        assertEquals(listOf(criticalAlert, sendFailed, mention), result)
    }

    @Test
    fun `T4-3 the mention tag goes second`() {
        val result = selectVisibleTags(allFour, widthOf(criticalAlert, sendFailed), measure)
        assertEquals(listOf(criticalAlert, sendFailed), result)
    }

    // ── T4-4 / T4-5 : degenerate widths ──────────────────────────────────────

    @Test
    fun `T4-4 zero width still keeps both non-droppable tags`() {
        assertEquals(listOf(criticalAlert, sendFailed), selectVisibleTags(allFour, 0f, measure))
    }

    @Test(timeout = 1000)
    fun `T4-5 a negative width terminates and keeps the non-droppable tags`() {
        assertEquals(listOf(criticalAlert, sendFailed), selectVisibleTags(allFour, -500f, measure))
    }

    // ── T4-6 / T4-7 : the fit boundary ───────────────────────────────────────

    @Test
    fun `T4-6 an exact fit drops nothing`() {
        assertEquals(allFour, selectVisibleTags(allFour, widthOf(*allFour.toTypedArray()), measure))
    }

    @Test
    fun `T4-7 one pixel of overflow drops exactly one tag`() {
        val result =
            selectVisibleTags(allFour, widthOf(*allFour.toTypedArray()) - 1f, measure)
        assertEquals(listOf(criticalAlert, sendFailed, mention), result)
    }

    // ── T4-8 … T4-10 : composition edge cases ────────────────────────────────

    @Test
    fun `T4-8 non-droppable tags are never dropped even when they overflow`() {
        val onlyPinned = listOf(criticalAlert, sendFailed)
        assertEquals(onlyPinned, selectVisibleTags(onlyPinned, 0f, measure))
    }

    @Test
    fun `T4-9 an all-droppable run can degrade to nothing`() {
        assertEquals(emptyList<TagSegment>(), selectVisibleTags(listOf(mention, draft), 0f, measure))
    }

    @Test
    fun `T4-10 an empty run stays empty`() {
        assertEquals(emptyList<TagSegment>(), selectVisibleTags(emptyList(), 0f, measure))
    }

    // ── T4-11 : bounded work per bind ────────────────────────────────────────

    @Test
    fun `T4-11 measuring is bounded to three calls per bind`() {
        var calls = 0
        val counting: (String) -> Float = { calls++; measure(it) }
        selectVisibleTags(allFour, 0f, counting)
        assertTrue("measureText called $calls times", calls <= 3)
    }
}
