package com.difft.android.p2p.android.client

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Runs the call so that cancelling the coroutine cancels the call: `Call.cancel()` closes the socket
 * and the peer sees the hang-up at once. A blocking `execute()` under `withContext` cannot give that
 * — cancellation would land only after the read returns, minutes later for a request the peer holds
 * open, with the peer believing all that time that this device is still waiting. Every request a
 * peer may hold open — the upload's verdict wait, the reverse-pairing confirmation hang — depends on
 * it, which is why it has one owner rather than a copy per client.
 *
 * The caller owns the returned [Response].
 */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
}
