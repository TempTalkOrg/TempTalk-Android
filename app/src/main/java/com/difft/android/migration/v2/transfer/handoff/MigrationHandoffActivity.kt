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













class MigrationHandoffActivity : ComponentActivity() {

    
    private var startedReceiverHere = false

    
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
            
            L.w { "[MigrationV2][Handoff] send refused: payload absent, undecodable or not loopback" }
            return false
        }
        
        
        val credentialHandoff = intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
        L.i { "[MigrationV2][Handoff] send accepted from $caller handoff=$credentialHandoff → opening the send screen" }
        
        
        startActivity(
            Intent()
                .setClassName(packageName, PeerApps.SEND_ACTIVITY)
                .putExtra(PeerApps.EXTRA_QR_PAYLOAD, raw)
                .putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        return true
    }

    




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
            
            finishWith(true, Intent().putExtra(PeerApps.EXTRA_QR_PAYLOAD, QrPayloadCodec.encodeText(payload)))
        }
    }

    
    private fun receiveBlockReason(): String? {
        val gate = EntryPointAccessors.fromApplication(applicationContext, MigrationStatusProvider.StatusEntryPoint::class.java).handoffGate()
        return if (gate.canReceive()) null else MigrationRejectReason.TARGET_NOT_EMPTY.wire
    }

    private fun handleResumeReceive(): Boolean {
        vettedCaller("resume-receive") ?: return false
        L.i { "[MigrationV2][Handoff] resume-receive → bringing the receive screen to the front" }
        
        
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

    




    override fun onDestroy() {
        if (isFinishing && startedReceiverHere && !resultDelivered) {
            L.w { "[MigrationV2][Handoff] start-receive interrupted before the payload was published — tearing the receiver down" }
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    private companion object {
        
        const val RECEIVER_UP_TIMEOUT_MS = 8_000L
    }
}
