package com.difft.android.chat.group

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.difft.app.database.models.GroupModel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupExpiryTrackerTest {
    private val tracker = GroupExpiryTracker()
    private val persist = mockk<(GroupModel) -> Unit>(relaxed = true)
    private val emit = mockk<(GroupModel) -> Unit>(relaxed = true)

    @Test
    fun `clear persists null and emits exactly once while repeated clear only records order`() {
        val group = group("a")
        tracker.clearExpiry("a", "incoming", { group }, persist, emit)
        tracker.clearExpiry("a", "incoming", { group }, persist, emit)
        assertNull(group.expireTime)
        verify(exactly = 1) { persist(match { it.expireTime == null }) }
        verify(exactly = 1) { emit(group) }
    }

    @Test
    fun `clear of null or missing row still rejects a previously started response`() {
        for (stored in listOf(null, group("a", null))) {
            val started = tracker.requestStartedAt()
            tracker.clearExpiry("a", "sent", { stored }, persist, emit)
            val response = group("a")
            tracker.persistResponse(listOf(response), started) {}
            assertNull(response.expireTime)
        }
        verify(exactly = 0) { persist(any()) }
        verify(exactly = 0) { emit(any()) }
    }

    @Test
    fun `older list response drops only cleared gid deadline and still persists other fields`() {
        val started = tracker.requestStartedAt()
        tracker.clearExpiry("a", "notify:6", { group("a") }, persist, emit)
        val stale = group("a").apply { name = "updated name"; version = 9 }
        val other = group("b")
        val commit = mockk<() -> Unit>(relaxed = true)
        tracker.persistResponse(listOf(stale, other), started, persist = commit)
        assertNull(stale.expireTime)
        assertEquals("updated name", stale.name)
        assertEquals(9, stale.version)
        assertEquals(DEADLINE, other.expireTime)
        verify(exactly = 1) { commit() }
    }

    @Test
    fun `detail response requested after clear can restore deadline`() {
        tracker.clearExpiry("a", "sent", { group("a") }, persist, emit)
        val started = tracker.requestStartedAt()
        val response = group("a")
        tracker.persistResponse(listOf(response), started) {}
        assertEquals(DEADLINE, response.expireTime)
    }

    @Test
    fun `clear between response construction and commit wins`() {
        val started = tracker.requestStartedAt()
        val response = group("a")
        tracker.clearExpiry("a", "incoming", { group("a") }, persist, emit)
        tracker.persistResponse(listOf(response), started) { assertNull(response.expireTime) }
    }

    @Test
    fun `a delayed clear does not erase a deadline from a request that started after it`() {
        val stored = group("a", null)
        tracker.clearExpiry("a", "incoming", { stored }, persist, emit, hasDeadline = {
            
            tracker.persistResponse(listOf(group("a", LATER)), tracker.requestStartedAt()) { stored.expireTime = LATER }
            true
        })
        assertEquals(LATER, stored.expireTime)
        verify(exactly = 0) { persist(any()) }
        verify(exactly = 0) { emit(any()) }
    }

    @Test
    fun `an older detail response landing after a newer list keeps the newer deadline`() {
        val detailStarted = tracker.requestStartedAt()
        val listStarted = tracker.requestStartedAt()
        
        tracker.persistResponse(listOf(group("a", null)), listStarted) {}
        val detail = group("a")
        tracker.persistResponse(listOf(detail), detailStarted) {}
        assertNull(detail.expireTime)
    }

    @Test
    fun `an older list landing after a newer detail keeps the newer deadline`() {
        val listStarted = tracker.requestStartedAt()
        val detailStarted = tracker.requestStartedAt()
        tracker.persistResponse(listOf(group("a", LATER)), detailStarted) {}
        val listed = group("a")
        tracker.persistResponse(listOf(listed), listStarted) {}
        assertEquals(LATER, listed.expireTime)
    }

    @Test
    fun `an older list after a clear and a newer detail keeps the detail deadline`() {
        val listStarted = tracker.requestStartedAt()
        tracker.clearExpiry("a", "incoming", { group("a", null) }, persist, emit)
        val detailStarted = tracker.requestStartedAt()
        tracker.persistResponse(listOf(group("a", LATER)), detailStarted) {}
        val listed = group("a")
        tracker.persistResponse(listOf(listed), listStarted) {}
        
        assertEquals(LATER, listed.expireTime)
    }

    @Test
    fun `a detail without a deadline keeps the stored one instead of its cached copy`() {
        
        val cached = group("a")
        tracker.persistResponse(listOf(cached), tracker.requestStartedAt(), carriesDeadline = { false }, stored = { null }) {}
        assertNull(cached.expireTime)
        
        val listed = group("a", LATER)
        tracker.persistResponse(listOf(listed), tracker.requestStartedAt()) {}
        assertEquals(LATER, listed.expireTime)
    }

    @Test
    fun `a clear recorded while a response is written is applied by that writer`() {
        val response = group("a")
        val cleared = mutableListOf<String>()
        tracker.persistResponse(listOf(response), tracker.requestStartedAt(), clearWritten = { cleared += it }) {
            
            tracker.clearExpiry("a", "incoming", { null }, persist, emit, hasDeadline = { false })
        }
        assertEquals(listOf("a"), cleared)
        
        assertEquals(DEADLINE, response.expireTime)
        verify(exactly = 0) { persist(any()) }
    }

    @Test
    fun `a clear stamp only moves forward when clears race`() {
        val olderStarted = tracker.requestStartedAt()
        tracker.clearExpiry("a", "incoming", { null }, persist, emit, hasDeadline = { false })
        val started = tracker.requestStartedAt()
        tracker.clearExpiry("a", "sent", { null }, persist, emit, hasDeadline = { false })
        
        val response = group("a")
        tracker.persistResponse(listOf(response), started) {}
        assertNull(response.expireTime)
        val older = group("a")
        tracker.persistResponse(listOf(older), olderStarted) {}
        assertNull(older.expireTime)
    }

    @Test
    fun `a clear for a group without a deadline never waits on a write in progress`() {
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writer = Thread {
            tracker.persistResponse(listOf(group("a")), tracker.requestStartedAt()) {
                writing.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }.apply { start() }
        writing.await(5, TimeUnit.SECONDS)
        val done = CountDownLatch(1)
        Thread {
            tracker.clearExpiry("b", "incoming", { null }, persist, emit, hasDeadline = { false })
            done.countDown()
        }.start()
        val returned = done.await(2, TimeUnit.SECONDS)
        release.countDown()
        writer.join(5_000)
        assertTrue(returned, "the message path waited for the group-table write")
    }

    @Test
    fun `successful absence and nonpositive response deadlines clear previous value`() {
        for (deadline in listOf(null, 0L, -1L)) {
            val response = group("a", deadline)
            tracker.persistResponse(listOf(response), tracker.requestStartedAt()) {}
            assertNull(response.expireTime)
        }
    }

    @Test
    fun `failed persistence does not emit a successful clear`() {
        every { persist(any()) } throws IllegalStateException("storage unavailable")
        val started = tracker.requestStartedAt()
        kotlin.test.assertFailsWith<IllegalStateException> {
            tracker.clearExpiry("a", "sent", { group("a") }, persist, emit)
        }
        verify(exactly = 0) { emit(any()) }
        val response = group("a")
        tracker.persistResponse(listOf(response), started) {}
        assertNull(response.expireTime)
    }

    @Test
    fun `expiry participates in group content equality and hash`() {
        val first = group("a")
        val second = group("a")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        second.expireTime = null
        assertNotEquals(first, second)
        assertNotEquals(first.hashCode(), second.hashCode())
    }

    private fun group(gid: String, deadline: Long? = DEADLINE) = GroupModel().apply {
        this.gid = gid
        expireTime = deadline
    }

    private companion object {
        const val DEADLINE = 1_800_000_000_000L
        const val LATER = 1_900_000_000_000L
    }
}
