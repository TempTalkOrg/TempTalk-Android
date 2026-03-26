package com.difft.android.network.speedtest

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DomainSpeedTesterTest {

    private val mockClient = mockk<OkHttpClient>()
    private lateinit var tester: DomainSpeedTester

    @Before
    fun setUp() {
        tester = DomainSpeedTester(mockClient)
    }

    @After
    fun tearDown() {
        clearMocks(mockClient)
    }

    private fun mockCallReturning(response: Response) {
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
    }

    private fun mockCallThrowing(exception: Exception) {
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } throws exception
    }

    private fun buildResponse(code: Int): Response {
        return Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .request(Request.Builder().url("https://test.host").build())
            .body("available".toResponseBody())
            .build()
    }

    @Test
    fun `testHosts returns available for HTTP 200 response`() = runTest {
        mockCallReturning(buildResponse(200))

        val results = tester.testHosts(listOf("test.host"))

        assertEquals(1, results.size)
        assertTrue(results[0].isAvailable)
        assertEquals("test.host", results[0].host)
        assertTrue(results[0].latencyMs < Long.MAX_VALUE)
    }

    @Test
    fun `testHosts returns available for HTTP 403 response`() = runTest {
        mockCallReturning(buildResponse(403))

        val results = tester.testHosts(listOf("test.host"))

        assertTrue(results[0].isAvailable, "Any HTTP response should mark host as available")
    }

    @Test
    fun `testHosts returns available for HTTP 500 response`() = runTest {
        mockCallReturning(buildResponse(500))

        val results = tester.testHosts(listOf("test.host"))

        assertTrue(results[0].isAvailable, "Any HTTP response should mark host as available")
    }

    @Test
    fun `testHosts returns unavailable on IOException`() = runTest {
        mockCallThrowing(IOException("Connection refused"))

        val results = tester.testHosts(listOf("down.host"))

        assertFalse(results[0].isAvailable)
        assertEquals(Long.MAX_VALUE, results[0].latencyMs)
        assertEquals("down.host", results[0].host)
    }

    @Test
    fun `testHosts returns unavailable on SocketTimeoutException`() = runTest {
        mockCallThrowing(SocketTimeoutException("Read timed out"))

        val results = tester.testHosts(listOf("slow.host"))

        assertFalse(results[0].isAvailable)
        assertEquals(Long.MAX_VALUE, results[0].latencyMs)
    }

    @Test
    fun `testHosts returns results for all hosts concurrently`() = runTest {
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns buildResponse(200)

        val hosts = listOf("host1.test", "host2.test", "host3.test")
        val results = tester.testHosts(hosts)

        assertEquals(3, results.size)
        assertEquals(hosts.toSet(), results.map { it.host }.toSet())
        assertTrue(results.all { it.isAvailable })
    }

    @Test
    fun `testHosts returns empty list for empty input`() = runTest {
        val results = tester.testHosts(emptyList())

        assertTrue(results.isEmpty())
    }

    @Test
    fun `testHosts sorts available hosts before unavailable`() = runTest {
        var callCount = 0
        val mockCall = mockk<Call>()
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } answers {
            callCount++
            if (callCount % 2 == 0) throw IOException("fail")
            buildResponse(200)
        }

        val hosts = listOf("host1.test", "host2.test", "host3.test")
        val results = tester.testHosts(hosts)

        assertEquals(3, results.size)
        // Available hosts should come before unavailable
        val availableIndices = results.mapIndexedNotNull { i, r -> if (r.isAvailable) i else null }
        val unavailableIndices = results.mapIndexedNotNull { i, r -> if (!r.isAvailable) i else null }
        if (availableIndices.isNotEmpty() && unavailableIndices.isNotEmpty()) {
            assertTrue(availableIndices.max() < unavailableIndices.min(), "Available hosts should sort before unavailable")
        }
    }
}
