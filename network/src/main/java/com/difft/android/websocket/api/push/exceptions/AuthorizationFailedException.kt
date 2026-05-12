package com.difft.android.websocket.api.push.exceptions

class AuthorizationFailedException(code: Int, s: String?) : NonSuccessfulResponseCodeException(code, s)
