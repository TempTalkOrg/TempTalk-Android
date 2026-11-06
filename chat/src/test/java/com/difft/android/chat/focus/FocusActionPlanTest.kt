package com.difft.android.chat.focus

import com.difft.android.chat.R
import com.difft.android.chat.focus.settings.StillNotifyItem
import org.junit.Test
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which flow a "focus this group" request starts, the duration sheet description and the end-time label. */
class FocusActionPlanTest {

    private fun focus(active: Boolean = true, invalid: Boolean = false, vararg gids: String = arrayOf("A"), feature: Boolean = true) =
        EffectiveFocus(
            featureEnabled = feature,
            active = active,
            invalid = invalid,
            groupIds = if (invalid || !active) emptySet() else gids.toSet(),
        )

    @Test
    fun `not focusing opens the duration sheet`() {
        assertEquals(FocusRequestPlan.DurationSheet, planFocusRequest(focus(active = false), "B"))
    }

    @Test
    fun `focusing another group confirms the switch`() {
        assertEquals(FocusRequestPlan.ConfirmSwitch("A"), planFocusRequest(focus(), "B"))
    }

    @Test
    fun `the focused group itself does nothing`() {
        assertEquals(FocusRequestPlan.None, planFocusRequest(focus(), "A"))
    }

    @Test
    fun `invalid focus reselects without asking`() {
        assertEquals(FocusRequestPlan.SwitchDirect, planFocusRequest(focus(invalid = true), "B"))
    }

    @Test
    fun `feature off does nothing`() {
        assertEquals(FocusRequestPlan.None, planFocusRequest(focus(feature = false), "B"))
        assertEquals(FocusRequestPlan.None, planFocusRequest(focus(active = false, feature = false), "B"))
    }

    @Test
    fun `durations map to the server values`() {
        assertEquals(listOf<Int?>(3600, 28800, null), FocusDuration.entries.map { it.seconds })
    }

    @Test
    fun `sheet description follows folding and names every item that still notifies`() {
        fun desc(fold: Boolean, me: Boolean, all: Boolean, call: Boolean) = focusSheetDescription(
            EffectiveFocus(foldOthers = fold, othersAllowMentionMe = me, othersAllowMentionAll = all, othersAllowGroupCall = call),
        )
        val defaults = desc(fold = true, me = true, all = false, call = false)
        assertEquals(R.string.focus_sheet_desc_fold_notify, defaults.res)
        assertEquals(listOf(StillNotifyItem.MENTION_ME), defaults.stillNotify)

        val meAndCall = desc(fold = false, me = true, all = false, call = true)
        assertEquals(R.string.focus_sheet_desc_notify, meAndCall.res)
        assertEquals(listOf(StillNotifyItem.MENTION_ME, StillNotifyItem.GROUP_CALL), meAndCall.stillNotify)

        // @All alone still counts: the description no longer depends on @Me only.
        assertEquals(listOf(StillNotifyItem.MENTION_ALL), desc(fold = true, me = false, all = true, call = false).stillNotify)

        assertEquals(R.string.focus_sheet_desc_fold, desc(fold = true, me = false, all = false, call = false).res)
        val plain = desc(fold = false, me = false, all = false, call = false)
        assertEquals(R.string.focus_sheet_desc_plain, plain.res)
        assertEquals(emptyList(), plain.stillNotify)
    }

    @Test
    fun `end label is today's clock time or flagged as another day`() {
        val zone = ZoneId.of("UTC")
        val now = java.time.Instant.parse("2026-10-28T10:00:00Z").toEpochMilli()

        val today = FocusTimeFormat.endLabel(now + 3_600_000, now, zone, Locale.US)
        assertFalse(today.tomorrow)
        assertEquals("11:00", today.time.replace(Regex("[^0-9:]"), ""))

        val tomorrow = FocusTimeFormat.endLabel(now + 24 * 3_600_000L, now, zone, Locale.US)
        assertTrue(tomorrow.tomorrow)

        // Midnight crossing: 23:30 today vs 00:30 the next day.
        val late = java.time.Instant.parse("2026-10-28T23:30:00Z").toEpochMilli()
        assertFalse(FocusTimeFormat.endLabel(late, now, zone, Locale.US).tomorrow)
        assertTrue(FocusTimeFormat.endLabel(late + 3_600_000, now, zone, Locale.US).tomorrow)
    }
}
