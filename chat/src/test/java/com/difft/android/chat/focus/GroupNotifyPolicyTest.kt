package com.difft.android.chat.focus

import com.difft.android.chat.focus.GroupNotifyPolicy.Category
import com.difft.android.chat.focus.GroupNotifyPolicy.Decision
import com.difft.android.chat.focus.GroupNotifyPolicy.Input
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions
import com.difft.android.chat.focus.GroupNotifyPolicy.NotifyLevel
import com.difft.android.chat.focus.GroupNotifyPolicy.Reason
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue





class GroupNotifyPolicyTest {

    private fun focusOn(vararg gids: String, mutate: EffectiveFocus.() -> EffectiveFocus = { this }) = EffectiveFocus(
        featureEnabled = true,
        active = true,
        groupIds = gids.toSet(),
        until = NOW + 3_600_000L,
    ).mutate()

    private fun group(
        category: Category,
        gid: String = "B",
        muted: Boolean = false,
        options: MuteOptions = MuteOptions(),
        level: NotifyLevel = NotifyLevel.ALL,
        focus: EffectiveFocus = EffectiveFocus(featureEnabled = true),
    ) = GroupNotifyPolicy.decide(Input(gid, category, muted, options, level, focus))

    private fun private(muted: Boolean, focus: EffectiveFocus = EffectiveFocus(featureEnabled = true)) =
        GroupNotifyPolicy.decide(Input(gid = null, category = Category.OTHER, muted = muted, focus = focus))

    private fun assertDecision(expected: Reason, actual: Decision, message: String? = null) {
        assertEquals(expected, actual.reason, message)
        assertEquals(expected in NOTIFYING, actual.notify, message)
    }

    

    @Test
    fun `step1 private chat follows mute only and ignores focus`() {
        val focus = focusOn("A")
        assertDecision(Reason.PRIVATE_PUSH, private(muted = false, focus = focus))
        assertDecision(Reason.PRIVATE_MUTED, private(muted = true, focus = focus))
        assertDecision(Reason.PRIVATE_MUTED, private(muted = true, focus = EffectiveFocus(featureEnabled = false)))
        assertDecision(Reason.PRIVATE_PUSH, private(muted = false, focus = EffectiveFocus(featureEnabled = false)))
    }

    

    @Test
    fun `step2 categorize`() {
        val all = GroupNotifyPolicy.MENTIONS_ALL_UID
        assertEquals(Category.OTHER, GroupNotifyPolicy.categorize(emptyList(), ME))
        assertEquals(Category.MENTION_ME, GroupNotifyPolicy.categorize(listOf(ME), ME))
        assertEquals(Category.MENTION_ME, GroupNotifyPolicy.categorize(listOf(OTHER, ME), ME))
        assertEquals(Category.MENTION_ALL, GroupNotifyPolicy.categorize(listOf(all), ME))
        
        assertEquals(Category.MENTION_ME, GroupNotifyPolicy.categorize(listOf(all, ME), ME))
        assertEquals(Category.MENTION_ME, GroupNotifyPolicy.categorize(listOf(ME, all), ME))
        assertEquals(Category.MENTION_ME, GroupNotifyPolicy.categorize(listOf(OTHER, all, ME), ME))
        
        assertEquals(Category.MENTION_ALL, GroupNotifyPolicy.categorize(listOf(all, OTHER), ME))
        assertEquals(Category.MENTION_ALL, GroupNotifyPolicy.categorize(listOf(OTHER, all), ME))
        assertEquals(Category.OTHER, GroupNotifyPolicy.categorize(listOf(OTHER), ME))
    }

    

    @Test
    fun `step3a focused group notifies every category regardless of mute and level`() {
        val focus = focusOn("A")
        for (category in Category.entries) {
            assertDecision(
                Reason.FOCUS_GROUP,
                group(category, gid = "A", muted = true, level = NotifyLevel.OFF, focus = focus),
                category.name,
            )
        }
    }

    @Test
    fun `step3 multi-group focus treats every listed group as focused`() {
        val focus = focusOn("A", "B")
        assertDecision(Reason.FOCUS_GROUP, group(Category.OTHER, gid = "A", focus = focus))
        assertDecision(Reason.FOCUS_GROUP, group(Category.OTHER, gid = "B", focus = focus))
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, gid = "C", focus = focus))

        
        val single = focusOn("A")
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ME, gid = "B", focus = single))
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, gid = "B", focus = single))
    }

    

    @Test
    fun `step3b default others options let only mention-me through`() {
        val focus = focusOn("A")
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ME, focus = focus))
        for (blocked in listOf(Category.OTHER, Category.MENTION_ALL, Category.GROUP_CALL)) {
            assertDecision(Reason.FOCUS_OTHER_BLOCK, group(blocked, focus = focus), blocked.name)
        }
        
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ME, muted = true, focus = focus))
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ME, level = NotifyLevel.OFF, focus = focus))
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, muted = false, level = NotifyLevel.ALL, focus = focus))
    }

    @Test
    fun `step3b all others options on`() {
        val focus = focusOn("A") { copy(othersAllowMentionAll = true, othersAllowGroupCall = true) }
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ALL, focus = focus))
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.GROUP_CALL, focus = focus))
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, focus = focus))
    }

    @Test
    fun `step3b others options can all be off`() {
        val focus = focusOn("A") { copy(othersAllowMentionMe = false) }
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.MENTION_ME, focus = focus))
    }

    @Test
    fun `step3 invalid focus counts every group as other including the invalid one`() {
        val focus = focusOn("A") { copy(invalid = true, invalidReason = 1) }
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, gid = "A", focus = focus))
        assertDecision(Reason.FOCUS_OTHER_ALLOW, group(Category.MENTION_ME, gid = "A", focus = focus))
        assertDecision(Reason.FOCUS_OTHER_BLOCK, group(Category.OTHER, gid = "C", focus = focus))
    }

    @Test
    fun `step3 focus that has expired falls through to the mute and level steps`() {
        
        val expired = EffectiveFocus.derive(
            GroupFocusState(true, FocusSnapshot(enabled = true, groupIds = listOf("A"), until = NOW, version = 2)),
            now = NOW,
        )
        assertFalse(expired.active)
        assertDecision(Reason.MUTE_BLOCK, group(Category.OTHER, muted = true, focus = expired))
        assertDecision(Reason.MUTE_ALLOW, group(Category.MENTION_ME, muted = true, focus = expired))
        assertDecision(Reason.LEVEL_ALLOW, group(Category.OTHER, gid = "A", focus = expired))
    }

    

    @Test
    fun `step4 muted without focus uses the default sub-options`() {
        assertDecision(Reason.MUTE_BLOCK, group(Category.OTHER, muted = true))
        assertDecision(Reason.MUTE_ALLOW, group(Category.MENTION_ME, muted = true))
        assertDecision(Reason.MUTE_BLOCK, group(Category.MENTION_ALL, muted = true))
        assertDecision(Reason.MUTE_BLOCK, group(Category.GROUP_CALL, muted = true))
    }

    @Test
    fun `step4 explicit sub-options`() {
        val options = MuteOptions(allowMentionMe = false, allowMentionAll = true, allowGroupCall = true)
        assertDecision(Reason.MUTE_BLOCK, group(Category.MENTION_ME, muted = true, options = options))
        assertDecision(Reason.MUTE_ALLOW, group(Category.MENTION_ALL, muted = true, options = options))
        assertDecision(Reason.MUTE_ALLOW, group(Category.GROUP_CALL, muted = true, options = options))
        assertDecision(Reason.MUTE_BLOCK, group(Category.OTHER, muted = true, options = options))
    }

    @Test
    fun `step4 mute ignores the level`() {
        assertDecision(Reason.MUTE_ALLOW, group(Category.MENTION_ME, muted = true, level = NotifyLevel.OFF))
        assertDecision(Reason.MUTE_BLOCK, group(Category.OTHER, muted = true, level = NotifyLevel.ALL))
    }

    

    @Test
    fun `step5 level ALL notifies everything`() {
        for (category in Category.entries) {
            assertDecision(Reason.LEVEL_ALLOW, group(category, level = NotifyLevel.ALL), category.name)
        }
    }

    @Test
    fun `step5 level OFF blocks all but group calls`() {
        for (category in Category.entries - Category.GROUP_CALL) {
            assertDecision(Reason.LEVEL_BLOCK, group(category, level = NotifyLevel.OFF), category.name)
        }
        assertDecision(Reason.LEVEL_ALLOW, group(Category.GROUP_CALL, level = NotifyLevel.OFF))
    }

    @Test
    fun `step5 level MENTION passes me and all`() {
        assertDecision(Reason.LEVEL_ALLOW, group(Category.MENTION_ME, level = NotifyLevel.MENTION))
        assertDecision(Reason.LEVEL_ALLOW, group(Category.MENTION_ALL, level = NotifyLevel.MENTION))
        assertDecision(Reason.LEVEL_BLOCK, group(Category.OTHER, level = NotifyLevel.MENTION))
        assertDecision(Reason.LEVEL_ALLOW, group(Category.GROUP_CALL, level = NotifyLevel.MENTION))
    }

    

    @Test
    fun `feature off keeps the old rules - mute blocks everything and focus is ignored`() {
        val off = EffectiveFocus(featureEnabled = false)
        for (category in Category.entries) {
            assertDecision(
                Reason.MUTE_BLOCK,
                group(category, muted = true, options = MuteOptions(true, true, true), focus = off),
                category.name,
            )
        }
        
        val stale = focusOn("A") { copy(featureEnabled = false) }
        assertDecision(Reason.LEVEL_ALLOW, group(Category.OTHER, gid = "B", focus = stale))
    }

    @Test
    fun `feature off and on give the same mention level result`() {
        val off = EffectiveFocus(featureEnabled = false)
        val on = EffectiveFocus(featureEnabled = true)
        for (focus in listOf(off, on)) {
            assertDecision(Reason.LEVEL_ALLOW, group(Category.MENTION_ME, level = NotifyLevel.MENTION, focus = focus))
            assertDecision(Reason.LEVEL_ALLOW, group(Category.MENTION_ALL, level = NotifyLevel.MENTION, focus = focus))
        }
        assertDecision(Reason.LEVEL_BLOCK, group(Category.OTHER, level = NotifyLevel.MENTION, focus = off))
        assertDecision(Reason.LEVEL_BLOCK, group(Category.MENTION_ME, level = NotifyLevel.OFF, focus = off))
    }

    

    @Test
    fun `EffectiveFocus derive requires the feature, enabled and an unreached until`() {
        val snapshot = FocusSnapshot(enabled = true, groupIds = listOf("A"), until = NOW + 10, version = 3)
        assertTrue(EffectiveFocus.derive(GroupFocusState(true, snapshot), NOW).active)
        assertFalse(EffectiveFocus.derive(GroupFocusState(false, snapshot), NOW).active)
        assertFalse(EffectiveFocus.derive(GroupFocusState(true, snapshot.copy(enabled = false)), NOW).active)
        assertFalse(EffectiveFocus.derive(GroupFocusState(true, snapshot), NOW + 10).active)
        assertTrue(EffectiveFocus.derive(GroupFocusState(true, snapshot.copy(until = null)), NOW + 1_000_000).active)
    }

    @Test
    fun `EffectiveFocus invalid is read from invalidReason only`() {
        val snapshot = FocusSnapshot(enabled = true, groupIds = listOf("A"), invalidReason = 0, version = 3)
        val derived = EffectiveFocus.derive(GroupFocusState(true, snapshot), NOW)
        assertTrue(derived.invalid)
        assertEquals(setOf("A"), derived.groupIds)
        assertFalse(derived.isFocusedGroup("A"))
        assertFalse(EffectiveFocus.derive(GroupFocusState(true, snapshot.copy(invalidReason = null)), NOW).invalid)
    }

    private companion object {
        const val ME = "+10001"
        const val OTHER = "+10002"
        const val NOW = 1_700_000_000_000L
        val NOTIFYING = setOf(
            Reason.PRIVATE_PUSH, Reason.FOCUS_GROUP, Reason.FOCUS_OTHER_ALLOW, Reason.MUTE_ALLOW, Reason.LEVEL_ALLOW,
        )
    }
}
