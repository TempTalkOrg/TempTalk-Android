package com.difft.android.chat.setting.sync

import com.difft.android.network.BaseResponse
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.HttpService
import com.difft.android.network.requests.GetConversationSetRequestBody
import com.difft.android.network.responses.ConversationSetResponseBody
import com.difft.android.network.responses.GetConversationSetResponseBody
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull


class ConversationConfigFetcherTest {

    private val httpService = mockk<HttpService>()
    private val httpClient = mockk<ChativeHttpClient>()
    private lateinit var fetcher: ConversationConfigFetcher
    private val sentBatches = mutableListOf<List<String>>()

    @Before
    fun setUp() {
        every { httpClient.httpService } returns httpService
        fetcher = ConversationConfigFetcher(httpClient)
    }

    private fun ids(n: Int) = List(n) { "room$it" }

    private fun echoServer() {
        coEvery { httpService.fetchGetConversationSet(any()) } coAnswers {
            val body = firstArg<GetConversationSetRequestBody>()
            sentBatches += body.conversations
            ok(body.conversations.map { ConversationSetResponseBody(conversation = it) })
        }
    }

    private fun ok(items: List<ConversationSetResponseBody>) =
        BaseResponse(ver = 1, status = 0, reason = null, data = GetConversationSetResponseBody(items))

    @Test
    fun `empty list sends nothing`() = runTest {
        assertEquals(emptyList(), fetcher.fetch(emptyList(), "test"))
        coVerify(exactly = 0) { httpService.fetchGetConversationSet(any()) }
    }

    @Test
    fun `up to 200 ids go out as one request`() = runTest {
        echoServer()
        for (n in listOf(1, 199, 200)) {
            sentBatches.clear()
            val result = fetcher.fetch(ids(n), "test")
            assertEquals(listOf(n), sentBatches.map { it.size }, "n=$n")
            assertEquals(ids(n), result?.map { it.conversation }, "n=$n")
        }
    }

    @Test
    fun `larger lists are chunked sequentially in request order`() = runTest {
        echoServer()
        val cases = mapOf(201 to listOf(200, 1), 213 to listOf(200, 13), 401 to listOf(200, 200, 1), 500 to listOf(200, 200, 100))
        for ((n, expectedSizes) in cases) {
            sentBatches.clear()
            val result = fetcher.fetch(ids(n), "test")
            assertEquals(expectedSizes, sentBatches.map { it.size }, "n=$n")
            assertEquals(ids(n), sentBatches.flatten(), "n=$n")
            assertEquals(ids(n), result?.map { it.conversation }, "n=$n")
        }
    }

    @Test
    fun `a failing later chunk yields null and stops further requests`() = runTest {
        coEvery { httpService.fetchGetConversationSet(any()) } returnsMany listOf(
            ok(ids(200).map { ConversationSetResponseBody(conversation = it) }),
            BaseResponse(ver = 1, status = 10001, reason = "too many", data = null),
            ok(emptyList()),
        )
        assertNull(fetcher.fetch(ids(500), "test"))
        coVerify(exactly = 2) { httpService.fetchGetConversationSet(any()) }
    }

    @Test
    fun `a transport failure on a later chunk propagates and stops further requests`() = runTest {
        coEvery { httpService.fetchGetConversationSet(any()) } returns
            ok(ids(200).map { ConversationSetResponseBody(conversation = it) }) andThenThrows IOException("down")
        assertFailsWith<IOException> { fetcher.fetch(ids(500), "test") }
        coVerify(exactly = 2) { httpService.fetchGetConversationSet(any()) }
    }
}
