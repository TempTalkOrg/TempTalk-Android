package com.difft.android.chat.messages

import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.difft.app.database.WCDB
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds





class MigrationRetryQueueDrainTest {

    private val messaging = TestMessagingPause().open()
    private val wcdb = mockk<WCDB>(relaxed = true)
    private val failed = FailedMessageProcessor(mockk(relaxed = true), wcdb, messaging.gate)
    private val pending = PendingMessageProcessor(mockk(relaxed = true), wcdb, messaging.gate)

    @Test
    fun `the owner drains both queues while its export is exporting`() = runBlocking<Unit> {
        messaging.pauseMessaging()

        assertNotNull(failed.drainAsOwner(OWNER))
        assertNotNull(pending.drainAsOwner(OWNER))

        verify(atLeast = 1) { wcdb.failedMessage }
        verify(atLeast = 1) { wcdb.pendingMessageNew }
    }

    @Test
    fun `no drain for another owner or after the commit marker`() = runBlocking<Unit> {
        messaging.pauseMessaging()
        assertNull(failed.drainAsOwner("someone-else"))
        assertNull(pending.drainAsOwner("someone-else"))

        messaging.pause.committing(OWNER, "session")
        assertNull(failed.drainAsOwner(OWNER))
        assertNull(pending.drainAsOwner(OWNER))

        messaging.pause.finish(OWNER, null, MigrationPauseOutcome.FAILED) 
        assertNull(failed.drainAsOwner(OWNER))

        verify(exactly = 0) { wcdb.failedMessage }
        verify(exactly = 0) { wcdb.pendingMessageNew }
    }

    @Test
    fun `the owner's drain ticket is released, so quiescence is not held up`() = runBlocking<Unit> {
        messaging.pauseMessaging()

        failed.drainAsOwner(OWNER)
        pending.drainAsOwner(OWNER)

        
        assertTrue(messaging.gate.closeAndAwaitIdle(OWNER, 1.seconds))
    }

    @Test
    fun `a failing queue is logged and the other still drains`() = runBlocking<Unit> {
        val failing = mockk<PendingMessageProcessor>()
        coEvery { failing.drainAsOwner(OWNER) } throws IllegalStateException("db")
        val other = mockk<FailedMessageProcessor>()
        coEvery { other.drainAsOwner(OWNER) } returns 0

        MigrationRetryQueueDrain(failing, other).drainAsOwner(OWNER)

        coVerify(exactly = 1) { other.drainAsOwner(OWNER) }
    }
}
