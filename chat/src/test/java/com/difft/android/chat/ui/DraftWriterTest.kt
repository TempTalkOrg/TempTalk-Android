package com.difft.android.chat.ui

import com.difft.android.messageserialization.db.store.DraftRepository
import difft.android.messageserialization.model.Draft
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger





class DraftWriterTest {

    private val repository: DraftRepository = mockk(relaxed = true)
    private val writer = DraftWriter(repository, DraftWriter.Order())

    @Test
    fun `an older draft arriving after a newer one is dropped`() = runBlocking {
        val old = writer.nextVersion()
        val new = writer.nextVersion()

        writer.upsert("room", Draft(content = "hello"), new)
        writer.upsert("room", Draft(content = "h"), old)

        verify(exactly = 1) { repository.upsertDraft("room", Draft(content = "hello")) }
        verify(exactly = 0) { repository.upsertDraft("room", Draft(content = "h")) }
    }

    @Test
    fun `a clear wins over an older draft and loses to a newer one`() = runBlocking {
        val typed = writer.nextVersion()
        val cleared = writer.nextVersion()
        val retyped = writer.nextVersion()

        writer.clear("room", cleared)
        writer.upsert("room", Draft(content = "stale"), typed)
        writer.upsert("room", Draft(content = "new"), retyped)

        verify(exactly = 0) { repository.upsertDraft("room", Draft(content = "stale")) }
        verifyOrder {
            repository.clearDraft("room")
            repository.upsertDraft("room", Draft(content = "new"))
        }
    }

    @Test
    fun `rooms are versioned independently`() = runBlocking {
        val a = writer.nextVersion()
        val b = writer.nextVersion()

        writer.upsert("b", Draft(content = "b"), b)
        writer.upsert("a", Draft(content = "a"), a)

        verify(exactly = 1) { repository.upsertDraft("a", Draft(content = "a")) }
        verify(exactly = 1) { repository.upsertDraft("b", Draft(content = "b")) }
    }

    @Test
    fun `concurrent writers never overlap`() = runBlocking {
        val inside = AtomicInteger()
        val maxInside = AtomicInteger()
        every { repository.upsertDraft(any(), any()) } answers {
            maxInside.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            Thread.sleep(20)
            inside.decrementAndGet()
        }

        coroutineScope {
            repeat(8) { i ->
                val version = writer.nextVersion()
                launch(Dispatchers.IO) { writer.upsert("room$i", Draft(content = "d$i"), version) }
            }
        }

        assertEquals(1, maxInside.get())
    }
}
