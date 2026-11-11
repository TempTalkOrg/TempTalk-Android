package com.difft.android.p2p.android.client

import androidx.annotation.VisibleForTesting
import com.difft.android.base.log.lumberjack.L
import com.difft.android.p2p.engine.model.P2pJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response










internal object PrepareRejection {

    



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
