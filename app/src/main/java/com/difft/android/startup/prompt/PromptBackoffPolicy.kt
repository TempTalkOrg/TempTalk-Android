package com.difft.android.startup.prompt

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Back-off of the startup prompts (#1355, decision D1 = time ladder): 1, 3, 7 and 30 days after
 * consecutive declines, then every 30 days. All times are trusted-clock millis.
 */
object PromptBackoffPolicy {

    private val LADDER_MS = longArrayOf(1.days.inWholeMilliseconds, 3.days.inWholeMilliseconds, 7.days.inWholeMilliseconds, 30.days.inWholeMilliseconds)

    /** A pending primary action this recent at session start means the session began with our own trip. */
    val OWN_TRIP_WINDOW_MS = 30.minutes.inWholeMilliseconds

    /** After this long since the host first became presentable, a newly chosen prompt is "late". */
    val LATE_PROMPT_MS = 5.seconds.inWholeMilliseconds

    /** Longest a lower ELIGIBLE prompt waits for a higher UNKNOWN one, from the first presentable moment. */
    val UNKNOWN_HOLD_MS = 15.seconds.inWholeMilliseconds

    /** Snooze length after the [declines]-th consecutive decline (1-based); 0 for none. */
    fun stepMillis(declines: Int): Long = when {
        declines <= 0 -> 0L
        declines > LADDER_MS.size -> LADDER_MS.last()
        else -> LADDER_MS[declines - 1]
    }

    /** One more decline: next ladder step from [now]. */
    fun declined(record: PromptRecord, now: Long): PromptRecord {
        val declines = record.declines + 1
        return record.copy(
            declines = declines,
            snoozeUntil = now + stepMillis(declines),
            showingSince = 0,
            pendingActionAt = 0,
        )
    }

    /**
     * A snooze longer than the record's own step can only come from a clock that moved back (for
     * example before the trusted clock anchored); restart that step from [now].
     */
    fun clamp(record: PromptRecord, now: Long): PromptRecord {
        val maxStep = stepMillis(record.declines)
        return if (record.snoozeUntil - now > maxStep) record.copy(snoozeUntil = now + maxStep) else record
    }

    fun isSnoozed(record: PromptRecord, now: Long): Boolean = record.snoozeUntil > now
}
