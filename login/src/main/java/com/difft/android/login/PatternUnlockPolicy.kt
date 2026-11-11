package com.difft.android.login








internal object PatternUnlockPolicy {

    const val MIN_DOTS = 4

    
    private const val LOGOUT_ATTEMPTS = 10
    private const val WARNING_ATTEMPTS = 9
    private const val DELAY_FROM_ATTEMPTS = 5

    enum class EntryKind {
        
        IGNORED,

        
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
        
        data class Unlocked(val viaLegacyRule: Boolean) : Result

        
        data class Failed(val attempts: Int, val checked: Int, val ladder: Ladder) : Result
    }

    
    fun entryLength(newSequence: List<Int>, legacySequence: List<Int>, createdWithNewRule: Boolean): Int =
        if (createdWithNewRule) newSequence.size else maxOf(newSequence.size, legacySequence.size)

    fun classify(dotCount: Int): EntryKind = when {
        dotCount <= 1 -> EntryKind.IGNORED
        dotCount < MIN_DOTS -> EntryKind.TOO_SHORT
        else -> EntryKind.VERIFY
    }

    
    fun ladderFor(attempts: Int): Ladder = when {
        attempts >= LOGOUT_ATTEMPTS -> Ladder.Logout
        attempts == WARNING_ATTEMPTS -> Ladder.Warning
        attempts >= DELAY_FROM_ATTEMPTS -> Ladder.Delay((attempts - 4) * (attempts - 4))
        else -> Ladder.None
    }

    





    fun evaluate(
        newSequence: List<Int>,
        legacySequence: List<Int>,
        createdWithNewRule: Boolean,
        currentAttempts: Int,
        matches: (String) -> Boolean,
    ): Result {
        
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
