package com.difft.android.migration.legacy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.difft.android.p2p.android.qr.QrPayloadCodec
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.difft.android.R
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.LoginActivity
import com.difft.android.migration.ui.MigrationLinks
import com.difft.android.migration.v2.handoff.HandoffState
import com.difft.android.migration.v2.handoff.MigrationHandoffGate
import com.difft.android.migration.v2.handoff.MigrationStatusClient
import com.difft.android.migration.v2.transfer.send.MigrationP2pSendActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject









@AndroidEntryPoint
class LegacyMigrationGuideActivity : BaseActivity() {

    @Inject
    lateinit var handoffGate: MigrationHandoffGate

    @Inject
    lateinit var statusClient: MigrationStatusClient

    private var status by mutableStateOf<PeerApps.PeerStatus>(PeerApps.PeerStatus.NotInstalled)

    private var handoff by mutableStateOf(LegacyHandoffOffer.NONE)

    private val mode: LegacyGuideMode by lazy {
        if (intent.getBooleanExtra(EXTRA_MIGRATED, false)) LegacyGuideMode.MIGRATED else LegacyGuideMode.GUIDE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DifftTheme {
                LegacyMigrationGuideScreen(
                    status = status,
                    onOpenNewApp = ::openNewApp,
                    onDownload = ::openDownloadPage,
                    onLater = ::finish,
                    mode = mode,
                    handoff = handoff,
                    onStartHandoff = ::startHandoff,
                    onUpdatePeer = ::updatePeer,
                    onSignInAgain = ::signInAgain,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        status = PeerApps.peerStatus(this)
        L.i { "[MigrationV2][LegacyGuide] peer status=${status::class.simpleName}" }
        if (mode == LegacyGuideMode.GUIDE && status == PeerApps.PeerStatus.Trusted) refreshHandoffOffer()
    }

    



    private fun refreshHandoffOffer() {
        lifecycleScope.launch {
            handoff = when {
                !handoffGate.canHandOver() -> LegacyHandoffOffer.NONE
                else -> when (val reply = statusClient.queryStatus()) {
                    MigrationStatusClient.PeerStatusReply.TooOld -> LegacyHandoffOffer.PEER_TOO_OLD
                    MigrationStatusClient.PeerStatusReply.Unavailable -> LegacyHandoffOffer.NONE
                    is MigrationStatusClient.PeerStatusReply.Ok ->
                        if (reply.state == HandoffState.NONE) LegacyHandoffOffer.AVAILABLE
                        else LegacyHandoffOffer.PEER_OCCUPIED
                }
            }
            L.i { "[MigrationV2][LegacyGuide] handoff offer=$handoff" }
        }
    }

    




    private val startReceiveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)
        val payload = raw?.let(QrPayloadCodec::decode)
        if (result.resultCode != RESULT_OK || payload == null || payload.ip != PeerApps.LOOPBACK_IP || payload.transferKey == null) {
            val reason = result.data?.getStringExtra(PeerApps.EXTRA_REJECT_REASON)
            L.w { "[MigrationV2][LegacyGuide] the new app did not bring its receiver up (resultCode=${result.resultCode} reason=$reason)" }
            
            
            if (reason != null) refreshHandoffOffer() else ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            return@registerForActivityResult
        }
        
        
        L.i { "[MigrationV2][LegacyGuide] receiver is up → opening the confirmation page" }
        startActivity(
            Intent()
                .setClassName(packageName, PeerApps.SEND_ACTIVITY)
                .putExtra(PeerApps.EXTRA_QR_PAYLOAD, raw)
                .putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, true)
                .putExtra(MigrationP2pSendActivity.EXTRA_LEGACY_INITIATED, true),
        )
    }

    private fun startHandoff() {
        val intent = Intent(PeerApps.ACTION_HANDOFF_START_RECEIVE)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
        runCatching { startReceiveLauncher.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][LegacyGuide] could not reach the new app's trampoline: ${e.message}" }
                ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            }
    }

    
    private fun signInAgain() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun openNewApp() {
        val launch = packageManager.getLaunchIntentForPackage(PeerApps.peerPackage(this))
        if (launch == null) {
            ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            return
        }
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    
    private fun openDownloadPage() {
        if (!MigrationLinks.openDownloadPage(this)) ToastUtil.show(getString(R.string.legacy_migration_open_failed))
    }

    
    private fun updatePeer() {
        if (!MigrationLinks.openUpdateFor(this, PeerApps.peerPackage(this))) {
            ToastUtil.show(getString(R.string.legacy_migration_open_failed))
        }
    }

    companion object {
        
        private const val EXTRA_MIGRATED = "extra_migrated"

        fun start(context: Context) {
            context.startActivity(Intent(context, LegacyMigrationGuideActivity::class.java))
        }

        fun createIntent(context: Context, mode: LegacyGuideMode = LegacyGuideMode.GUIDE): Intent =
            Intent(context, LegacyMigrationGuideActivity::class.java)
                .putExtra(EXTRA_MIGRATED, mode == LegacyGuideMode.MIGRATED)
    }
}
