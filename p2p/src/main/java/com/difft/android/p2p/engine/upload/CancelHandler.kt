package com.difft.android.p2p.engine.upload

import com.difft.android.p2p.engine.model.ProtocolError
import com.difft.android.p2p.engine.session.CancelOutcome
import com.difft.android.p2p.engine.session.SessionManager

/**
 * Engine handler for `POST /api/localsend/v2/cancel?sessionId=[&token=]`.
 * The answer says what the cancel did to the data, not merely that the request was heard — see
 * [SessionManager.cancel]. The token is the session-level one issued by `prepare-upload`, required
 * exactly as it is on `/status`: a missing or wrong token is a protocol error, not a lenient no-op.
 */
class CancelHandler(private val sessionManager: SessionManager) {
    fun handle(sessionId: String?, token: String?): CancelOutcome {
        if (sessionId.isNullOrEmpty()) throw ProtocolError.BadRequest("sessionId")
        if (token.isNullOrEmpty()) throw ProtocolError.BadRequest("token")
        return sessionManager.cancel(sessionId, token)
    }
}
