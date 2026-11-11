package com.difft.android.chat.contacts

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CardPreloadTest {
    @Test fun `an entry is taken once and only as its own type`() {
        val preloads = CardPreloads()
        val key = preloads.put("state")
        assertNull(preloads.take<Int>(key))
        val other = preloads.put("state")
        assertEquals("state", preloads.take<String>(other))
        assertNull(preloads.take<String>(other))
        assertNull(preloads.take<String>(null))
    }

    @Test fun `entries no sheet took are dropped oldest first`() {
        val preloads = CardPreloads()
        val keys = (1..5).map { preloads.put(it) }
        assertNull(preloads.take<Int>(keys.first()))
        assertEquals(5, preloads.take<Int>(keys.last()))
    }

    @Test fun `a read that answers in time is used`() = runTest {
        val read = CompletableDeferred<String>()
        val result = async { readWithin(backgroundScope, CARD_LOCAL_READ_TIMEOUT_MS, { read.await() }, StandardTestDispatcher(testScheduler)) }
        runCurrent()
        read.complete("local")
        runCurrent()
        assertEquals("local", result.await())
    }

    @Test fun `a read past the deadline shows the sheet without it`() = runTest {
        val never = CompletableDeferred<String>()
        val result = async { readWithin(backgroundScope, CARD_LOCAL_READ_TIMEOUT_MS, { never.await() }, StandardTestDispatcher(testScheduler)) }
        advanceTimeBy(CARD_LOCAL_READ_TIMEOUT_MS - 1)
        runCurrent()
        assertFalse(result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertNull(result.await())
    }

    @Test fun `a failed read shows the sheet without it`() = runTest {
        val result = async {
            readWithin<String>(backgroundScope, CARD_LOCAL_READ_TIMEOUT_MS, { error("Fixture database failure") }, StandardTestDispatcher(testScheduler))
        }
        runCurrent()
        assertNull(result.await())
    }

    @Test fun `the card's local read never asks the server`() {
        
        val source = File(SOURCE).readText()
        val load = source.substringAfter("suspend fun load(").substringBefore("internal fun ContactorModel.fillGapsOf")
        listOf("getContactWithID", "fetchContactors").forEach {
            assertFalse(it in load, "load() calls $it")
        }
        assertTrue("getContactorFromAllTable" in load)
    }

    private companion object {
        val SOURCE = listOf(
            "src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactDetailRepository.kt",
            "chat/src/main/java/com/difft/android/chat/contacts/contactsdetail/ContactDetailRepository.kt",
        ).first { File(it).exists() }
    }
}
