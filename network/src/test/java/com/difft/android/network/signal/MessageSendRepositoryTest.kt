package com.difft.android.network.signal

import com.difft.android.network.ChativeHttpClient
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.push.exceptions.NonSuccessfulResponseCodeException
import com.difft.android.websocket.api.push.exceptions.PushNetworkException
import com.difft.android.websocket.api.push.exceptions.RateLimitException
import com.difft.android.websocket.api.push.exceptions.ServerRejectedException
import com.difft.android.websocket.api.push.exceptions.UnregisteredUserException
import com.difft.android.websocket.internal.push.NewOutgoingPushMessage
import com.difft.android.websocket.internal.push.NewSendMessageResponse
import com.difft.android.websocket.internal.push.exceptions.AccountOfflineException
import com.difft.android.websocket.internal.push.exceptions.MismatchedDevicesException
import com.difft.android.websocket.internal.push.exceptions.StaleDevicesException
import difft.android.messageserialization.For
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class MessageSendRepositoryTest {

    private val messageApiService: MessageApiService = mockk()
    private val httpClient: ChativeHttpClient = mockk {
        every { getService(MessageApiService::class.java) } returns messageApiService
    }
    private lateinit var repository: MessageSendRepository

    private val testMessage: NewOutgoingPushMessage = mockk(relaxed = true)
    private val userRecipient = For.Account("user-123")
    private val groupRecipient = For.Group("group-456")

    @Before
    fun setUp() {
        repository = MessageSendRepository(httpClient)
    }

    @After
    fun tearDown() {
        clearMocks(messageApiService, httpClient)
    }

    // --- Helper to build error responses with custom headers ---

    private fun errorResponseWithHeaders(
        code: Int,
        body: String,
        headers: Headers = Headers.headersOf()
    ): Response<okhttp3.ResponseBody> {
        val responseBody = body.toResponseBody("application/json".toMediaType())
        val rawResponse = okhttp3.Response.Builder()
            .code(code)
            .message("Error")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://test.example.com/v4/messages/test").build())
            .headers(headers)
            .body(responseBody)
            .build()
        return Response.error(responseBody, rawResponse)
    }

    // --- 200 success with valid body ---

    @Test
    fun `200 with valid body returns NewSendMessageResponse`() = runTest {
        val responseBody = """{"ver":1,"status":200,"reason":"ok","data":{"needsSync":false,"sequenceId":42}}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.success(
            responseBody.toResponseBody("application/json".toMediaType())
        )

        val result = repository.sendMessage(testMessage, userRecipient)
        assertNotNull(result)
        assertIs<NewSendMessageResponse>(result)
    }

    // --- 204 No Content returns default NewSendMessageResponse ---

    @Test
    fun `204 returns default NewSendMessageResponse without parsing body`() = runTest {
        val rawResponse = okhttp3.Response.Builder()
            .code(204)
            .message("No Content")
            .protocol(Protocol.HTTP_1_1)
            .request(Request.Builder().url("https://test.example.com/v4/messages/test").build())
            .build()
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.success(null, rawResponse)

        val result = repository.sendMessage(testMessage, userRecipient)
        assertNotNull(result)
        assertIs<NewSendMessageResponse>(result)
    }

    // --- 200 with null body -> MalformedResponseException ---

    @Test
    fun `200 with null body throws MalformedResponseException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.success(null)

        assertFailsWith<MalformedResponseException> {
            repository.sendMessage(testMessage, userRecipient)
        }
    }

    // --- 401 -> AuthorizationFailedException ---

    @Test
    fun `401 throws AuthorizationFailedException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            401, "Unauthorized".toResponseBody("text/plain".toMediaType())
        )

        val exception = assertFailsWith<AuthorizationFailedException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(401, exception.code)
    }

    // --- 403 -> AuthorizationFailedException ---

    @Test
    fun `403 throws AuthorizationFailedException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            403, "Forbidden".toResponseBody("text/plain".toMediaType())
        )

        val exception = assertFailsWith<AuthorizationFailedException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(403, exception.code)
    }

    // --- 404 with status 10105 -> AccountOfflineException ---

    @Test
    fun `404 with status 10105 throws AccountOfflineException`() = runTest {
        val body = """{"ver":1,"status":10105,"reason":"offline","data":null}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            404, body.toResponseBody("application/json".toMediaType())
        )

        val exception = assertFailsWith<AccountOfflineException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(10105, exception.status)
    }

    // --- 404 without special status -> UnregisteredUserException ---

    @Test
    fun `404 without special status throws UnregisteredUserException`() = runTest {
        val body = """{"ver":1,"status":200,"reason":"ok"}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            404, body.toResponseBody("application/json".toMediaType())
        )

        val exception = assertFailsWith<UnregisteredUserException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals("user-123", exception.e164Number)
    }

    // --- 409 with MismatchedDevices body -> MismatchedDevicesException ---

    @Test
    fun `409 with valid MismatchedDevices body throws MismatchedDevicesException`() = runTest {
        val body = """{"missingDevices":[1,2],"extraDevices":[3]}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            409, body.toResponseBody("application/json".toMediaType())
        )

        val exception = assertFailsWith<MismatchedDevicesException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(listOf(1, 2), exception.mismatchedDevices.missingDevices)
        assertEquals(listOf(3), exception.mismatchedDevices.extraDevices)
    }

    // --- 410 with StaleDevices body -> StaleDevicesException ---

    @Test
    fun `410 with valid StaleDevices body throws StaleDevicesException`() = runTest {
        val body = """{"staleDevices":[1,2]}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            410, body.toResponseBody("application/json".toMediaType())
        )

        val exception = assertFailsWith<StaleDevicesException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(listOf(1, 2), exception.staleDevices.staleDevices)
    }

    // --- 413 with Retry-After header -> RateLimitException ---

    @Test
    fun `413 with Retry-After header throws RateLimitException with retryAfter`() = runTest {
        val headers = Headers.headersOf("Retry-After", "60")
        coEvery { messageApiService.sendMessage(any(), any()) } returns
            errorResponseWithHeaders(413, "", headers)

        val exception = assertFailsWith<RateLimitException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(Optional.of(60_000L), exception.retryAfterMilliseconds)
    }

    // --- 429 -> RateLimitException ---

    @Test
    fun `429 throws RateLimitException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns
            errorResponseWithHeaders(429, "")

        val exception = assertFailsWith<RateLimitException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(Optional.empty<Long>(), exception.retryAfterMilliseconds)
    }

    // --- 508 -> ServerRejectedException ---

    @Test
    fun `508 throws ServerRejectedException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            508, "Loop Detected".toResponseBody("text/plain".toMediaType())
        )

        assertFailsWith<ServerRejectedException> {
            repository.sendMessage(testMessage, userRecipient)
        }
    }

    // --- IOException -> PushNetworkException ---

    @Test
    fun `IOException throws PushNetworkException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } throws IOException("Network down")

        val exception = assertFailsWith<PushNetworkException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertIs<IOException>(exception.cause)
    }

    // --- Routing: group -> sendGroupMessage, 1v1 -> sendMessage ---

    @Test
    fun `group recipient routes to sendGroupMessage`() = runTest {
        val responseBody = """{"ver":1,"status":200,"reason":"ok"}"""
        coEvery { messageApiService.sendGroupMessage(any(), any()) } returns Response.success(
            responseBody.toResponseBody("application/json".toMediaType())
        )

        repository.sendMessage(testMessage, groupRecipient)

        coVerify(exactly = 1) { messageApiService.sendGroupMessage("group-456", testMessage) }
        coVerify(exactly = 0) { messageApiService.sendMessage(any(), any()) }
    }

    @Test
    fun `user recipient routes to sendMessage`() = runTest {
        val responseBody = """{"ver":1,"status":200,"reason":"ok"}"""
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.success(
            responseBody.toResponseBody("application/json".toMediaType())
        )

        repository.sendMessage(testMessage, userRecipient)

        coVerify(exactly = 1) { messageApiService.sendMessage("user-123", testMessage) }
        coVerify(exactly = 0) { messageApiService.sendGroupMessage(any(), any()) }
    }

    // --- 200 with malformed body -> MalformedResponseException ---

    @Test
    fun `200 with malformed JSON body throws MalformedResponseException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.success(
            "not valid json {{{".toResponseBody("application/json".toMediaType())
        )

        assertFailsWith<MalformedResponseException> {
            repository.sendMessage(testMessage, userRecipient)
        }
    }

    // --- Unknown error code -> NonSuccessfulResponseCodeException ---

    @Test
    fun `500 throws NonSuccessfulResponseCodeException`() = runTest {
        coEvery { messageApiService.sendMessage(any(), any()) } returns Response.error(
            500, "Internal Server Error".toResponseBody("text/plain".toMediaType())
        )

        val exception = assertFailsWith<NonSuccessfulResponseCodeException> {
            repository.sendMessage(testMessage, userRecipient)
        }
        assertEquals(500, exception.code)
    }
}
