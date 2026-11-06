package com.difft.android.chat.ui

import com.difft.android.messageserialization.db.store.DraftRepository
import difft.android.messageserialization.model.Draft
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A draft typed within the 500 ms debounce must survive the input pausing (a fold/unfold
 * recreation, the page covered or popped): [DraftViewModel.flushNow] writes it at once.
 *
 * Main is a [StandardTestDispatcher] that is never advanced, so the debounced collector cannot
 * write: every write observed here comes from flushNow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DraftViewModelFlushTest {

    private val repository: DraftRepository = mockk(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `flushNow writes the latest pending draft without waiting for the debounce`() {
        val vm = DraftViewModel(repository)
        vm.updateDraft("room", Draft(content = "h"))
        vm.updateDraft("room", Draft(content = "hello"))

        vm.flushNow()

        verify(timeout = WRITE_TIMEOUT_MS, exactly = 1) { repository.upsertDraft("room", Draft(content = "hello")) }
        verify(exactly = 0) { repository.upsertDraft("room", Draft(content = "h")) }
    }

    @Test
    fun `flushNow writes nothing twice and nothing when no draft is pending`() {
        val vm = DraftViewModel(repository)
        vm.flushNow()
        vm.updateDraft("room", Draft(content = "a"))
        vm.flushNow()
        vm.flushNow()

        verify(timeout = WRITE_TIMEOUT_MS, exactly = 1) { repository.upsertDraft(any(), any()) }
        Thread.sleep(SETTLE_MS)
        verify(exactly = 1) { repository.upsertDraft(any(), any()) }
    }

    @Test
    fun `a cleared draft is not written back by a later flush`() {
        val vm = DraftViewModel(repository)
        vm.updateDraft("room", Draft(content = "sent text"))
        vm.clearDraft("room")

        vm.flushNow()

        Thread.sleep(SETTLE_MS)
        verify(exactly = 0) { repository.upsertDraft(any(), any()) }
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000L
        const val SETTLE_MS = 200L
    }
}
