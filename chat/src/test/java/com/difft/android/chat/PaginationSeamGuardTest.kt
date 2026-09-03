package com.difft.android.chat

import com.difft.android.base.utils.RoomChange
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.pagination.roomChangeSignals
import com.difft.android.chat.pagination.testing.FakeChatMessageWindowSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases #9, #11 and #17 — the properties the whole pagination test net rests on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaginationSeamGuardTest {

    // #9 — the change signal must be COLD. Every `observerMessagesChanges()` restart re-collects
    // the same Flow instance, and the restarted collection must get its next signal on the leading
    // edge instead of inheriting the previous collection's sampling window (which would swallow it
    // for up to the sampling period, i.e. exactly the case a restart exists to serve).
    @Test
    fun `messageChanges is cold so a re-collection gets its own leading-edge signal`() = runTest {
        val upstream = MutableSharedFlow<List<RoomChange>>(extraBufferCapacity = 64)
        val signals = roomChangeSignals(upstream, ROOM_ID)
        val messageChange = listOf(RoomChange(ROOM_ID, RoomChangeType.MESSAGE))

        val firstRun = mutableListOf<Unit>()
        val firstJob = signals.onEach { firstRun += it }.launchIn(backgroundScope)
        upstream.subscriptionCount.first { it > 0 }
        upstream.emit(messageChange)
        runCurrent()
        assertEquals(1, firstRun.size)
        firstJob.cancelAndJoin()

        val secondRun = mutableListOf<Unit>()
        signals.onEach { secondRun += it }.launchIn(backgroundScope)
        upstream.subscriptionCount.first { it > 0 }
        upstream.emit(messageChange)
        runCurrent()

        assertEquals(1, secondRun.size)
    }

    // #9 (negative half) — a change in another room, or a non-MESSAGE change in this one, must not
    // wake the window observer.
    @Test
    fun `messageChanges ignores other rooms and non-message change types`() = runTest {
        val upstream = MutableSharedFlow<List<RoomChange>>(extraBufferCapacity = 64)
        val received = mutableListOf<Unit>()
        roomChangeSignals(upstream, ROOM_ID).onEach { received += it }.launchIn(backgroundScope)
        upstream.subscriptionCount.first { it > 0 }

        upstream.emit(listOf(RoomChange("other-room", RoomChangeType.MESSAGE)))
        upstream.emit(listOf(RoomChange(ROOM_ID, RoomChangeType.REFRESH)))
        runCurrent()

        assertEquals(0, received.size)
    }

    // The machine guard for the seam decision. Re-introducing any `com.tencent.wcdb.*` type
    // into the controller's or the base class's type surface reloads the native library from the
    // constructor and puts every case in this package back to @Ignore.
    @Test
    fun `the controller type surface holds no WCDB types`() {
        listOf(
            ChatNormalPaginationController::class.java,
            BaseChatPaginationController::class.java,
        ).forEach { type ->
            val offenders = buildList {
                type.declaredFields.forEach { add(it.type.name to "field ${it.name}") }
                type.declaredMethods.forEach { method ->
                    add(method.returnType.name to "return of ${method.name}")
                    method.parameterTypes.forEach { add(it.name to "parameter of ${method.name}") }
                }
                type.declaredConstructors.forEach { constructor ->
                    constructor.parameterTypes.forEach { add(it.name to "constructor parameter") }
                }
            }.filter { (name, _) -> name.startsWith(WCDB_PACKAGE_PREFIX) }

            assertTrue("${type.simpleName} exposes WCDB types: $offenders", offenders.isEmpty())
        }
    }

    // #17 — the fake IS the ground truth for every controller case below it, so its four boundary
    // operators are verified against the interface KDoc directly. One wrong operator here turns
    // the whole suite green for the wrong reason.
    @Test
    fun `fake window source honours all four timestamp boundaries`() {
        val source = FakeChatMessageWindowSource()
        source.seed(buildMessageSequence(count = 10, startTs = 1_000L, stepMs = 1_000L))

        assertEquals(listOf("m3", "m2", "m1"), source.olderThan(5_000L, 3).map { it.id })
        assertEquals(listOf("m4", "m3", "m2"), source.atOrOlderThan(5_000L, 3).map { it.id })
        assertEquals(listOf("m5", "m6", "m7"), source.newerThan(5_000L, 3).map { it.id })
        assertEquals(listOf("m4", "m5", "m6"), source.atOrNewerThan(5_000L, 3).map { it.id })
        assertEquals(4, source.countOlderThan(5_000L))
        assertEquals(5, source.countNewerThan(5_000L))
    }

    private companion object {
        const val ROOM_ID = "room"
        const val WCDB_PACKAGE_PREFIX = "com.tencent.wcdb"
    }
}
