package com.difft.android.migration.v2.transfer.send

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.NewInstanceFactory
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.R
import com.difft.android.chat.invite.ScanActivity
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.storage.user.StorageBoundUserManager
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.appScope
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.login.ScreenLockActivity
import com.difft.android.migration.ui.MigrationLinks
import com.difft.android.migration.v2.export.ExportEstimate
import com.difft.android.migration.v2.export.MigrationReadSource
import com.difft.android.migration.v2.export.NeutralExporterImpl
import com.difft.android.migration.v2.handoff.HandoffAuthPolicy
import com.difft.android.migration.v2.handoff.HandoffSpaceCheck
import com.difft.android.migration.v2.handoff.MigrationHandoffGate
import com.difft.android.migration.v2.handoff.MigrationHandoffReconciler
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.VerdictProbe
import com.difft.android.migration.v2.handoff.MigrationStatusClient
import com.difft.android.migration.v2.transfer.DeviceNames
import com.difft.android.p2p.android.client.EndpointRace
import com.difft.android.p2p.android.client.SenderHttpClient
import com.difft.android.p2p.android.qr.QrPayloadCodec
import com.difft.android.p2p.engine.model.DeviceInfo
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel

/**
 * The send Activity on the old device, the mirror of the receive Activity in the sender role.
 * Launched plain, not for result, from the Me page, the home scanner or the same-device trampoline.
 * It scans the new device's QR, then streams the real export with the live account, environment and
 * handshake so the receiver's gate fires.
 *
 * The transfer runs on the service-owned scope and the view model re-attaches through the holder, so
 * Activity death does not cancel an in-flight send. This device never signs itself out here: a phone
 * receiver registers as the primary and the server revokes this session, which the app-wide kick-out
 * detection handles; a desktop receiver only links as a secondary. Registered non-exported, with
 * FLAG_SECURE so the QR target and progress stay out of thumbnails and screen recordings.
 */
@AndroidEntryPoint
class MigrationP2pSendActivity : BaseActivity() {

    /** Live read source for the pairing page's size estimate; the send itself works from a snapshot. */
    @Inject
    lateinit var readSource: MigrationReadSource

    @Inject
    lateinit var userManager: StorageBoundUserManager

    /** The handoff gate + the reconciler are the send screen's half of the no-login migration. */
    @Inject
    lateinit var handoffGate: MigrationHandoffGate

    @Inject
    lateinit var handoffReconciler: MigrationHandoffReconciler

    @Inject
    lateinit var statusClient: MigrationStatusClient

    @Inject
    lateinit var pendingVerdictStore: PendingVerdictStore

    private val fromLink: Boolean by lazy { intent.getBooleanExtra(PeerApps.EXTRA_FROM_LINK, false) }

    /**
     * The confirmation page was opened by this app's own guide page, so the new app has a receiver
     * open purely for it and leaving without starting releases it. When the new app opened this page,
     * its own screen owns the receiver and nothing is released from here.
     */
    private val legacyInitiated: Boolean by lazy { intent.getBooleanExtra(EXTRA_LEGACY_INITIATED, false) }

    /** The signed-in account's contact card, for the confirmation page's identity row; null until loaded. */
    private var selfContact by mutableStateOf<ContactorModel?>(null)

    /**
     * Only ever true when the vetted same-device trampoline set it: the caller's package and pinned
     * signing certificate were checked there, and the pairing payload had to point at loopback.
     */
    private val credentialHandoff: Boolean by lazy {
        intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
    }

    private val viewModel: MigrationP2pSendViewModel by lazy {
        ViewModelProvider(
            this,
            object : NewInstanceFactory() {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    // The view model drives the transfer through MigrationTransferService, which owns the
                    // scope, the engine dependencies and the live account read; this supplies a context.
                    MigrationP2pSendViewModel(
                        applicationContext,
                        estimator = ::estimateExport,
                        autoStart = intent.getBooleanExtra(PeerApps.EXTRA_AUTO_START, false),
                        initialIncludeAttachments = intent.getBooleanExtra(PeerApps.EXTRA_INCLUDE_ATTACHMENTS, true),
                        credentialHandoff = credentialHandoff,
                        handoffBlockReason = ::handoffBlockReason,
                        authMethod = { HandoffAuthPolicy.required(this@MigrationP2pSendActivity, userManager.getUserData()) },
                        spaceVerdict = { estimate, include -> HandoffSpaceCheck.evaluate(filesDir, estimate, include) },
                        reconcile = { handoffReconciler.onScreenResumed() },
                        awaitPeerConfirm = intent.getBooleanExtra(PeerApps.EXTRA_AWAIT_PEER_CONFIRM, false),
                        resolveEndpoint = { target -> EndpointRace.resolve(target) },
                        claimPairing = { target -> SenderHttpClient(target).claimPairing(selfDeviceInfo(), target.pin) },
                        loadPendingVerdict = { pendingVerdictStore.load() },
                        clearPendingVerdict = { pendingVerdictStore.clear() },
                        probeVerdict = VerdictProbe::check,
                    ) as T
            },
        )[MigrationP2pSendViewModel::class.java]
    }

    /** This device as the receiver sees it on the claim; the fingerprint is a throwaway, as on every sender call. */
    private fun selfDeviceInfo() = DeviceInfo(
        alias = DeviceNames.local(applicationContext),
        fingerprint = "tx-${UUID.randomUUID().toString().take(8)}",
    )

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val raw = result.data?.getStringExtra(ScanActivity.EXTRA_SCAN_RESULT)
                if (raw != null) {
                    viewModel.onIntent(MigrationP2pSendIntent.QrScanned(raw))
                } else {
                    viewModel.onIntent(MigrationP2pSendIntent.ScanCancelled)
                }
            } else {
                viewModel.onIntent(MigrationP2pSendIntent.ScanCancelled)
            }
        }

    /**
     * Same device: the new app's trampoline is started for result so it can vet `callingPackage`. The
     * result carries nothing this screen acts on — the user is already over there — so a refusal is
     * only logged.
     */
    private val resumeReceiveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                L.w { "[MigrationV2][P2P][Tx] new app refused resume-receive resultCode=${result.resultCode}" }
            }
        }

    /**
     * Same device, retry: the new app's trampoline is asked to bring a receiver up and answer with a
     * fresh pairing payload, started for result so it can vet `callingPackage` (and so the answer
     * lands here). Only a loopback payload carrying a key is taken; the payload itself is never
     * logged. A refusal the new app named (it holds an account now, the switch is off) leaves the
     * failure screen up with a toast.
     */
    private val startReceiveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val raw = result.data?.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)
            val payload = raw?.let(QrPayloadCodec::decode)
            if (result.resultCode != Activity.RESULT_OK || raw == null || payload == null ||
                payload.ip != PeerApps.LOOPBACK_IP || payload.transferKey == null
            ) {
                val reason = result.data?.getStringExtra(PeerApps.EXTRA_REJECT_REASON)
                L.w { "[MigrationV2][P2P][Tx] fresh pairing refused resultCode=${result.resultCode} reason=$reason" }
                ToastUtil.show(getString(R.string.legacy_migration_open_failed))
                return@registerForActivityResult
            }
            viewModel.onFreshPairing(raw)
        }

    /**
     * The authoritative re-check, immediately before the user commits: null when the hand-over may
     * proceed, otherwise the wire token the screen renders. The local session can be revoked between
     * the new app's probe and this moment.
     */
    private fun handoffBlockReason(): String? =
        if (handoffGate.canHandOver()) null
        else com.difft.android.migration.model.MigrationError.CredentialMissing.code

    override fun onResume() {
        super.onResume()
        // The user may have backgrounded a failure screen while the new app finished, so an
        // unconfirmed result is re-asked before anything is assumed about it.
        viewModel.onScreenResumed()
    }

    override fun onDestroy() {
        // The new app opened a receiver for this confirmation page; leaving without starting would
        // otherwise leave it listening until its pairing timeout and a failure notice over there.
        if (credentialHandoff && legacyInitiated && isFinishing && !viewModel.sendStarted) {
            L.i { "[MigrationV2][Handoff] confirmation page left without starting — releasing the new app's receiver" }
            appScope.launch { runCatching { statusClient.cancelReceive() } }
        }
        super.onDestroy()
    }

    private suspend fun estimateExport(): ExportEstimate =
        NeutralExporterImpl(readSource)
            .apply { selfUid = userManager.getUserData()?.account.orEmpty() }
            .estimate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The migrated data is sensitive: keep the QR target and progress out of recent-tasks
        // thumbnails and screen recordings, as the receive Activity does.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Auto-lock is the most common way a transfer gets interrupted: the screen sleeps, Wi-Fi drops
        // to power-save and the CPU suspends. Hold the screen on for as long as this screen is in
        // front (the service additionally holds wake/Wi-Fi locks for the cases this cannot cover —
        // an incoming call, a manual lock press).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                val state by viewModel.state.collectAsState()
                val peerAlias by viewModel.targetAlias.collectAsState()
                val sameDevice by viewModel.sameDevice.collectAsState()
                MigrationP2pSendScreen(
                    state = state,
                    onIntent = viewModel::onIntent,
                    peerAlias = peerAlias,
                    sameDevice = sameDevice,
                    fromLink = fromLink,
                    credentialHandoff = credentialHandoff,
                    selfContact = selfContact,
                    selfId = userManager.getUserData()?.account.orEmpty(),
                )
            }
        }
        setContentView(composeView)
        loadSelfContact()

        // A pairing payload handed in by intent — the same-device trampoline, or the home scanner
        // that recognised a migration QR — skips the scan step and lands on Ready directly.
        if (savedInstanceState == null) {
            intent.getStringExtra(PeerApps.EXTRA_QR_PAYLOAD)?.let { viewModel.onIntent(MigrationP2pSendIntent.QrScanned(it)) }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        MigrationP2pSendEffect.LaunchScanActivity -> launchScan()
                        is MigrationP2pSendEffect.Toast -> ToastUtil.show(effect.msg)
                        MigrationP2pSendEffect.Finish -> finish()
                        MigrationP2pSendEffect.ReturnToPeerApp -> returnToPeerApp()
                        MigrationP2pSendEffect.BringPeerToFront -> bringPeerToFront()
                        MigrationP2pSendEffect.RequestAppLockAuth -> launchAppLockAuth()
                        MigrationP2pSendEffect.RequestDeviceCredentialAuth -> launchDeviceCredentialAuth()
                        MigrationP2pSendEffect.OpenStorageSettings -> MigrationLinks.openStorageSettings(this@MigrationP2pSendActivity)
                        MigrationP2pSendEffect.OpenUpdatePage -> openUpdatePage()
                        MigrationP2pSendEffect.RescanLink -> rescanLink()
                        MigrationP2pSendEffect.RequestFreshPairing -> requestFreshPairing()
                    }
                }
            }
        }
        // Same device: once the receiver has accepted the container there is nothing left to do here — the
        // user is already on the new app. A failure keeps this screen so a retry launched from the new
        // app still has the pairing payload.
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                // Same device: the user is on the new app. Desktop link: the result shows on the Desktop.
                // Either way this screen has nothing left to say.
                if (state is MigrationP2pSendState.Done && fromLink) {
                    L.i { "[MigrationV2][P2P][Tx] send done — the Desktop shows the result, leaving the send screen" }
                    finish()
                }
                // A confirmed cancel has nothing left to show: the transfer is stopped and this
                // device is exactly as it was.
                if (state is MigrationP2pSendState.Cancelled) {
                    L.i { "[MigrationV2][P2P][Tx] send cancelled — leaving the send screen" }
                    finish()
                }
            }
        }
    }

    /**
     * The app's own App Lock, in verification mode: it sets RESULT_OK only from a successful
     * verification, and a back press comes back as RESULT_CANCELED.
     */
    private val appLockAuth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onIntent(
                if (result.resultCode == Activity.RESULT_OK) {
                    MigrationP2pSendIntent.AuthSucceeded
                } else {
                    MigrationP2pSendIntent.AuthFailed
                },
            )
        }

    private val deviceCredentialAuth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onIntent(
                if (result.resultCode == Activity.RESULT_OK) {
                    MigrationP2pSendIntent.AuthSucceeded
                } else {
                    MigrationP2pSendIntent.AuthFailed
                },
            )
        }

    private fun launchAppLockAuth() {
        val intent = Intent(this, ScreenLockActivity::class.java)
            .putExtra(ScreenLockActivity.EXTRA_VERIFICATION_MODE, true)
        runCatching { appLockAuth.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][Handoff] app-lock verification could not start: ${e.message}" }
                viewModel.onIntent(MigrationP2pSendIntent.AuthFailed)
            }
    }

    /**
     * The system device credential. The requirement here is user presence, not a crypto-bound unlock —
     * the credential's confidentiality comes from the container's own encryption. A null intent means
     * the lock screen was removed since the policy decision, leaving nothing to verify against, so it
     * proceeds as it would on a device that never had one.
     */
    private fun launchDeviceCredentialAuth() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") // Deprecated at API 29; the only single-path API across API 23..36.
        val intent = keyguard?.createConfirmDeviceCredentialIntent(
            getString(R.string.migration_handoff_auth_title),
            getString(R.string.migration_handoff_auth_desc),
        )
        if (intent == null) {
            L.i { "[MigrationV2][Handoff] no device credential to confirm — skipping" }
            viewModel.onIntent(MigrationP2pSendIntent.AuthSucceeded)
            return
        }
        runCatching { deviceCredentialAuth.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][Handoff] device-credential confirmation could not start: ${e.message}" }
                viewModel.onIntent(MigrationP2pSendIntent.AuthFailed)
            }
    }

    /** The confirmation page shows who is signing the data away, from the contact card. */
    private fun loadSelfContact() {
        val id = userManager.getUserData()?.account.orEmpty()
        if (id.isEmpty()) return
        lifecycleScope.launch {
            selfContact = runCatching {
                withContext(Dispatchers.IO) { ContactorUtil.getContactWithID(this@MigrationP2pSendActivity, id).orElse(null) }
            }.onFailure { e -> L.w { "[MigrationV2][P2P][Tx] self contact lookup failed: ${e.message}" } }
                .getOrNull()
        }
    }

    /**
     * "Update Quicall": the end that is too old gets updated. On the old app the too-old end is
     * usually the new app; everywhere else it is this install.
     */
    /**
     * Desktop link, after a failure: the computer's session is over, so the only restart is its new
     * code. The home scanner recognises it and opens the sign-in confirmation page itself, so this
     * screen has nothing more to do than get out of the way.
     */
    private fun rescanLink() {
        val intent = Intent(this, ScanActivity::class.java)
        runCatching { startActivity(intent) }
            .onFailure {
                L.w { "[MigrationV2][P2P][Tx] rescan launch failed: ${it.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.migration_p2p_send_scanner_unavailable))
            }
        finish()
    }

    private fun openUpdatePage() {
        val target = if (PeerApps.isLegacy(this)) PeerApps.peerPackage(this) else packageName
        MigrationLinks.openUpdateFor(this, target)
    }

    /**
     * Same device: bring the new app's receive screen forward via its trampoline; this screen stays
     * underneath. Started for result — and therefore without FLAG_ACTIVITY_NEW_TASK, which would
     * cancel the result immediately — so the trampoline sees a `callingPackage` to vet. The
     * trampoline is translucent and starts the receive screen in its own task itself.
     */
    private fun bringPeerToFront() {
        val intent = Intent(PeerApps.ACTION_HANDOFF_RESUME_RECEIVE)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
        runCatching { resumeReceiveLauncher.launch(intent) }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Tx] could not bring the new app forward: ${e.message}" } }
    }

    /**
     * Same device: the pairing payload is session-scoped, so a retry asks the new app's trampoline
     * for a new one. No FLAG_ACTIVITY_NEW_TASK — the result must come back to this task for the
     * trampoline to see a `callingPackage` to vet.
     */
    private fun requestFreshPairing() {
        val intent = Intent(PeerApps.ACTION_HANDOFF_START_RECEIVE)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
        runCatching { startReceiveLauncher.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][P2P][Tx] could not reach the new app's trampoline: ${e.message}" }
                ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            }
    }

    /** Same device: hand the foreground to the new app and leave. */
    private fun returnToPeerApp() {
        bringPeerToFront()
        finish()
    }

    private fun launchScan() {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra(ScanActivity.EXTRA_RETURN_RESULT, true)
            putExtra(ScanActivity.EXTRA_DECLINE_COMPUTER_EXPORT_CODE, true)
            putExtra(ScanActivity.EXTRA_MIGRATION_STYLE, true)
        }
        runCatching { scanLauncher.launch(intent) }
            .onFailure {
                L.w { "[MigrationV2][P2P][Tx] scan launch failed: ${it.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.migration_p2p_send_scanner_unavailable))
                viewModel.onIntent(MigrationP2pSendIntent.ScanCancelled)
            }
    }

    companion object {
        /** Set only by this app's own guide page when it opens the confirmation page (old-app-first path). */
        const val EXTRA_LEGACY_INITIATED = "extra_legacy_initiated"

    }
}
