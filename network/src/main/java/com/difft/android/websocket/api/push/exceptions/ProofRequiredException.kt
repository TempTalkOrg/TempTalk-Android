package com.difft.android.websocket.api.push.exceptions

import com.difft.android.base.log.lumberjack.L
import com.difft.android.websocket.internal.push.ProofRequiredResponse

/**
 * Thrown when rate-limited by the server and proof of humanity is required to continue messaging.
 */
class ProofRequiredException(
    response: ProofRequiredResponse,
    val retryAfterSeconds: Long
) : NonSuccessfulResponseCodeException(428) {

    val token: String? = response.token
    val options: Set<Option> = parseOptions(response.options)

    override fun toString(): String =
        "ProofRequiredException{token='$token', options=$options, retryAfterSeconds=$retryAfterSeconds}"

    enum class Option {
        RECAPTCHA, PUSH_CHALLENGE
    }

    companion object {
        private fun parseOptions(rawOptions: List<String>?): Set<Option> {
            if (rawOptions == null) return emptySet()
            return rawOptions.mapNotNull { raw ->
                when (raw) {
                    "recaptcha" -> Option.RECAPTCHA
                    "pushChallenge" -> Option.PUSH_CHALLENGE
                    else -> {
                        L.w { "Unrecognized challenge option: $raw" }
                        null
                    }
                }
            }.toSet()
        }
    }
}
