package com.difft.android.base.session

/**
 * Cross-module bridge so `:network` can report "the server rejected this device's credential"
 * (an HTTP or in-socket 401/403 on a Basic-auth channel) to the single application-scoped
 * component that owns the kick-out decision, without `:network` depending on `:chat`.
 *
 * A report is only a *signal*, never a verdict: the implementation confirms it against a
 * dedicated endpoint before acting, so a transient edge 401 cannot log a user out.
 *
 * Contract:
 *  - Fire-and-forget. Caller MUST NOT block on this returning, and MUST NOT branch on it.
 *  - Callable from any thread, including OkHttp/Retrofit callbacks and inside a `catch` block.
 *  - Idempotent. Safe to call many times in quick succession; the implementation de-duplicates.
 *  - MUST NOT throw across the boundary; implementation catches internally.
 */
interface AuthFailureSink {
    /** @param source one of the [AuthFailureSource] tags, used for logging only. */
    fun onAuthRejected(source: String)
}

/**
 * Tags identifying which Basic-auth channel observed the rejection. Declared here rather than on
 * the implementation so the `:network` reporters can name themselves without depending on `:chat`.
 */
object AuthFailureSource {
    /** The WebSocket handshake itself was rejected (mapped to AUTHENTICATION_FAILED). */
    const val WS_HANDSHAKE = "wsHandshake"

    /** An in-socket request response carried a 401/403. */
    const val WS_RESPONSE = "wsResponse"

    /** A REST call against the device endpoints was rejected. */
    const val DEVICE_API = "deviceApi"

    /** The HTTP message-send fallback was rejected. */
    const val MESSAGE_SEND = "messageSend"
}
