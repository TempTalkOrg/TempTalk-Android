package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.session.SessionManager










class StatusHandler(private val sessionManager: SessionManager) {

    fun handle(sessionId: String?, token: String?): StatusResponse {
        if (sessionId.isNullOrEmpty()) throw ProtocolError.BadRequest("sessionId")
        if (token.isNullOrEmpty()) throw ProtocolError.BadRequest("token")
        return sessionManager.status(sessionId, token)
    }
}
