package com.difft.android.p2p.engine.upload

/**
 * Response headers a receiver may add to an upload's 200. A precondition the sender declared in
 * its handshake — something the receiver must have done before the sender may act — is not
 * proven by a bare 200: a receiver that never understood the field answers 200 all the same. So
 * each such precondition has a header the receiver sets only after doing the thing, and the
 * sender acts only when it reads it back.
 */
object UploadReplyHeaders {
    /** `true` once the receiver installed the credential the sender handed over (same-device no-login handoff). */
    const val HANDOFF_INSTALLED = "X-Migration-Handoff-Installed"

    /** Case-insensitive, the way HTTP compares header names. */
    fun handoffInstalled(headers: Map<String, String>): Boolean =
        headers.entries.any { it.key.equals(HANDOFF_INSTALLED, ignoreCase = true) && it.value.equals("true", ignoreCase = true) }
}
