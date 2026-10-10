package com.difft.android.p2p.android.client

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.P2pJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/**
 * The one owner of "a non-2xx pairing response becomes a typed reason token".
 *
 * Every pairing request this device makes — `prepare-upload`, `link-precheck`, `link-intent`,
 * `reverse-pair` — is answered by the same peer implementations with the same body shapes, so they
 * must read them identically: a 403 carries the receiver's gate-reject token as plain text, while
 * 401 / 409 / 400 carry a JSON `{"error": "<token>"}`. One owner is what keeps a stale PIN, a busy
 * peer and a malformed request from collapsing into "network error" on one caller but not another.
 */
internal object PrepareRejection {

    /**
     * Maps a non-2xx `[endpoint]` response to a [PrepareUploadRejectedException] carrying the wire
     * reason token. Throws a plain [IllegalStateException] when the status carries no typed reason.
     */
    fun throwRejection(endpoint: String, resp: Response): Nothing {
        val body = runCatching { resp.body.string() }.getOrNull().orEmpty()
        val reason = when (resp.code) {
            HTTP_FORBIDDEN -> body.trim().takeIf { it.isNotBlank() }
            HTTP_UNAUTHORIZED -> errorToken(body) ?: PrepareUploadReasons.PIN_REQUIRED
            HTTP_CONFLICT -> errorToken(body) ?: PrepareUploadReasons.TOO_MANY_SESSIONS
            HTTP_BAD_REQUEST -> errorToken(body) ?: PrepareUploadReasons.INVALID_BODY
            else -> null
        }
        if (reason != null) {
            L.w { "[P2P][Sender] $endpoint rejected code=${resp.code} reason=$reason" }
            throw PrepareUploadRejectedException(reason)
        }
        error("$endpoint failed ${resp.code}")
    }

    /** The `error` token of a JSON `{"error": "..."}` body, or null when the body is not that shape. */
    @VisibleForTesting
    internal fun errorToken(body: String): String? = runCatching {
        P2pJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()?.takeIf { it.isNotBlank() }

    const val HTTP_BAD_REQUEST = 400
    const val HTTP_UNAUTHORIZED = 401
    const val HTTP_FORBIDDEN = 403
    const val HTTP_NOT_FOUND = 404
    const val HTTP_CONFLICT = 409
}
