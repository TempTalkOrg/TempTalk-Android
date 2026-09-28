package com.difft.android.migration.v2.transfer.login

import android.graphics.Bitmap

/**
 * MVI contract for the receive screen: the new device showing a QR and waiting for the old device to
 * connect and stream. The Activity maps the terminal states to the `RESULT_RESTORED` /
 * `RESULT_SKIPPED` codes, so the `:login` sign-in and restart tail is reused unchanged.
 */
sealed interface MigrationP2pReceiveState {
    /** Bringing up the HTTPS server + cert; brief. */
    data object Starting : MigrationP2pReceiveState

    /**
     * Listening, or about to. Device-to-device: [qrBitmap] is the pairing code for the old device to
     * scan, null while the listener is still coming up (the code page shows with an empty tile rather
     * than flashing a preparing page). Same device ([sameDevice]): no QR — the pairing payload was
     * handed to the old app by intent and this screen just says so.
     */
    data class WaitingForPeer(
        val qrBitmap: Bitmap?,
        val sameDevice: Boolean = false,
        /** The code was just replaced by a fresh one; the page says so for a moment. */
        val refreshed: Boolean = false,
    ) : MigrationP2pReceiveState

    /**
     * Bytes are streaming into staging. [progress] in `[0, 1]` drives a determinate bar and is
     * approximate; `-1f` is indeterminate. [totalBytes] is what it is measured against, 0 if unknown.
     */
    data class Receiving(val progress: Float, val peerAlias: String? = null, val totalBytes: Long = 0L) : MigrationP2pReceiveState

    /**
     * Everything has arrived; the container is being verified and written (staging → promote), and
     * in the handoff case the session is being activated. Not cancellable.
     */
    data object Verifying : MigrationP2pReceiveState

    /** Verified and promoted on disk; the Activity returns RESULT_RESTORED to continue sign-in. */
    data object Promoted : MigrationP2pReceiveState

    /**
     * Credential handoff only: promoted, the session credential installed and the 200 receipt on the
     * wire, so this install is signed in and the old app may sign itself out. The only exit is a
     * process restart: the session was written outside the sign-in path, so every singleton holding a
     * logged-out snapshot has to be rebuilt.
     */
    data object Activated : MigrationP2pReceiveState

    /** A gate, guard or tamper reject; the reason is shown and the user can start over or leave. */
    data class Error(val reason: String) : MigrationP2pReceiveState

    /** The user backed out; the Activity returns RESULT_SKIPPED to the source chooser. */
    data object Skipped : MigrationP2pReceiveState
}

sealed interface MigrationP2pReceiveIntent {
    data object Start : MigrationP2pReceiveIntent
    data object Retry : MigrationP2pReceiveIntent

    /** Same device only: open the old app's send screen again (the first handoff was dismissed or failed). */
    data object RelaunchLegacySender : MigrationP2pReceiveIntent

    /** The old device is a Desktop showing an export QR: open the scanner instead of waiting to be scanned. */
    data object ScanPeerQr : MigrationP2pReceiveIntent

    /** The scanner returned [raw]; a `dir = "download"` payload switches this screen to pulling from the Desktop. */
    data class PeerQrScanned(val raw: String) : MigrationP2pReceiveIntent

    /** The scanner was dismissed without a code. */
    data object ScanCancelled : MigrationP2pReceiveIntent

    /** Cancel an in-flight transfer, after the confirmation, which cancels the service. */
    data object Cancel : MigrationP2pReceiveIntent

    /** Leave a page with nothing in flight, such as the QR or failure page, for the source chooser. */
    data object Leave : MigrationP2pReceiveIntent

    /**
     * Version block across devices: open this device's download page, which serves this app. Never
     * offered on the same-device path, where the peer is the old app and only it can update itself.
     */
    data object OpenUpdate : MigrationP2pReceiveIntent
}

/** One-shot side effects the receive Activity performs. */
sealed interface MigrationP2pReceiveEffect {
    /** Same device: start the old app's send screen (for result) with the pairing payload. */
    data class LaunchLegacySender(val rawPayload: String) : MigrationP2pReceiveEffect

    /** Open the QR scanner for a Desktop export code. */
    data object LaunchScanner : MigrationP2pReceiveEffect

    /** Open this device's download page, which serves this app. */
    data object OpenUpdatePage : MigrationP2pReceiveEffect

    data class Toast(val msg: String) : MigrationP2pReceiveEffect
}
