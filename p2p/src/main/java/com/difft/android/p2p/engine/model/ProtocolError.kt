package com.difft.android.p2p.engine.model

/**
 * Protocol error → HTTP status mapping. The engine handlers return these as a typed failure; the
 * Android server adapter maps [httpStatus] onto the wire. Pure Kotlin — no Android/HTTP-library
 * types leak into the engine.
 */
sealed class ProtocolError(val httpStatus: Int, message: String) : Exception(message) {

    /** 204 — request valid but nothing to do (e.g. empty files map). */
    data object NoContent : ProtocolError(204, "no content")

    /** 400 — malformed body / missing required query param. */
    data class BadRequest(val detail: String) : ProtocolError(400, "bad request: $detail")

    /** 401 — PIN required or wrong. */
    data object Unauthorized : ProtocolError(401, "pin required or wrong")

    /** 403 — token/IP mismatch, or receiver declined. */
    data class Forbidden(val detail: String) : ProtocolError(403, "forbidden: $detail")

    /** 404 — the named session is not live and no terminal record of it is kept. */
    data object NotFound : ProtocolError(404, "not found")

    /** 409 — another session is already active. */
    data object Conflict : ProtocolError(409, "session already active")

    /** 429 — rate limited (reserved; PIN brute-force throttle). */
    data object TooManyRequests : ProtocolError(429, "too many requests")

    /** 500 — internal failure (I/O, integrity mismatch surfaced as server error). */
    data class Internal(val detail: String) : ProtocolError(500, "internal: $detail")

    /**
     * The short, safe-to-surface reason the receiver writes as the error response body so the sender
     * can show a specific message; the migration gate's typed env/account/version reject rides here.
     * Only [Forbidden] exposes its [Forbidden.detail]; every other error returns an empty body, so no
     * internal detail leaks to the peer.
     */
    fun detailBody(): String = when (this) {
        is Forbidden -> detail
        else -> ""
    }
}
