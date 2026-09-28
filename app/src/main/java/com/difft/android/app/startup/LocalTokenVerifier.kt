package com.difft.android.app.startup

import com.auth0.android.jwt.JWT
import com.difft.android.base.log.lumberjack.L

/**
 * Cold-start identity gate (issue #758): a stored Basic credential is only trusted when the stored
 * JWT parses and its `uid` claim names the same account. An unparsable token fails the gate rather
 * than throwing out of the startup path. Pure function, called from `MainActivity` off the main thread.
 */
object LocalTokenVerifier {

    fun isLocalTokenValid(baseAuth: String?, account: String?, token: String?): Boolean {
        if (baseAuth.isNullOrEmpty()) return false
        // Inconsistent state: basicAuth present but account/token missing (partial logout,
        // migration edge). Treat as unauthenticated — this is a security gate.
        if (account.isNullOrEmpty() || token.isNullOrEmpty()) {
            L.w {
                "[Startup] basicAuth set but account/token missing " +
                    "account.empty=${account.isNullOrEmpty()} token.empty=${token.isNullOrEmpty()}"
            }
            return false
        }
        return runCatching { JWT(token).getClaim("uid").asString() }.getOrNull() == account
    }
}
