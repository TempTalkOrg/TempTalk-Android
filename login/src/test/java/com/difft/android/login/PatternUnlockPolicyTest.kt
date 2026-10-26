package com.difft.android.login

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stored pattern in these tests is "1,3,9,8" on the 3x3 grid, i.e. ids 0,2,8,7, saved under the
 * retired selection rule unless a test says otherwise.
 */
class PatternUnlockPolicyTest {

    private val storedSequence = listOf(0, 2, 8, 7)
    private val storedKey = PatternUnlockPolicy.sequenceKey(storedSequence)

    // The same gesture under the new rule also selects the dots it crossed: 1,2,3,6,9,8.
    private val newRuleSequence = listOf(0, 1, 2, 5, 8, 7)

    private class Checker(private val accepted: String) {
        val checked = mutableListOf<String>()
        fun matches(key: String): Boolean {
            checked += key
            return key == accepted
        }
    }

    private fun evaluate(
        newSequence: List<Int>,
        legacySequence: List<Int>,
        createdWithNewRule: Boolean = false,
        attempts: Int = 0,
        checker: Checker = Checker(storedKey),
    ) = PatternUnlockPolicy.evaluate(newSequence, legacySequence, createdWithNewRule, attempts, checker::matches) to checker

    @Test
    fun `T4 a single dot is ignored and T5 two or three dots are only a hint`() {
        assertEquals(PatternUnlockPolicy.EntryKind.IGNORED, PatternUnlockPolicy.classify(0))
        assertEquals(PatternUnlockPolicy.EntryKind.IGNORED, PatternUnlockPolicy.classify(1))
        assertEquals(PatternUnlockPolicy.EntryKind.TOO_SHORT, PatternUnlockPolicy.classify(2))
        assertEquals(PatternUnlockPolicy.EntryKind.TOO_SHORT, PatternUnlockPolicy.classify(3))
        assertEquals(PatternUnlockPolicy.EntryKind.VERIFY, PatternUnlockPolicy.classify(4))
        assertEquals(PatternUnlockPolicy.EntryKind.VERIFY, PatternUnlockPolicy.classify(9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `T5 a short entry can never reach verification`() {
        evaluate(listOf(0, 1, 2), listOf(0, 1, 2))
    }

    @Test
    fun `a correct new-rule entry unlocks with one check`() {
        val (result, checker) = evaluate(storedSequence, storedSequence)

        assertEquals(PatternUnlockPolicy.Result.Unlocked(viaLegacyRule = false), result)
        assertEquals(listOf(storedKey), checker.checked)
    }

    @Test
    fun `T6 a wrong entry is charged one attempt and follows the ladder`() {
        val wrong = listOf(0, 1, 2, 5)
        val cases = listOf(
            0 to PatternUnlockPolicy.Ladder.None,
            3 to PatternUnlockPolicy.Ladder.None,
            4 to PatternUnlockPolicy.Ladder.Delay(1),
            5 to PatternUnlockPolicy.Ladder.Delay(4),
            6 to PatternUnlockPolicy.Ladder.Delay(9),
            7 to PatternUnlockPolicy.Ladder.Delay(16),
            8 to PatternUnlockPolicy.Ladder.Warning,
            9 to PatternUnlockPolicy.Ladder.Logout,
        )
        for ((before, ladder) in cases) {
            val (result, _) = evaluate(wrong, wrong, createdWithNewRule = true, attempts = before)
            assertEquals("attempts before=$before", PatternUnlockPolicy.Result.Failed(before + 1, 1, ladder), result)
        }
    }

    @Test
    fun `T11 a legacy pattern unlocks through the old-rule sequence with one failed check first`() {
        val (result, checker) = evaluate(newRuleSequence, storedSequence)

        assertEquals(PatternUnlockPolicy.Result.Unlocked(viaLegacyRule = true), result)
        assertEquals(listOf(PatternUnlockPolicy.sequenceKey(newRuleSequence), storedKey), checker.checked)
    }

    @Test
    fun `T14 both candidates failing charges two attempts`() {
        val wrongNew = listOf(0, 1, 2, 5)
        val wrongLegacy = listOf(0, 2, 5, 8)

        val (result, checker) = evaluate(wrongNew, wrongLegacy, attempts = 2)

        assertEquals(PatternUnlockPolicy.Result.Failed(attempts = 4, checked = 2, ladder = PatternUnlockPolicy.Ladder.None), result)
        assertEquals(2, checker.checked.size)
    }

    @Test
    fun `T15 identical sequences are checked once and charged once`() {
        val wrong = listOf(0, 1, 2, 5)

        val (result, checker) = evaluate(wrong, wrong, attempts = 2)

        assertEquals(PatternUnlockPolicy.Result.Failed(attempts = 3, checked = 1, ladder = PatternUnlockPolicy.Ladder.None), result)
        assertEquals(1, checker.checked.size)
    }

    @Test
    fun `a legacy sequence shorter than four dots is not verified`() {
        val (result, checker) = evaluate(listOf(0, 1, 2, 5), listOf(0, 2, 5))

        assertEquals(1, (result as PatternUnlockPolicy.Result.Failed).checked)
        assertEquals(1, checker.checked.size)
    }

    @Test
    fun `T17 at nine attempts the correct legacy gesture still unlocks`() {
        val (result, _) = evaluate(newRuleSequence, storedSequence, attempts = 9)

        assertEquals(PatternUnlockPolicy.Result.Unlocked(viaLegacyRule = true), result)
    }

    @Test
    fun `T17 at nine attempts a wrong gesture with two candidates reaches eleven and logs out`() {
        val wrongNew = listOf(0, 1, 2, 5)
        val wrongLegacy = listOf(0, 2, 5, 8)

        val (result, _) = evaluate(wrongNew, wrongLegacy, attempts = 9)

        assertEquals(PatternUnlockPolicy.Result.Failed(attempts = 11, checked = 2, ladder = PatternUnlockPolicy.Ladder.Logout), result)
    }

    @Test
    fun `T17 at eight attempts two failed candidates jump over the warning to logout`() {
        val (result, _) = evaluate(listOf(0, 1, 2, 5), listOf(0, 2, 5, 8), attempts = 8)

        assertEquals(PatternUnlockPolicy.Ladder.Logout, (result as PatternUnlockPolicy.Result.Failed).ladder)
    }

    @Test
    fun `T12 a pattern created under the new rule only ever checks the new sequence`() {
        // The old-rule sequence would match an older pattern, but this one carries the marker.
        val (result, checker) = evaluate(newRuleSequence, storedSequence, createdWithNewRule = true)

        assertEquals(PatternUnlockPolicy.Result.Failed(attempts = 1, checked = 1, ladder = PatternUnlockPolicy.Ladder.None), result)
        assertEquals(listOf(PatternUnlockPolicy.sequenceKey(newRuleSequence)), checker.checked)
    }

    @Test
    fun `T12 a new-rule pattern still unlocks with its own sequence`() {
        val (result, _) = evaluate(newRuleSequence, storedSequence, createdWithNewRule = true, checker = Checker(PatternUnlockPolicy.sequenceKey(newRuleSequence)))

        assertEquals(PatternUnlockPolicy.Result.Unlocked(viaLegacyRule = false), result)
    }

    @Test
    fun `a legacy pattern whose new-rule sequence is short still unlocks through the legacy one`() {
        // The two rules start on different dots: new [1, 2, 5], legacy [0, 1, 2, 5] (the stored one).
        val legacy = listOf(0, 1, 2, 5)
        val newShort = listOf(1, 2, 5)
        assertEquals(4, PatternUnlockPolicy.entryLength(newShort, legacy, createdWithNewRule = false))
        assertEquals(3, PatternUnlockPolicy.entryLength(newShort, legacy, createdWithNewRule = true))

        val (result, checker) = evaluate(newShort, legacy, checker = Checker(PatternUnlockPolicy.sequenceKey(legacy)))

        assertEquals(PatternUnlockPolicy.Result.Unlocked(viaLegacyRule = true), result)
        assertEquals(listOf(PatternUnlockPolicy.sequenceKey(legacy)), checker.checked)
    }

    @Test
    fun `a short new-rule sequence is not verified and costs nothing beyond the legacy check`() {
        val (result, checker) = evaluate(listOf(1, 2, 5), listOf(0, 1, 2, 4))

        assertEquals(PatternUnlockPolicy.Result.Failed(attempts = 1, checked = 1, ladder = PatternUnlockPolicy.Ladder.None), result)
        assertEquals(listOf("0,1,2,4"), checker.checked)
    }

    @Test
    fun `stored pattern marker round trips and legacy strings stay readable`() {
        val marked = PatternCredential("aa", "bb", createdWithNewRule = true).format()
        assertEquals("aa:bb:1", marked)
        assertEquals(PatternCredential("aa", "bb", true), PatternCredential.parse(marked))

        val legacy = PatternCredential.parse("aa:bb")
        assertEquals(PatternCredential("aa", "bb", false), legacy)
        assertFalse(legacy!!.createdWithNewRule)
        assertEquals("aa:bb", legacy.format())
        // Readers that only take the first two parts are unaffected by the marker.
        assertEquals(listOf("aa", "bb"), marked.split(":").take(2))
        assertNull(PatternCredential.parse("garbage"))
        assertTrue(PatternCredential.parse("aa:bb:1")!!.createdWithNewRule)
    }
}
