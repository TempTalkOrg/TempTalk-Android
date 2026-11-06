package com.difft.android.chat.focus.invalid

import com.difft.android.chat.R
import com.difft.android.chat.focus.EffectiveFocus
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which invalid reason shows which dialog, when a dialog is answered, and when the strip and the D4 body apply. */
class FocusInvalidRulesTest {

    private fun invalid(reason: Int?, version: Int = 5, gid: String = "A") = EffectiveFocus(
        featureEnabled = true,
        active = true,
        invalid = reason != null,
        invalidReason = reason,
        groupIds = setOf(gid),
        version = version,
    )

    // ---------------------------------------------------------------- dialog kind per reason

    @Test
    fun `dismissed and expired groups show D5, removed ones D5b`() {
        assertEquals(FocusInvalidKind.DISBANDED, FocusInvalidRules.pendingDialog(invalid(0), "")?.kind)
        assertEquals(FocusInvalidKind.DISBANDED, FocusInvalidRules.pendingDialog(invalid(4), "")?.kind)
        assertEquals(FocusInvalidKind.REMOVED, FocusInvalidRules.pendingDialog(invalid(1), "")?.kind)
        assertEquals(FocusInvalidKind.REMOVED, FocusInvalidRules.pendingDialog(invalid(3), "")?.kind)
    }

    @Test
    fun `a self leave and unknown reasons never show a dialog`() {
        assertNull(FocusInvalidRules.pendingDialog(invalid(2), ""))
        assertNull(FocusInvalidRules.pendingDialog(invalid(9), ""))
    }

    @Test
    fun `the pending dialog carries the version and the dead group`() {
        assertEquals(PendingFocusDialog(FocusInvalidKind.DISBANDED, "0:G1", 5, "G1"), FocusInvalidRules.pendingDialog(invalid(0, version = 5, gid = "G1"), ""))
    }

    // ---------------------------------------------------------------- handled version and clearing

    @Test
    fun `an answered episode is not shown again, but a different reason or group is`() {
        assertNull(FocusInvalidRules.pendingDialog(invalid(0, version = 5), handledEpisode = "0:A"))
        assertEquals(FocusInvalidKind.REMOVED, FocusInvalidRules.pendingDialog(invalid(1, version = 6), handledEpisode = "0:A")?.kind)
        assertEquals("0:B", FocusInvalidRules.pendingDialog(invalid(0, version = 6, gid = "B"), handledEpisode = "0:A")?.episode)
    }

    @Test
    fun `editing options while invalid bumps the version but keeps the episode answered`() {
        val episode = FocusInvalidRules.episodeKey(invalid(0, version = 5))
        assertNull(FocusInvalidRules.pendingDialog(invalid(0, version = 9), handledEpisode = episode))
        assertEquals(false, FocusInvalidRules.stripVisible(invalid(0, version = 9), dismissedEpisode = episode))
    }

    @Test
    fun `the episode key ignores group order`() {
        val a = invalid(0).copy(groupIds = linkedSetOf("B", "A"))
        val b = invalid(0).copy(groupIds = linkedSetOf("A", "B"))
        assertEquals(FocusInvalidRules.episodeKey(a), FocusInvalidRules.episodeKey(b))
    }

    @Test
    fun `a newer version that is no longer invalid clears the dialog`() {
        val reselected = EffectiveFocus(featureEnabled = true, active = true, invalid = false, groupIds = setOf("B"), version = 6)
        assertNull(FocusInvalidRules.pendingDialog(reselected, handledEpisode = ""))
        assertNull(FocusInvalidRules.pendingDialog(EffectiveFocus.INACTIVE, handledEpisode = ""))
    }

    // ---------------------------------------------------------------- status strip

    @Test
    fun `the strip shows for every invalid reason including a self leave`() {
        listOf(0, 1, 2, 3, 4).forEach { assertEquals(true, FocusInvalidRules.stripVisible(invalid(it), dismissedEpisode = ""), "reason $it") }
    }

    @Test
    fun `the strip hides once closed for the episode and returns for a different one`() {
        assertEquals(false, FocusInvalidRules.stripVisible(invalid(2, version = 5), dismissedEpisode = "2:A"))
        assertEquals(true, FocusInvalidRules.stripVisible(invalid(0, version = 6), dismissedEpisode = "2:A"))
        assertEquals(true, FocusInvalidRules.stripVisible(invalid(2, version = 6, gid = "B"), dismissedEpisode = "2:A"))
    }

    @Test
    fun `the strip is gone after a reselect, an end or an expiry`() {
        val reselected = EffectiveFocus(featureEnabled = true, active = true, invalid = false, groupIds = setOf("B"), version = 6)
        assertEquals(false, FocusInvalidRules.stripVisible(reselected, ""))
        assertEquals(false, FocusInvalidRules.stripVisible(EffectiveFocus.INACTIVE, ""))
        // Expired locally while invalid: derive() turns the state inactive, so it is no longer "invalid".
        assertEquals(false, FocusInvalidRules.stripVisible(EffectiveFocus(featureEnabled = true, active = false, invalid = false, version = 5), ""))
    }

    // ---------------------------------------------------------------- D4 leave body

    @Test
    fun `the leave body changes only for the current valid focus group`() {
        val valid = EffectiveFocus(featureEnabled = true, active = true, groupIds = setOf("A"), version = 3)
        assertEquals(R.string.focus_leave_focused_notice, FocusInvalidRules.leaveNoticeRes(valid, "A"))
        assertNull(FocusInvalidRules.leaveNoticeRes(valid, "B"))
        assertNull(FocusInvalidRules.leaveNoticeRes(invalid(0, gid = "A"), "A"))
        assertNull(FocusInvalidRules.leaveNoticeRes(EffectiveFocus.INACTIVE, "A"))
    }
}
