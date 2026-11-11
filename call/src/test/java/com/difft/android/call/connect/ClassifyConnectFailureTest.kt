package com.difft.android.call.connect

import com.difft.android.call.exception.CallPreconditionException
import com.difft.android.call.exception.ServerConnectionException
import com.difft.android.call.exception.StartCallException
import com.google.protobuf.InvalidProtocolBufferException
import io.livekit.android.room.RoomException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ClassifyConnectFailureTest {

    @Test
    fun `server verdict about the call is terminal`() {
        assertEquals(
            ConnectFailureCategory.SERVER_VERDICT,
            classifyConnectFailure(RoomException.StartCallException("call ended", null, 22001)),
        )
        assertEquals(
            ConnectFailureCategory.SERVER_VERDICT,
            classifyConnectFailure(StartCallException("no permission")),
        )
    }

    @Test
    fun `media unreachable on the first connect tries the next candidate`() {
        assertEquals(
            ConnectFailureCategory.TRANSIENT,
            classifyConnectFailure(
                RoomException.MediaUnreachableException(
                    message = "could not establish subscriber connection",
                    transport = "subscriber",
                    failed = false,
                    elapsedMs = 15_000,
                ),
            ),
        )
    }

    @Test
    fun `unsatisfiable local precondition is terminal`() {
        assertEquals(
            ConnectFailureCategory.PRECONDITION,
            classifyConnectFailure(CallPreconditionException("SPKI pin absent")),
        )
        assertEquals(
            ConnectFailureCategory.PRECONDITION,
            classifyConnectFailure(InvalidProtocolBufferException("malformed start-call params")),
        )
    }

    @Test
    fun `known transport failures are retryable`() {
        listOf(
            SocketTimeoutException(),
            RoomException.ConnectTimeoutException("timeout", null),
            SSLHandshakeException("trust anchor not found"),
            UnknownHostException("no address"),
        ).forEach {
            assertEquals("${it.javaClass.simpleName}", ConnectFailureCategory.TRANSIENT, classifyConnectFailure(it))
        }
    }

    @Test
    fun `a terminal condition wrapped by a transport exception stays terminal`() {
        assertEquals(
            ConnectFailureCategory.PRECONDITION,
            classifyConnectFailure(IOException("wrapped", CallPreconditionException("SPKI pin absent"))),
        )
        assertEquals(
            ConnectFailureCategory.SERVER_VERDICT,
            classifyConnectFailure(
                IOException("wrapped", RoomException.StartCallException("call ended", null, 22001)),
            ),
        )
    }

    @Test
    fun `an Error is terminal because no candidate can satisfy it`() {
        assertEquals(ConnectFailureCategory.PRECONDITION, classifyConnectFailure(OutOfMemoryError()))
        assertEquals(ConnectFailureCategory.PRECONDITION, classifyConnectFailure(NoClassDefFoundError("missing")))
    }

    @Test
    fun `a self-referential cause chain cannot spin`() {
        val looping = IOException("a")
        looping.initCause(looping.let { IOException("b", it) })
        assertEquals(ConnectFailureCategory.TRANSIENT, classifyConnectFailure(looping))
    }

    @Test
    fun `transport failures outside the old allowlist are retryable`() {
        listOf(
            
            RoomException.ConnectException("engine failed", null),
            
            Exception("HTTP 502 bad gateway"),
            ConnectException("connection refused"),
            SocketException("connection reset"),
            EOFException(),
            SSLPeerUnverifiedException("hostname mismatch"),
            IOException("canceled"),
        ).forEach {
            assertEquals("${it.javaClass.simpleName}", ConnectFailureCategory.TRANSIENT, classifyConnectFailure(it))
        }
    }

    @Test
    fun `masked validate 401 is retryable regardless of its message`() {
        
        
        listOf(
            "invalid authorization header. Must start with Bearer ",
            "invalid token",
            "",
        ).forEach { body ->
            assertEquals(
                "body=$body",
                ConnectFailureCategory.TRANSIENT,
                classifyConnectFailure(RoomException.NoAuthException(body, null)),
            )
        }
    }

    @Test
    fun `server verdict status is surfaced for logging when the SDK carried one`() {
        assertEquals(401, serverVerdictStatus(RoomException.StartCallException("token rejected", null, 401)))
        assertEquals(22001, serverVerdictStatus(RoomException.StartCallException("call ended", null, 22001)))
        
        assertEquals(null, serverVerdictStatus(StartCallException("no permission")))
        assertEquals(null, serverVerdictStatus(SSLHandshakeException("trust anchor not found")))
    }

    @Test
    fun `app-side connection exception is retryable`() {
        
        
        assertEquals(
            ConnectFailureCategory.TRANSIENT,
            classifyConnectFailure(ServerConnectionException("connect failed")),
        )
    }
}
