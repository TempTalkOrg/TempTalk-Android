package com.difft.android.login

import android.content.Intent

/** Optional correlation for the hosted lock flow; legacy callers keep their result protocol. */
object SecurityResultNonce {
    const val EXTRA_REQUEST_ID = "com.difft.android.security.REQUEST_ID"
    fun resultData(source: Intent): Intent? = source.getStringExtra(EXTRA_REQUEST_ID)?.let { request ->
        Intent().putExtra(EXTRA_REQUEST_ID, request)
    }
}
