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

    /**
     * Background-connection slot: after an explicit switch-off every trigger waits this long. A decline
     * records the same floor, of which late-delivery evidence honors [LATE_DELIVERY_FLOOR_MS] and FCM
     * unavailable nothing beyond the ladder.
     */
    val BACKGROUND_CONNECTION_FLOOR_MS = 30.days.inWholeMilliseconds

    /**
     * Late-delivery evidence re-prompts at most once per evidence window ([LateDeliveryRule.WINDOW_DAYS])
     * after a decline, the ladder still applying on top: a user who tolerates slow push is asked weekly
     * at most, and the fourth decline reaches the 30-day step anyway.
     */
    val LATE_DELIVERY_FLOOR_MS = 7.days.inWholeMilliseconds

    /** When the record's floor started; the shorter per-trigger floors in `forRecord` count from here. */
    fun floorStart(record: PromptRecord): Long = record.floorUntil - BACKGROUND_CONNECTION_FLOOR_MS

    /** The dismissal floor of [id]'s slot; 0 for slots without one. */
    fun floorFor(id: PromptId): Long = if (id == PromptId.BACKGROUND_CONNECTION) BACKGROUND_CONNECTION_FLOOR_MS else 0L

    /** Snooze length after the [declines]-th consecutive decline (1-based); 0 for none. */
    fun stepMillis(declines: Int): Long = when {
        declines <= 0 -> 0L
        declines > LADDER_MS.size -> LADDER_MS.last()
        else -> LADDER_MS[declines - 1]
    }

    /**
     * One more decline: next ladder step from [now], and a floor of [floorMs] when the slot has one.
     * [endsSwitchOff] is set by the user's own "later" on a shown prompt: that ends the switch-off
     * state so the normal ladder applies again. Declines that reconciliation infers from an older
     * pending action keep it, so they never override a switch-off made after that action.
     */
    fun declined(record: PromptRecord, now: Long, floorMs: Long = 0L, endsSwitchOff: Boolean = false): PromptRecord {
        val declines = record.declines + 1
        return record.copy(
            declines = declines,
            snoozeUntil = now + stepMillis(declines),
            // Never shorten a floor a later switch-off already set (a reconciled decline dates from the trip).
            floorUntil = if (floorMs > 0L) maxOf(record.floorUntil, now + floorMs) else record.floorUntil,
            switchedOff = if (endsSwitchOff) false else record.switchedOff,
            showingSince = 0,
            pendingActionAt = 0,
        )
    }

    /**
     * The user switched the background connection off: starts the floor without counting a decline and
     * settles any pending prompt action, so reconciliation cannot later turn that trip into a decline.
     */
    fun switchedOff(record: PromptRecord, now: Long): PromptRecord =
        record.copy(switchedOff = true, floorUntil = now + BACKGROUND_CONNECTION_FLOOR_MS, pendingActionAt = 0, showingSince = 0)

    /** The slot's history outlives a resolved problem; an empty record is dropped instead. */
    fun hasHistory(record: PromptRecord): Boolean =
        record.declines > 0 || record.floorUntil > 0L || record.switchedOff

    /**
     * A snooze longer than the record's own step can only come from a clock that moved back (for
     * example before the trusted clock anchored); restart that step from [now]. The floor is clamped
     * to its own length the same way.
     */
    fun clamp(record: PromptRecord, now: Long): PromptRecord {
        val maxStep = stepMillis(record.declines)
        val stepped = if (record.snoozeUntil - now > maxStep) record.copy(snoozeUntil = now + maxStep) else record
        return if (stepped.floorUntil - now > BACKGROUND_CONNECTION_FLOOR_MS) stepped.copy(floorUntil = now + BACKGROUND_CONNECTION_FLOOR_MS) else stepped
    }

    /** The slot waits on the ladder, and after a switch-off also on the floor; per-trigger floor handling is in `forRecord`. */
    fun snoozedUntil(record: PromptRecord): Long =
        if (record.switchedOff) maxOf(record.snoozeUntil, record.floorUntil) else record.snoozeUntil

    fun isSnoozed(record: PromptRecord, now: Long): Boolean = snoozedUntil(record) > now
}
