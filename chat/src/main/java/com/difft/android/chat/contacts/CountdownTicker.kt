package com.difft.android.chat.contacts

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class CountdownTicker @Inject constructor() {
    val dayChanges: Flow<Unit> = midnightChanges(System::currentTimeMillis, ZoneId::systemDefault)

    val ticks: Flow<Unit> = flow {
        while (true) {
            delay(TICK_INTERVAL_MS)
            emit(Unit)
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 60_000L
    }
}


internal fun millisUntilNextMidnight(now: Long, zone: ZoneId): Long {
    val instant = Instant.ofEpochMilli(now)
    val next = instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    return (next.toEpochMilli() - now).coerceAtLeast(1)
}

internal fun midnightChanges(clock: () -> Long, zone: () -> ZoneId): Flow<Unit> = flow {
    while (true) {
        delay(millisUntilNextMidnight(clock(), zone()))
        emit(Unit)
    }
}
