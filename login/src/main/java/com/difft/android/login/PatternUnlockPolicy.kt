package com.difft.android.login

/**
 * Decisions for a pattern entry on the unlock screen, kept free of views so they can be tested.
 *
 * Stored patterns always have at least [MIN_DOTS] dots, so shorter entries are neither verified nor
 * counted. Patterns saved before the segment-aware dot selection (no [PatternCredential] marker)
 * may only match the sequence the retired rule produced, so that one is tried as a fallback.
 */
internal object PatternUnlockPolicy {

    const val MIN_DOTS = 4

    /** Failed attempts at which the next wrong entry forces a logout. */
    private const val LOGOUT_ATTEMPTS = 10
    private const val WARNING_ATTEMPTS = 9
    private const val DELAY_FROM_ATTEMPTS = 5

    enum class EntryKind {
        /** A stray touch: clear without any feedback. */
        IGNORED,

        /** Deliberate but too short to be a pattern: neutral hint, not counted. */
        TOO_SHORT,

        VERIFY,
    }

    sealed interface Ladder {
        data object None : Ladder
        data object Warning : Ladder
        data class Delay(val seconds: Int) : Ladder
        data object Logout : Ladder
    }

    sealed interface Result {
        /** [viaLegacyRule]: only the retired-rule sequence matched. */
        data class Unlocked(val viaLegacyRule: Boolean) : Result

        /** [attempts] is the new total; [checked] how many candidates were verified (and charged). */
        data class Failed(val attempts: Int, val checked: Int, val ladder: Ladder) : Result
    }

    /** Dot count an entry is classified by: the longer sequence when the legacy one will be tried. */
    fun entryLength(newSequence: List<Int>, legacySequence: List<Int>, createdWithNewRule: Boolean): Int =
        if (createdWithNewRule) newSequence.size else maxOf(newSequence.size, legacySequence.size)

    fun classify(dotCount: Int): EntryKind = when {
        dotCount <= 1 -> EntryKind.IGNORED
        dotCount < MIN_DOTS -> EntryKind.TOO_SHORT
        else -> EntryKind.VERIFY
    }

    /** Status to show for [attempts] failed attempts; 5..8 get a countdown, 10 logs out. */
    fun ladderFor(attempts: Int): Ladder = when {
        attempts >= LOGOUT_ATTEMPTS -> Ladder.Logout
        attempts == WARNING_ATTEMPTS -> Ladder.Warning
        attempts >= DELAY_FROM_ATTEMPTS -> Ladder.Delay((attempts - 4) * (attempts - 4))
        else -> Ladder.None
    }

    /**
     * Checks the new-rule sequence and, when eligible, the retired-rule one, before charging anything:
     * any match unlocks; otherwise every candidate actually checked costs one attempt.
     *
     * @param matches verifies a sequence key (see [sequenceKey]) against the stored hash.
     */
    fun evaluate(
        newSequence: List<Int>,
        legacySequence: List<Int>,
        createdWithNewRule: Boolean,
        currentAttempts: Int,
        matches: (String) -> Boolean,
    ): Result {
        // The two rules can disagree on the start dot, so either candidate may be the only one long enough.
        val candidates = buildList {
            if (newSequence.size >= MIN_DOTS) add(newSequence to false)
            val tryLegacy = !createdWithNewRule && legacySequence != newSequence && legacySequence.size >= MIN_DOTS
            if (tryLegacy) add(legacySequence to true)
        }
        require(candidates.isNotEmpty()) { "entry shorter than $MIN_DOTS dots must not be verified" }
        for ((sequence, viaLegacyRule) in candidates) {
            if (matches(sequenceKey(sequence))) return Result.Unlocked(viaLegacyRule)
        }

        val attempts = currentAttempts + candidates.size
        return Result.Failed(attempts, candidates.size, ladderFor(attempts))
    }

    fun sequenceKey(sequence: List<Int>): String = sequence.joinToString(",")
}
