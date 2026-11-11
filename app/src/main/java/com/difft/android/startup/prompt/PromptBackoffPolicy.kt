package com.difft.android.startup.prompt

import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds





object PromptBackoffPolicy {

    private val LADDER_MS = longArrayOf(1.days.inWholeMilliseconds, 3.days.inWholeMilliseconds, 7.days.inWholeMilliseconds, 30.days.inWholeMilliseconds)

    
    val OWN_TRIP_WINDOW_MS = 30.minutes.inWholeMilliseconds

    
    val LATE_PROMPT_MS = 5.seconds.inWholeMilliseconds

    
    val UNKNOWN_HOLD_MS = 15.seconds.inWholeMilliseconds

    




    val BACKGROUND_CONNECTION_FLOOR_MS = 30.days.inWholeMilliseconds

    




    val LATE_DELIVERY_FLOOR_MS = 7.days.inWholeMilliseconds

    
    val RESTRICTED_INTERVAL_MS = 30.days.inWholeMilliseconds

    
    const val RESTRICTED_MAX_DECLINES = 2

    
    fun floorStart(record: PromptRecord): Long = record.floorUntil - BACKGROUND_CONNECTION_FLOOR_MS

    
    fun floorFor(id: PromptId): Long = if (id == PromptId.BACKGROUND_CONNECTION) BACKGROUND_CONNECTION_FLOOR_MS else 0L

    
    fun stepMillis(declines: Int): Long = when {
        declines <= 0 -> 0L
        declines > LADDER_MS.size -> LADDER_MS.last()
        else -> LADDER_MS[declines - 1]
    }

    





    fun declined(record: PromptRecord, now: Long, floorMs: Long = 0L, endsSwitchOff: Boolean = false): PromptRecord {
        val declines = record.declines + 1
        return record.copy(
            declines = declines,
            snoozeUntil = now + stepMillis(declines),
            
            floorUntil = if (floorMs > 0L) maxOf(record.floorUntil, now + floorMs) else record.floorUntil,
            switchedOff = if (endsSwitchOff) false else record.switchedOff,
            showingSince = 0,
            pendingActionAt = 0,
        )
    }

    



    fun switchedOff(record: PromptRecord, now: Long): PromptRecord =
        record.copy(switchedOff = true, floorUntil = now + BACKGROUND_CONNECTION_FLOOR_MS, pendingActionAt = 0, showingSince = 0)

    



    fun declined(id: PromptId, record: PromptRecord, now: Long, endsSwitchOff: Boolean = false): PromptRecord =
        if (id == PromptId.BACKGROUND_RESTRICTED) {
            record.copy(declines = record.declines + 1, snoozeUntil = now + RESTRICTED_INTERVAL_MS, showingSince = 0, pendingActionAt = 0)
        } else {
            declined(record, now, floorFor(id), endsSwitchOff)
        }

    



    fun restrictedTrip(record: PromptRecord, now: Long): PromptRecord =
        record.copy(snoozeUntil = now + RESTRICTED_INTERVAL_MS)

    
    fun keepsHistory(id: PromptId): Boolean = id == PromptId.BACKGROUND_CONNECTION || id == PromptId.BACKGROUND_RESTRICTED

    
    fun hasHistory(record: PromptRecord): Boolean =
        record.declines > 0 || record.floorUntil > 0L || record.switchedOff || record.snoozeUntil > 0L

    




    fun clamp(record: PromptRecord, now: Long, id: PromptId? = null): PromptRecord {
        val maxStep = if (id == PromptId.BACKGROUND_RESTRICTED) RESTRICTED_INTERVAL_MS else stepMillis(record.declines)
        val stepped = if (record.snoozeUntil - now > maxStep) record.copy(snoozeUntil = now + maxStep) else record
        return if (stepped.floorUntil - now > BACKGROUND_CONNECTION_FLOOR_MS) stepped.copy(floorUntil = now + BACKGROUND_CONNECTION_FLOOR_MS) else stepped
    }

    
    fun snoozedUntil(record: PromptRecord): Long =
        if (record.switchedOff) maxOf(record.snoozeUntil, record.floorUntil) else record.snoozeUntil

    fun isSnoozed(record: PromptRecord, now: Long): Boolean = snoozedUntil(record) > now

    
    fun isSnoozed(id: PromptId, record: PromptRecord, now: Long): Boolean =
        (id == PromptId.BACKGROUND_RESTRICTED && record.declines >= RESTRICTED_MAX_DECLINES) || isSnoozed(record, now)
}
