package com.difft.android.chat.group

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull


class GroupSingleFlightTest {
    private val flight = SingleFlight<String, String>()

    @Test fun `a concurrent caller receives the in-flight result instead of null`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        var runs = 0
        coroutineScope {
            val leader = async(Dispatchers.Default) {
                flight.run("group") { runs++; entered.complete(Unit); release.await(); "fresh" }
            }
            withTimeout(5_000) { entered.await() }
            val follower = async(Dispatchers.Default) { flight.run("group", onJoin = { joined.complete(Unit) }) { runs++; "duplicate" } }
            withTimeout(5_000) { joined.await() }
            release.complete(Unit)
            assertEquals("fresh", withTimeout(5_000) { leader.await() })
            assertEquals("fresh", withTimeout(5_000) { follower.await() })
        }
        assertEquals(1, runs)
    }

    @Test fun `a failed or cancelled leader yields null to followers and releases the key`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val leader = async(Dispatchers.Default) { flight.run("group") { entered.complete(Unit); release.await(); "late" } }
            withTimeout(5_000) { entered.await() }
            val joined = CompletableDeferred<Unit>()
            val follower = async(Dispatchers.Default) { flight.run("group", onJoin = { joined.complete(Unit) }) { "duplicate" } }
            withTimeout(5_000) { joined.await() }
            leader.cancel()
            assertNull(withTimeout(5_000) { follower.await() })
            assertFalse(follower.isCancelled)
        }
        assertEquals("next", flight.run("group") { "next" })
        assertNull(flight.run("group") { null })
    }

    @Test fun `a caller marked after an in-flight run waits it out and runs its own fetch`(): Unit = runBlocking {
        
        val release = CompletableDeferred<Unit>()
        val runs = mutableListOf<String>()
        val stale = async { flight.run("group") { runs += "stale"; release.await(); "stale" } }
        yield()
        
        val accepted = flight.mark()
        val refresh = async { flight.run("group", startedAfter = accepted) { runs += "fresh"; "fresh" } }
        yield()
        assertEquals(listOf("stale"), runs)
        release.complete(Unit)
        assertEquals("stale", withTimeout(5_000) { stale.await() })
        assertEquals("fresh", withTimeout(5_000) { refresh.await() })
        assertEquals(listOf("stale", "fresh"), runs)

        
        val before = flight.mark()
        val release2 = CompletableDeferred<Unit>()
        val leader = async { flight.run("group") { runs += "after-mark"; release2.await(); "after-mark" } }
        yield()
        val follower = async { flight.run("group", startedAfter = before) { runs += "duplicate"; "duplicate" } }
        yield()
        release2.complete(Unit)
        assertEquals("after-mark", withTimeout(5_000) { leader.await() })
        assertEquals("after-mark", withTimeout(5_000) { follower.await() })
        assertEquals(listOf("stale", "fresh", "after-mark"), runs)
    }

    @Test fun `different keys run independently`(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val first = async(Dispatchers.Default) { flight.run("a") { entered.complete(Unit); release.await(); "a" } }
            withTimeout(5_000) { entered.await() }
            assertEquals("b", flight.run("b") { "b" })
            release.complete(Unit)
            assertEquals("a", first.await())
        }
    }
}
