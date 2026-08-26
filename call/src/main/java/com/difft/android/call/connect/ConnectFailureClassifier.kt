package com.difft.android.call.connect

import com.difft.android.call.exception.CallPreconditionException
import com.difft.android.call.exception.StartCallException
import com.google.protobuf.InvalidProtocolBufferException
import io.livekit.android.room.RoomException
import kotlinx.coroutines.CancellationException

/** What a connect failure says about trying the remaining failover candidates. */
internal enum class ConnectFailureCategory {
    /** This candidate failed; another one may still work. */
    TRANSIENT,

    /** The server answered a verdict about the call itself — identical from every candidate. */
    SERVER_VERDICT,

    /** A local precondition no candidate can satisfy. */
    PRECONDITION,
}

/**
 * Classifies a connect failure by WHERE the answer came from, not by which exception type
 * happened to surface. Only two things make trying the next candidate pointless: the server
 * already answered a verdict about this call, or a local precondition blocks every attempt.
 * Everything else — including exception types this code has never seen — is treated as this
 * candidate's transport failing, because a failure at candidate A predicts nothing about B.
 *
 * The default direction matters more than the membership of any list, because the set of
 * failures arriving here is OPEN: the livekit fork surfaces four `RoomException` subtypes, passes
 * raw platform exceptions through (`SSLHandshakeException`, `SocketTimeoutException`, …), and
 * wraps any non-401 response to the `/rtc/validate` probe it fires after a transport failure in a
 * bare `java.lang.Exception`. An allowlist of the retryable half of an open set can never be
 * complete, and an incomplete one costs candidates that were reachable. Defaulting to TRANSIENT
 * makes an incomplete error vocabulary harmless: unknown means "no information", which argues for
 * trying the next candidate rather than against it. iOS and desktop reach the same default by
 * different routes (iOS a fatal-type list with a transient fallthrough, desktop by branding server
 * answers as `HTTPError` and retrying everything else).
 *
 * Bounding retries is therefore the caller's job, and a candidate count alone is not enough:
 * see [CallConnectionCoordinator.connectToRoomWithFailover]'s wall-clock budget, which is what
 * keeps "a slower failure" from meaning minutes.
 *
 * [CancellationException] must be rethrown by callers BEFORE classification — cancellation
 * is not a failure and would otherwise be reported as one.
 */
internal fun classifyConnectFailure(t: Throwable): ConnectFailureCategory {
    // The whole cause chain is inspected, matching CertValidationFailureDetector next door: a
    // terminal condition wrapped by a transport-layer exception is still terminal, and mistaking
    // one for retryable is exactly what sends every candidate at the same unsatisfiable guard.
    generateSequence(t) { it.cause }.take(MAX_CAUSE_DEPTH).forEach { link ->
        when (link) {
            // Server verdict: delivered in-band via TTCallResponse.base.status != 0 (call ended,
            // no permission, invalid params). Every candidate returns the same answer, and the
            // server's reason is worth showing, so fail now instead of burning the budget.
            is RoomException.StartCallException, is StartCallException ->
                return ConnectFailureCategory.SERVER_VERDICT

            // Local precondition: a fail-closed guard or a released room (see
            // CallPreconditionException), or call params that cannot be parsed. Also any [Error]
            // — an OOM or a missing class is not a property of this candidate, and retrying it
            // wastes the budget on a failure no node can satisfy.
            is CallPreconditionException, is InvalidProtocolBufferException, is Error ->
                return ConnectFailureCategory.PRECONDITION

            else -> Unit
        }
    }
    return ConnectFailureCategory.TRANSIENT
}

/** Bounds the cause walk so a self-referential chain cannot spin. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * The server's in-band verdict code (`TTCallResponse.base.status`) when the SDK carried one, else
 * null. The exception's own message does not include it, so callers log it explicitly.
 *
 * Scope is exactly the in-band verdict channel: only [RoomException.StartCallException] carries a
 * code, so a 401 observed through this helper means the server rejected the app token inside
 * `TTCallResponse`. An HTTP 401 from the SDK's `/rtc/validate` probe is a different channel — it
 * arrives as [RoomException.NoAuthException] with no code, is classified TRANSIENT, and is NOT
 * counted here.
 *
 * Observation only, deliberately not acted on: iOS force-refreshes the app token once and re-enters
 * failover for an in-band 401, while this client has no such handling. Whether that code ever
 * reaches this point is unknown — the pre-call `WsTokenManager.refreshTokenIfNeeded()` check
 * already covers plain expiry, leaving only server-side revocation, key rotation, or a token
 * expiring mid-failover. Log first, then decide whether the retry is worth its complexity.
 */
internal fun serverVerdictStatus(t: Throwable): Int? = (t as? RoomException.StartCallException)?.statusCode
