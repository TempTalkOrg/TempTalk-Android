package com.difft.android.p2p.engine.model






sealed class ProtocolError(val httpStatus: Int, message: String) : Exception(message) {

    
    data object NoContent : ProtocolError(204, "no content")

    
    data class BadRequest(val detail: String) : ProtocolError(400, "bad request: $detail")

    
    data object Unauthorized : ProtocolError(401, "pin required or wrong")

    
    data class Forbidden(val detail: String) : ProtocolError(403, "forbidden: $detail")

    
    data object NotFound : ProtocolError(404, "not found")

    
    data object Conflict : ProtocolError(409, "session already active")

    
    data object TooManyRequests : ProtocolError(429, "too many requests")

    
    data class Internal(val detail: String) : ProtocolError(500, "internal: $detail")

    





    fun detailBody(): String = when (this) {
        is Forbidden -> detail
        else -> ""
    }
}
