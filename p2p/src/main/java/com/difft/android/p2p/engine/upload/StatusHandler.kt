package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.session.SessionManager

/**
 * Engine handler for `GET /api/localsend/v2/status?sessionId=&token=`: the sender asking how its
 * upload is doing — or how it ended — on a connection of its own, so the answer does not depend on
 * the upload connection still being open or on anything the receiver's import thread has to do.
 *
 * Both parameters are required. The token is the session-level one issued by `prepare-upload`, not a
 * per-file upload token: the query outlives the files, and must be answerable after the session is
 * over. Transport-agnostic; the Android adapter maps the [ProtocolError]s onto the wire.
 */
class StatusHandler(private val sessionManager: SessionManager) {

    fun handle(sessionId: String?, token: String?): StatusResponse {
        if (sessionId.isNullOrEmpty()) throw ProtocolError.BadRequest("sessionId")
        if (token.isNullOrEmpty()) throw ProtocolError.BadRequest("token")
        return sessionManager.status(sessionId, token)
    }
}
