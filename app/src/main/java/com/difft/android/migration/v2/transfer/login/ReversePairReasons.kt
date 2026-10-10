package com.difft.android.migration.v2.transfer.login

import com.difft.android.migration.v2.transfer.failureReason
import com.difft.android.p2p.android.client.PrepareUploadRejectedException

/**
 * How a failed `/reverse-pair` becomes the reason token the failure page classifies.
 *
 * Everything the peer itself named — an unsupported protocol, its user cancelling, a confirmation
 * that timed out, a busy peer — already arrives as a [PrepareUploadRejectedException] carrying the
 * wire token (see `ReversePairClient`), so it passes straight through. The tokens themselves live in
 * `PrepareUploadReasons`, beside the rest of the pairing vocabulary.
 */
internal object ReversePairReasons {

    /**
     * The terminal reason for a pairing call that failed while nothing had connected back yet. A
     * typed reject keeps the peer's own token; anything else falls through to the shared transport
     * vocabulary, so an unreachable computer reads as a connection problem rather than a stale code.
     */
    fun of(e: Throwable): String =
        if (e is PrepareUploadRejectedException) e.reason else failureReason(e)
}
