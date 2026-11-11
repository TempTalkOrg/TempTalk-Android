package com.difft.android.app.startup

import com.auth0.android.jwt.JWT
import com.difft.android.base.log.lumberjack.L






object LocalTokenVerifier {

    



    fun isLocalTokenValid(
        baseAuth: String?,
        account: String?,
        token: String?,
        logInconsistency: Boolean = true,
    ): Boolean {
        if (baseAuth.isNullOrEmpty()) return false
        
        
        if (account.isNullOrEmpty() || token.isNullOrEmpty()) {
            if (logInconsistency) L.w {
                "[Startup] basicAuth set but account/token missing " +
                    "account.empty=${account.isNullOrEmpty()} token.empty=${token.isNullOrEmpty()}"
            }
            return false
        }
        return runCatching { JWT(token).getClaim("uid").asString() }.getOrNull() == account
    }
}
