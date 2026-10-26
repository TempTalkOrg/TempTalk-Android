package com.difft.android.base.notification

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #1350: the call test's shown signal and the ordering of its OK and timeout FAIL writes. */
class NotificationTestRepositoryTest {

    private val written = mutableListOf<NotificationTestResult>()
    private lateinit var store: NotificationTestStore
    private lateinit var repository: NotificationTestRepository

    @Before
    fun setUp() {
        store = mockk {
            coEvery { write(any(), any()) } answers { synchronized(written) { written += secondArg<NotificationTestResult>() } }
        }
        repository = NotificationTestRepository(mockk(), store)
        repository.notePostedCall("fp")
    }

    @Test
    fun `the call screen raises the shown signal before anything is written`() {
        repository.markCallShown(nowMs = 2_000)
        assertEquals(2_000, repository.callShown.value)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a call timeout records nothing once the call screen has opened since the post`() = runBlocking {
        repository.markCallShown(nowMs = 2_000)
        assertFalse(repository.recordCallTimeout("fp", postedAt = 1_000))
        coVerify(exactly = 0) { store.write(any(), any()) }
    }

    @Test
    fun `a call timeout records a failure under the posted fingerprint when no screen opened since the post`() = runBlocking {
        repository.markCallShown(nowMs = 500)
        assertTrue(repository.recordCallTimeout("fp", postedAt = 1_000, nowMs = 16_000))
        assertEquals(listOf(NotificationTestResult(NotificationTestOutcome.FAIL, 16_000, "fp")), written)
    }

    @Test
    fun `a call screen opening while the timeout failure is being written still ends on OK`() = runBlocking {
        val failWriting = CompletableDeferred<Unit>()
        val releaseFail = CompletableDeferred<Unit>()
        coEvery { store.write(any(), any()) } coAnswers {
            val result = secondArg<NotificationTestResult>()
            if (result.outcome == NotificationTestOutcome.FAIL) {
                failWriting.complete(Unit)
                releaseFail.await()
            }
            synchronized(written) { written += result }
        }
        val fail = async(Dispatchers.IO) { repository.recordCallTimeout("fp", postedAt = 1_000) }
        failWriting.await()

        repository.markCallShown(nowMs = 2_000)
        val ok = launch(Dispatchers.IO) { repository.recordCallShown(nowMs = 2_000) }
        // Room for an unordered OK write to overtake the held FAIL.
        delay(100)
        releaseFail.complete(Unit)

        assertTrue(fail.await())
        ok.join()
        assertEquals(listOf(NotificationTestOutcome.FAIL, NotificationTestOutcome.OK), written.map { it.outcome })
    }
}
