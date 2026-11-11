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




@OptIn(ExperimentalCoroutinesApi::class)
class PaginationSeamGuardTest {

    
    
    
    
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
