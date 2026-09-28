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

/**
 * The retiring package's "move to the new app" page. Reached from the home title-bar icon, the
 * first-launch notice and the Me page. Tells the user why, checks whether the new app is installed
 * and genuinely ours, and offers the one action that applies: open it, or go get it.
 *
 * With a trusted new app that can take the account, the hand-over starts here: launch its receive
 * trampoline, then open our send page in credential-handoff mode. Otherwise just open the new app.
 */
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
        // The user may have just installed the new app and come back; re-check every time.
        status = PeerApps.peerStatus(this)
        L.i { "[MigrationV2][LegacyGuide] peer status=${status::class.simpleName}" }
        if (mode == LegacyGuideMode.GUIDE && status == PeerApps.PeerStatus.Trusted) refreshHandoffOffer()
    }

    /**
     * Ask the new app what it holds, off the main thread. Only a new app that is empty and new enough,
     * with this app equally able to hand over, earns the direct CTA.
     */
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

    /**
     * Ask the new app to bring its receiver up and answer with the pairing payload. Started for result
     * so the new app can vet `callingPackage` and its certificate, and so the answer lands here: the
     * confirmation page opens in this app, where the data is.
     */
    private val startReceiveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)
        val payload = raw?.let(QrPayloadCodec::decode)
        if (result.resultCode != RESULT_OK || payload == null || payload.ip != PeerApps.LOOPBACK_IP || payload.transferKey == null) {
            val reason = result.data?.getStringExtra(PeerApps.EXTRA_REJECT_REASON)
            L.w { "[MigrationV2][LegacyGuide] the new app did not bring its receiver up (resultCode=${result.resultCode} reason=$reason)" }
            // The new app named a condition (it already holds an account, the switch is off): the
            // page re-reads the offer so it shows that state instead of the button that just failed.
            if (reason != null) refreshHandoffOffer() else ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            return@registerForActivityResult
        }
        // Only this app's own code reaches here, with a payload the vetted peer just produced; the
        // send trampoline is the only other place the handoff flag is ever set.
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

    /** Re-login after a completed hand-over: an ordinary sign-in, which kicks the new app as any second device would. */
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

    /** One download page for every channel; it does the store / direct split itself. */
    private fun openDownloadPage() {
        if (!MigrationLinks.openDownloadPage(this)) ToastUtil.show(getString(R.string.legacy_migration_open_failed))
    }

    /** The new app is too old for the handoff protocol: its store listing, or the download page. */
    private fun updatePeer() {
        if (!MigrationLinks.openUpdateFor(this, PeerApps.peerPackage(this))) {
            ToastUtil.show(getString(R.string.legacy_migration_open_failed))
        }
    }

    companion object {
        /** Renders the "already moved to the new app" end state instead of the move-over guide. */
        private const val EXTRA_MIGRATED = "extra_migrated"

        fun start(context: Context) {
            context.startActivity(Intent(context, LegacyMigrationGuideActivity::class.java))
        }

        fun createIntent(context: Context, mode: LegacyGuideMode = LegacyGuideMode.GUIDE): Intent =
            Intent(context, LegacyMigrationGuideActivity::class.java)
                .putExtra(EXTRA_MIGRATED, mode == LegacyGuideMode.MIGRATED)
    }
}
