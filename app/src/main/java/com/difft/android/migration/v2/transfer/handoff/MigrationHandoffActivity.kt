package com.difft.android.migration.v2.transfer.handoff

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.migration.v2.handoff.MigrationStatusProvider
import com.difft.android.migration.v2.login.MigrationImportLauncherImpl
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import com.difft.android.migration.v2.transfer.isTerminal
import com.difft.android.p2p.android.qr.QrPayloadCodec
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.difft.android.p2p.engine.upload.MigrationRejectReason
import dagger.hilt.android.EntryPointAccessors

/**
 * The only exported entry point of the migration feature — a UI-less trampoline both package families
 * ship, so the two apps on one device can hand the transfer to each other without exposing their real
 * screens.
 *
 * Every action is started for result, so [getCallingPackage] is trustworthy, and every action vets
 * it: the caller must be the peer package signed with one of our certificates, and a send payload
 * must point at loopback. A payload from an unknown app would otherwise make this device stream its
 * history to whatever address it named. Start-receive shows nothing — the receiver comes up in the
 * foreground service and the pairing payload goes back in the result, so the final authorisation
 * stays on the old app's own page.
 */
class MigrationHandoffActivity : ComponentActivity() {

    /** True when this instance started the receiver, so an interrupted start may tear it down. */
    private var startedReceiverHere = false

    /** True once a result was set; the teardown in [onDestroy] is for the unanswered case only. */
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            PeerApps.ACTION_HANDOFF_SEND -> finishWith(handleSend())
            PeerApps.ACTION_HANDOFF_RESUME_RECEIVE -> finishWith(handleResumeReceive())
            PeerApps.ACTION_HANDOFF_START_RECEIVE -> handleStartReceive()
            else -> {
                L.w { "[MigrationV2][Handoff] unknown action=${intent?.action}" }
                finishWith(false)
            }
        }
    }

    private fun finishWith(ok: Boolean, data: Intent? = null) {
        resultDelivered = true
        if (ok) setResult(RESULT_OK, data) else setResult(RESULT_CANCELED)
        finish()
    }

    /** The caller must be the peer package, signed with one of our certificates. */
    private fun vettedCaller(what: String): String? {
        val caller = callingPackage
        if (caller == null || caller != PeerApps.peerPackage(this)) {
            L.w { "[MigrationV2][Handoff] $what refused: caller=$caller is not the peer package" }
            return null
        }
        if (PeerApps.peerStatus(this, caller) != PeerApps.PeerStatus.Trusted) {
            L.w { "[MigrationV2][Handoff] $what refused: caller certificate not trusted" }
            return null
        }
        return caller
    }

    private fun handleSend(): Boolean {
        val caller = vettedCaller("send") ?: return false
        val raw = intent.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)
        val payload = raw?.let(QrPayloadCodec::decode)
        if (payload == null || payload.ip != PeerApps.LOOPBACK_IP || payload.transferKey == null) {
            // Never log the payload itself: it carries the pin and the transfer key.
            L.w { "[MigrationV2][Handoff] send refused: payload absent, undecodable or not loopback" }
            return false
        }
        // The handoff flag can only ever arrive here — from the vetted peer, on a loopback payload —
        // which is what makes "a cross-device container can never carry a credential" structural.
        val credentialHandoff = intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
        L.i { "[MigrationV2][Handoff] send accepted from $caller handoff=$credentialHandoff → opening the send screen" }
        // REORDER_TO_FRONT: a send screen that already exists (the same pairing, mid-send) comes
        // forward instead of a second instance stacking on top of it.
        startActivity(
            Intent()
                .setClassName(packageName, PeerApps.SEND_ACTIVITY)
                .putExtra(PeerApps.EXTRA_QR_PAYLOAD, raw)
                .putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        return true
    }

    /**
     * Start the no-login receiver and hand its pairing payload back to the old app. This install must
     * be able to take an account first, and names the failing condition in the result so the old app
     * can show a reason; the handshake gate re-checks the same conditions when the bytes arrive.
     */
    private fun handleStartReceive() {
        if (vettedCaller("start-receive") == null) {
            finishWith(false)
            return
        }
        receiveBlockReason()?.let { reason ->
            L.w { "[MigrationV2][Handoff] start-receive refused: $reason" }
            finishWith(false, Intent().putExtra(PeerApps.EXTRA_REJECT_REASON, reason))
            return
        }
        L.i { "[MigrationV2][Handoff] start-receive accepted → bringing the receiver up" }
        val current = MigrationTransferStateHolder.state.value
        if (current !is TransferState.WaitingForPeer) {
            if (current != TransferState.Idle && !current.isTerminal()) {
                L.w { "[MigrationV2][Handoff] start-receive refused: a transfer is in flight (${current::class.simpleName})" }
                finishWith(false)
                return
            }
            MigrationTransferService.startReceiver(this, account = "", sameDevice = true, credentialHandoff = true)
            startedReceiverHere = true
        }
        lifecycleScope.launch {
            val payload = withTimeoutOrNull(RECEIVER_UP_TIMEOUT_MS) {
                MigrationTransferStateHolder.state
                    .combine(MigrationTransferStateHolder.qrPayload) { state, qr -> if (state is TransferState.WaitingForPeer) qr else null }
                    .filterNotNull()
                    .first()
            }
            if (payload == null || payload.ip != PeerApps.LOOPBACK_IP) {
                L.w { "[MigrationV2][Handoff] start-receive: receiver did not come up — tearing down" }
                MigrationTransferService.cancel(this@MigrationHandoffActivity)
                finishWith(false)
                return@launch
            }
            // The payload travels in the result only, never in a log line.
            finishWith(true, Intent().putExtra(PeerApps.EXTRA_QR_PAYLOAD, QrPayloadCodec.encodeText(payload)))
        }
    }

    /** Null when this install may adopt an account now; otherwise the wire token naming why not. */
    private fun receiveBlockReason(): String? {
        val gate = EntryPointAccessors.fromApplication(applicationContext, MigrationStatusProvider.StatusEntryPoint::class.java).handoffGate()
        return if (gate.canReceive()) null else MigrationRejectReason.TARGET_NOT_EMPTY.wire
    }

    private fun handleResumeReceive(): Boolean {
        vettedCaller("resume-receive") ?: return false
        L.i { "[MigrationV2][Handoff] resume-receive → bringing the receive screen to the front" }
        // singleTop + REORDER_TO_FRONT: an existing receive screen comes forward in its own task
        // instead of being recreated; when none exists yet this creates it in the handoff shape.
        startActivity(
            MigrationImportLauncherImpl.createP2pReceiveIntentFor(
                this,
                account = "",
                sameDevice = true,
                credentialHandoff = true,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        return true
    }

    /**
     * This is a `noHistory` trampoline: leaving the task destroys it and cancels the wait on
     * [lifecycleScope], so a receiver started here would keep listening with nobody left to publish
     * its payload. A receiver that was already up when the caller arrived is not ours to stop.
     */
    override fun onDestroy() {
        if (isFinishing && startedReceiverHere && !resultDelivered) {
            L.w { "[MigrationV2][Handoff] start-receive interrupted before the payload was published — tearing the receiver down" }
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    private companion object {
        /** How long the receiver gets to publish its pairing payload before the caller is told it failed. */
        const val RECEIVER_UP_TIMEOUT_MS = 8_000L
    }
}
