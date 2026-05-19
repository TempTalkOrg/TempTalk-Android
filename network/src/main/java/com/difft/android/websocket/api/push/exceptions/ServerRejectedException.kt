package com.difft.android.websocket.api.push.exceptions

/**
 * Indicates the server has rejected the request and we should stop retrying.
 */
class ServerRejectedException : NonSuccessfulResponseCodeException(508)
