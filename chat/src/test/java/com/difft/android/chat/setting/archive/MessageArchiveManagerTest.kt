package com.difft.android.chat.setting.archive

import com.difft.android.base.utils.time.ServerTimeProvider
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue







class MessageArchiveManagerTest {

    
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

    




    private fun isArchivedByExpiry(
        readTime: Long,
        expiryMillis: Long,
        now: Long,
        systemShowTimestamp: Long = readTime,
    ): Boolean =
        readTime > 0 &&
            readTime + expiryMillis < now &&
            systemShowTimestamp + expiryMillis < now

    




    private fun isAnchorCleared(readTime: Long, systemShowTimestamp: Long, anchor: Long): Boolean =
        readTime > 0 && readTime <= anchor && systemShowTimestamp <= anchor

    
    private fun effectiveArchiveReadTime(rawReadTime: Long): Long =
        rawReadTime.takeIf { it > 0 } ?: ServerTimeProvider.nowMillis()

    private val oneDayMillis = 24L * 60 * 60 * 1000
    private val expiryMillis = 7L * oneDayMillis 

    

    @Test
    fun `message at exact expiry boundary is not archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        
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
        val readTime = now - expiryMillis - 1 
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    @Test
    fun `recently read message is not archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - oneDayMillis 
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    

    @Test
    fun `unread earliest message falls back to trusted now and is not instantly re-archived`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        
        val effectiveReadTime = effectiveArchiveReadTime(rawReadTime = 0L)
        val now = ServerTimeProvider.nowMillis()

        
        assertFalse(isArchivedByExpiry(effectiveReadTime, expiryMillis, now))
    }

    

    @Test
    fun `wall-clock rollback with fixed anchor keeps the archive decision`() {
        reset()
        fakeWall = 1_700_000_000_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - expiryMillis - 1 
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))

        
        fakeWall -= 2 * oneDayMillis
        val nowAfter = ServerTimeProvider.nowMillis()

        
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, nowAfter))
        
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, fakeWall))
    }

    @Test
    fun `wall-clock jump forward with fixed anchor does not prematurely archive`() {
        reset()
        fakeWall = 1_700_000_000_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val readTime = now - oneDayMillis 
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now))

        
        fakeWall += 30 * oneDayMillis
        val nowAfter = ServerTimeProvider.nowMillis()

        
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, nowAfter))
        
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, fakeWall))
    }

    

    @Test
    fun `cold-start fallback decision uses clamped persisted offset`() {
        
        reset(persistedOffset = oneDayMillis, lastKnownServerTime = 1_000_000_000L)
        fakeWall = 900_000_000L 

        val now = ServerTimeProvider.nowMillis()
        assertTrue(now >= 1_000_000_000L) 

        val readTime = now - expiryMillis - 1
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now))
    }

    

    
    
    @Test
    fun `bogus-early readTime does not archive before arrival plus expiry`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val systemShowTimestamp = now - oneDayMillis      
        val readTime = now - 100 * expiryMillis           

        
        assertFalse(isArchivedByExpiry(readTime, expiryMillis, now, systemShowTimestamp))

        
        val nowAfter = systemShowTimestamp + expiryMillis + 1
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, nowAfter, systemShowTimestamp))
    }

    
    @Test
    fun `normal readTime at or after arrival sees a no-op floor`() {
        reset()
        fakeWall = 100_000L
        fakeElapsed = 5_000L
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        val now = ServerTimeProvider.nowMillis()
        val systemShowTimestamp = now - expiryMillis - oneDayMillis 
        val readTime = now - expiryMillis - 1                       

        
        assertTrue(isArchivedByExpiry(readTime, expiryMillis, now, systemShowTimestamp))

        
        val freshReadTime = now - oneDayMillis
        val freshArrival = freshReadTime - 1
        assertFalse(isArchivedByExpiry(freshReadTime, expiryMillis, now, freshArrival))
    }

    
    
    @Test
    fun `message arrived after anchor is not anchor-cleared despite bogus readTime`() {
        reset()

        val anchor = 1_700_000_000_000L
        val systemShowTimestamp = anchor + oneDayMillis 
        val readTime = anchor - oneDayMillis            

        
        assertFalse(isAnchorCleared(readTime, systemShowTimestamp, anchor))

        
        assertTrue(isAnchorCleared(anchor - oneDayMillis, anchor - oneDayMillis, anchor))
    }
}
