package com.difft.android.login.viewmodel

import com.difft.android.network.NetworkException

/**
 * A sign-in request that got no answer from the server (connection, timeout, TLS). Nothing was
 * consumed server-side, so the same held sign-in may simply be retried; every other failure is a
 * server verdict and a retry cannot change it.
 */
class SignInTransportFailure(message: String) : NetworkException(message = message)
