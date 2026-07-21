package com.difft.android.chat.setting.archive

import com.difft.android.base.utils.time.ServerTimeProvider
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Trusted-time semantics for [MessageArchiveManager]'s expiry decision (design-android §3.1 / §6).
 *
 * Why this seam: the three production swap sites replace `System.currentTimeMillis()` with
 * [ServerTimeProvider.nowMillis]:
 *  - `archiveMessages()` `currentTimeMillis` (feeds `buildMessageClearCondition`'s expiry threshold),
 *  - the archive-marker `systemShowTimestamp` fallback,
 *  - the archive-marker `readTime` fallback (unread earliest message).
 *
 * `archiveMessages()` itself runs against WCDB (`wcdb.*`), whose native library is NOT loadable in a
 * JVM unit test (same constraint that `@Ignore`s `WcdbJobStorageSweepTest`), so it cannot be executed
 * here. These tests instead drive the exact time source now wired into those sites via
 * [ServerTimeProvider.resetForTest] fake clocks, and evaluate the archive expiry rule as the scalar
 * mirror of the WCDB comparison built in `buildMessageClearCondition`:
 *
 *     readTime > 0 && readTime + messageExpiryMillis < currentTimeMillis
 *     // == DBMessageModel.readTime.gt(0).and(readTime.add(messageExpiryMillis).lt(currentTimeMillis))
 *
 * The point proven is behavioral: once anchored, the archive decision follows trusted server time and
 * is immune to local wall-clock tampering — which is the whole reason for the swap.
 */
class MessageArchiveManagerTest {

    // Mutable fake clocks; the injected lambdas read these so tests advance time deterministically.
    private var fakeWall = 0L
    private var fakeElapsed = 0L

    private fun reset(persistedOffset: Long = 0L, lastKnownServerTime: Long = 0L) {
        ServerTimeProvider.resetForTest(
            wallClock = { fakeWall },
            elapsedClock = { fakeElapsed },
            persistedOffset = persistedOffset,
            lastKnownServerTime = lastKnownServerTime,
        )
    }

    /**
     * Scalar mirror of the WCDB expiry predicate in `buildMessageClearCondition`, including the
     * arrival floor: a message is not expired before arrival(systemShowTimestamp) + expiry, so the
     * readTime conjunct is ANDed with the same conjunct on systemShowTimestamp. [systemShowTimestamp]
     * defaults to [readTime] so the pre-existing cases (normal data, readTime >= arrival) see a no-op floor.
     */
    private fun isArchivedByExpiry(
        readTime: Long,
        expiryMillis: Long,
        now: Long,
        systemShowTimestamp: Long = readTime,
    ): Boolean =
        readTime > 0 &&
            readTime + expiryMillis < now &&
            systemShowTimestamp + expiryMillis < now

    /**
     * Scalar mirror of the clearAnchor predicate in `buildMessageClearCondition`, including the
     * arrival floor: a message that arrived after the anchor must not be anchor-cleared even if a
     * bogus-early readTime says otherwise.
     */
    private fun isAnchorCleared(readTime: Long, systemShowTimestamp: Long, anchor: Long): Boolean =
        readTime > 0 && readTime <= anchor && systemShowTimestamp <= anchor

    /** Effective readTime for a new archive marker: raw readTime, or trusted-now fallback (site :205). */
    private fun effectiveArchiveReadTime(rawReadTime: Long): Long =
        rawReadTime.takeIf { it > 0 } ?: ServerTimeProvider.nowMillis()

    private val oneDayMillis = 24L * 60 * 60 * 1000
    private val expiryMillis = 7L * oneDayMillis // 7-day message expiry

    // ---- Expiry boundary (uses trusted nowMillis as the decision input) ----

    @Test
    fun `message at exact expiry boundary is not archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        // readTime chosen so readTime + expiry == now exactly; `< now` is false → keep.
        val readTime = now - expiryMillis
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    @Test
    fun `message one ms past expiry is archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - expiryMillis - 1 // one ms past expiry → archive.
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    @Test
    fun `recently read message is not archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - oneDayMillis // read 1 day ago, well within the 7-day window.
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    // ---- Fallback path (site :205): unread earliest message uses trusted-now, not instant re-expiry ----

    @Test
    fun `unread earliest message falls back to trusted now and is not instantly re-archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        // rawReadTime == 0 (unread) → the archive marker inherits nowMillis() as its readTime.
        val effectiveReadTime = effectiveArchiveReadTime(rawReadTime = 0L)
        val now = ServerTimeProvider.nowMillis()

        // A freshly created marker (readTime == now) with a positive expiry is well within its window.
        assertFalse(isArchivedByExpiry(effectiveReadTime, expiryMillis, now))
    }

    // ---- Anti-tamper: rollback / forward wall-clock changes do not move an anchored decision ----

    @Test
    fun `wall-clock rollback with fixed anchor keeps the archive decision`() {
        reset()
        fakeWall = 1_700_000_000_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - expiryMillis - 1 // expired under trusted time.
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))

        // User rolls the device clock back 2 days; anchor (server + monotonic) is unaffected.
        fakeWall -= 2 * oneDayMillis
        val nowAfter = ServerTimeProvider.nowMillis()

        // Trusted decision is unchanged — still archived.
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, nowAfter))
        // And it genuinely differs from the pre-swap wall-clock behavior: raw wall time would now
        // treat the message as not-yet-expired, so the swap changes the outcome.
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, fakeWall))
    }

    @Test
    fun `wall-clock jump forward with fixed anchor does not prematurely archive`() {
        reset()
        fakeWall = 1_700_000_000_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - oneDayMillis // 1 day old, not yet expired (7-day window).
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now))

        // User jumps the device clock forward 30 days; anchor is unaffected.
        fakeWall += 30 * oneDayMillis
        val nowAfter = ServerTimeProvider.nowMillis()

        // Trusted decision unchanged — not archived (no premature deletion).
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, nowAfter))
        // Pre-swap wall-clock behavior would have archived it early.
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, fakeWall))
    }

    // ---- Cold-start fallback (no anchor): L2 clamp still yields a usable decision input ----

    @Test
    fun `cold-start fallback decision uses clamped persisted offset`() {
        // No anchor; persisted offset advances wall time, lastKnown clamps rollback.
        reset(persistedOffset = oneDayMillis, lastKnownServerTime = 1_000_000_000L)
        fakeWall = 900_000_000L // wall + offset = 900_000_000 + 1 day; below lastKnown → clamped up.

        val now = ServerTimeProvider.nowMillis()
        assertTrue(now >= 1_000_000_000L) // clamped to lastKnownServerTime.

        val readTime = now - expiryMillis - 1
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    // ---- Arrival floor: peer-supplied readTime cannot delete a message before its own arrival ----

    // (a) Bogus-early readTime (far earlier than arrival) must NOT delete the message until
    // arrival(systemShowTimestamp) + expiry — premature deletion is prevented.
    @Test
    fun `bogus-early readTime does not archive before arrival plus expiry`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val systemShowTimestamp = now - oneDayMillis      // arrived 1 day ago (well within 7-day window)
        val readTime = now - 100 * expiryMillis           // bogus peer clock, absurdly early

        // Without the floor, readTime + expiry < now → would archive. With the floor it must not.
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now, systemShowTimestamp))

        // Once arrival + expiry has actually passed, it is archived.
        val nowAfter = systemShowTimestamp + expiryMillis + 1
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, nowAfter, systemShowTimestamp))
    }

    // (b) Normal data (readTime >= arrival): the floor is a no-op, behaving exactly as before.
    @Test
    fun `normal readTime at or after arrival sees a no-op floor`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val systemShowTimestamp = now - expiryMillis - oneDayMillis // arrived before it was read
        val readTime = now - expiryMillis - 1                       // read just past expiry

        // Expired case: floor implied by readTime conjunct → still archived.
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now, systemShowTimestamp))

        // Not-yet-expired case: recently read normal message stays.
        val freshReadTime = now - oneDayMillis
        val freshArrival = freshReadTime - 1
        assertFalse(isArchivedByExpiry(freshReadTime, expiryMillis, now, freshArrival))
    }

    // (c) Anchor branch: message arrived AFTER the anchor (sst > anchor) but a bogus readTime <= anchor
    // must NOT be anchor-cleared.
    @Test
    fun `message arrived after anchor is not anchor-cleared despite bogus readTime`() {
        reset()

        val anchor = 1_700_000_000_000L
        val systemShowTimestamp = anchor + oneDayMillis // arrived after the anchor
        val readTime = anchor - oneDayMillis            // bogus peer clock, before the anchor

        // Bogus readTime alone would satisfy readTime <= anchor, but arrival is after the anchor.
        assertFalse(isAnchorCleared(readTime, systemShowTimestamp, anchor))

        // Normal data (both readTime and arrival at or before the anchor) is still anchor-cleared.
        assertTrue(isAnchorCleared(anchor - oneDayMillis, anchor - oneDayMillis, anchor))
    }
}
