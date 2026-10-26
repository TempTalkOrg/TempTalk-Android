package com.difft.android.network

import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * WebSocket host marks through the real [UrlManager] and coordinator (#1396): a failure marks the
 * host the socket dialled, a connect lifts the mark, and neither is attributed to a host the socket
 * never used.
 */
@RunWith(RobolectricTestRunner::class)
class UrlManagerWebSocketHostTest {

    private val fixtures = mutableListOf<ChatPoolFixture>()

    @After
    fun tearDown() = fixtures.forEach(ChatPoolFixture::close)

    private fun pool() = ChatPoolFixture(listOf(A, B, C)).also(fixtures::add)

    @Test
    fun `a websocket failure marks the host it dialled and the next connect moves on`() {
        val f = pool()
        assertEquals("wss://$A/chat/v1/websocket/", f.urlManager.getChatWebsocketUrl())

        f.urlManager.switchToNextChatWebsocketHost()

        assertEquals(listOf(B, C), f.eligibleHosts())
        assertEquals("wss://$B/chat/v1/websocket/", f.urlManager.getChatWebsocketUrl())
    }

    // T16 — B was marked (an HTTP failure landed while the socket was connecting), then the socket
    // connected on B: B answers, so it is eligible again.
    @Test
    fun `a websocket connect lifts the mark on the host it connected to`() {
        val f = pool()
        f.urlManager.recordFailedHost(A)
        assertEquals("wss://$B/chat/v1/websocket/", f.urlManager.getChatWebsocketUrl())
        f.urlManager.recordFailedHost(B)
        assertEquals(listOf(C), f.eligibleHosts())

        f.urlManager.recordChatWebsocketConnected()

        assertEquals(listOf(B, C), f.eligibleHosts())
    }

    @Test
    fun `a websocket failure while offline marks nothing`() {
        val f = pool()
        f.urlManager.getChatWebsocketUrl()
        f.online = false

        f.urlManager.switchToNextChatWebsocketHost()

        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    @Test
    fun `a connect before any url was built changes nothing`() {
        val f = pool()

        f.urlManager.recordChatWebsocketConnected()
        f.urlManager.switchToNextChatWebsocketHost()

        assertEquals(listOf(A, B, C), f.eligibleHosts())
    }

    private companion object {
        const val A = "a.test"
        const val B = "b.test"
        const val C = "c.test"
    }
}
