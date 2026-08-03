package com.difft.android.network

import com.difft.android.base.utils.time.ServerTimeProvider
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [ServerTimeSyncer.ensureAnchored]: early-return when already anchored, swallow generic
 * failures, propagate [CancellationException], and reach the anchored state on a successful probe.
 */
class ServerTimeSyncerTest {

    private val httpService = mockk<HttpService>()
    private val chatHttpClient = mockk<ChativeHttpClient>().also {
        every { it.httpService } returns httpService
    }
    private val syncer = ServerTimeSyncer(chatHttpClient)

    @After
    fun tearDown() {
        clearAllMocks()
    }

    /** Clear the anchor and inject deterministic clocks so isAnchored() reflects only this test. */
    private fun resetUnanchored() {
        ServerTimeProvider.resetForTest(wallClock = { 100_000L }, elapsedClock = { 5_000L })
    }

    @Test
    fun `already anchored returns immediately without probing`() = runTest {
        resetUnanchored()
        ServerTimeProvider.update(serverNow = 1_700_000_000_000L, source = "test")

        syncer.ensureAnchored()

        coVerify(exactly = 0) { httpService.health() }
    }

    @Test
    fun `generic exception from health is swallowed`() = runTest {
        resetUnanchored()
        coEvery { httpService.health() } throws RuntimeException("network down")

        syncer.ensureAnchored() // must not throw

        assertFalse(ServerTimeProvider.isAnchored())
        coVerify(exactly = 1) { httpService.health() }
    }

    @Test
    fun `CancellationException from health propagates`() = runTest {
        resetUnanchored()
        coEvery { httpService.health() } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            syncer.ensureAnchored()
        }
    }

    @Test
    fun `unanchored plus successful health leaves provider anchored`() = runTest {
        resetUnanchored()
        val serverTs = 1_700_000_000_000L
        // In production the converter hook anchors from the response envelope; ServerTimeSyncer does not
        // parse it. We model that side effect at the mocked HttpService boundary.
        coEvery { httpService.health() } answers {
            val resp = BaseResponse<Any>(ver = 1, status = 0, reason = null, data = null, serverTimestamp = serverTs)
            resp.serverTimestamp?.takeIf { it > 0L }?.let { ServerTimeProvider.update(it, "api") }
            resp
        }

        syncer.ensureAnchored()

        assertTrue(ServerTimeProvider.isAnchored())
        assertEquals(serverTs, ServerTimeProvider.nowMillis())
    }
}
