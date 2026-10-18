package com.difft.android.chat.setting.sync

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingSyncStateTest {

    private val field = SyncField.CONFIDENTIAL_MODE
    private val q = FakeQueue()
    private val state = q.state

    @Test
    fun `intent counts as pending until it expires, then only the queue speaks`() = runTest {
        q.queue()
        state.recordIntent(ROOM, field, ts = 100)
        assertTrue(state.isPending(ROOM, field))

        q.elapsed = SettingSyncState.INTENT_TTL_MS + 1
        assertFalse(state.isPending(ROOM, field))
        assertNull(state.activeIntent(ROOM, field))
    }

    @Test
    fun `clearIntentIf leaves a newer intent untouched`() {
        state.recordIntent(ROOM, field, ts = 100)
        state.recordIntent(ROOM, field, ts = 200)

        state.clearIntentIf(ROOM, field, ts = 100)
        assertEquals(200L, state.activeIntent(ROOM, field)?.ts)

        state.clearIntentIf(ROOM, field, ts = 200)
        assertNull(state.activeIntent(ROOM, field))
    }

    @Test
    fun `finalized jobs are excluded from pending and forgotten once the framework deleted them`() = runTest {
        val a = pendingSpec("a", ts = 100)
        q.queue(a)
        state.markFinalized("a", SettingSyncState.queueKey(ROOM, field))

        assertTrue(state.pendingJobs(ROOM, field).isEmpty())
        assertFalse(state.isPending(ROOM, field))

        // Deleted by the framework, then a new job with the same id can never exist; the entry is pruned.
        q.queue()
        assertTrue(state.pendingJobs(ROOM, field).isEmpty())
        q.queue(a)
        assertEquals(listOf("a"), state.pendingJobs(ROOM, field).map { it.id })
    }

    @Test
    fun `latestIntentTs is the max over memory and queue`() = runTest {
        q.queue(pendingSpec("a", ts = 100), pendingSpec("b", ts = 300))
        assertEquals(300L, state.latestIntentTs(ROOM, field))

        state.recordIntent(ROOM, field, ts = 400)
        assertEquals(400L, state.latestIntentTs(ROOM, field))

        q.queue()
        q.elapsed = SettingSyncState.INTENT_TTL_MS + 1
        assertNull(state.latestIntentTs(ROOM, field))
    }

    @Test
    fun `pendingJobs excludes the asking job`() = runTest {
        q.queue(pendingSpec("a", ts = 100), pendingSpec("b", ts = 200))
        assertEquals(listOf("b"), state.pendingJobs(ROOM, field, excludeJobId = "a").map { it.id })
    }
}
