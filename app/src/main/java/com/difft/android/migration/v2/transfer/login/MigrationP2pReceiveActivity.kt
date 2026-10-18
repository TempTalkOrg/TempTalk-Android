package com.difft.android.migration.v2.transfer.login

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.NewInstanceFactory
import com.difft.android.chat.invite.ScanActivity
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.MigrationImportLauncher
import com.difft.android.base.ui.theme.DifftTheme
import dagger.hilt.android.AndroidEntryPoint
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.R
import com.difft.android.base.migration.PeerApps
import com.difft.android.base.widget.ToastUtil
import com.difft.android.login.LoginActivity
import com.difft.android.login.entry.PendingLoginStore
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.login.viewmodel.SignInTransportFailure
import com.difft.android.network.viewmodel.Status
import com.difft.android.migration.ui.MigrationLinks
import com.difft.android.migration.v2.login.MigrationImportLauncherImpl
import com.difft.android.migration.v2.transfer.MigrationTransferService
import com.difft.android.migration.v2.transfer.MigrationTransferStateHolder
import com.difft.android.migration.v2.transfer.TransferState
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.restore.RestoreFlagStore

/**
 * The receive Activity on the new device. Its result contract is what the `:login` sign-in and
 * restart tail consumes: [MigrationImportLauncher.RESULT_RESTORED] once the import is verified and
 * promoted, [MigrationImportLauncher.RESULT_SKIPPED] when the user backs out.
 *
 * Launched for result by `:login` when the user picks "Receive from your old device". The
 * pre-transfer gate is wired by [MigrationP2pReceiveController], so an incompatible source is
 * rejected before any byte flows. Registered non-exported in the manifest.
 */
@AndroidEntryPoint
class MigrationP2pReceiveActivity : BaseActivity() {

    private val sameDevice: Boolean by lazy {
        intent.getBooleanExtra(MigrationImportLauncherImpl.EXTRA_SAME_DEVICE, false)
    }

    /** "Transfer from computer": open the scanner at once; the Desktop shows the code. */
    private val scanDesktop: Boolean by lazy {
        intent.getBooleanExtra(MigrationImportLauncherImpl.EXTRA_SCAN_DESKTOP, false)
    }

    /**
     * The no-login credential handoff: there is no account and no login waiting for a result — this
     * install adopts the old app's session and restarts into it.
     */
    private val credentialHandoff: Boolean by lazy {
        intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
    }

    /** Set once the session is in; leaving after that must not tear the receiver down. */
    private var handoffActivated = false

    @Inject
    lateinit var migrationImportLauncher: MigrationImportLauncher

    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    /**
     * The same sign-in chain the code page runs, hosted here so a promoted import signs in without
     * returning to the verification page. Only its observer differs; nothing of the chain is
     * duplicated.
     */
    private val loginViewModel: LoginViewModel by viewModels()

    private var promotedSignIn by mutableStateOf<PromotedSignIn>(PromotedSignIn.Idle)

    /** The verified sign-in this import was started for; the held copy in [pendingLoginStore] is the fallback. */
    private val pendingSignIn: LoginViewModel.PendingSignIn? by lazy {
        IntentCompat.getParcelableExtra(intent, MigrationImportLauncherImpl.EXTRA_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java)
    }

    /** Cached after the store's one-time read, for retries. */
    private var heldSignIn: LoginViewModel.PendingSignIn? = null

    /** Erasing the held sign-in after a successful one; "Done" waits on it before killing the process. */
    private var pendingLoginCleanup: Job? = null

    private val viewModel: MigrationP2pReceiveViewModel by lazy {
        val account = intent.getStringExtra(EXTRA_ACCOUNT).orEmpty()
        ViewModelProvider(
            this,
            object : NewInstanceFactory() {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    MigrationP2pReceiveViewModel(applicationContext, account, sameDevice, credentialHandoff, scanDesktop) as T
            },
        )[MigrationP2pReceiveViewModel::class.java]
    }

    /**
     * Same device: the old app's trampoline is started for result so it can trust `callingPackage`,
     * which a plain startActivity would not give it. A refusal or a missing old app comes back as a
     * cancelled result.
     */
    private val legacyHandoff = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            L.w { "[MigrationV2][P2P][Rx] same-device handoff refused or failed resultCode=${result.resultCode}" }
            ToastUtil.show(getString(R.string.migration_p2p_receive_legacy_open_failed))
        }
    }

    /** Desktop export code: the scanner returns the raw payload; the ViewModel decides whether it is one. */
    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val raw = result.data?.getStringExtra(ScanActivity.EXTRA_SCAN_RESULT)
        if (result.resultCode == RESULT_OK && raw != null) {
            viewModel.onIntent(MigrationP2pReceiveIntent.PeerQrScanned(raw))
        } else {
            viewModel.onIntent(MigrationP2pReceiveIntent.ScanCancelled)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reached without an account only via the same-device trampoline after this instance was
        // already gone: there is no login waiting for a result, so there is nothing to receive into.
        // In handoff mode the account is deliberately empty — it arrives inside the container — so
        // the "nothing to receive into" guard applies to the login path only.
        if (!credentialHandoff && intent.getStringExtra(EXTRA_ACCOUNT).isNullOrEmpty()) {
            L.w { "[MigrationV2][P2P][Rx] opened without an account — nothing to resume, finishing" }
            finish()
            return
        }
        // The migrated data is sensitive — keep the QR/progress out of recent-tasks thumbnails /
        // screen recordings.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        // Auto-lock is the most common way a transfer gets interrupted: the screen sleeps, Wi-Fi drops
        // to power-save and the CPU suspends. Hold the screen on for as long as this screen is in
        // front (the service additionally holds wake/Wi-Fi locks for the cases this cannot cover —
        // an incoming call, a manual lock press).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        observeSignIn()

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                val state by viewModel.state.collectAsState()

                // Drive the terminal `setResult + finish` from a LaunchedEffect keyed on the result code,
                // not from composition: finishing during composition would re-run on every recomposition
                // that observes a terminal state and could fire twice. Promoted is not terminal here — the
                // page finishes the sign-in itself.
                val terminalResult = when (state) {
                    MigrationP2pReceiveState.Skipped -> MigrationImportLauncher.RESULT_SKIPPED
                    else -> null
                }
                LaunchedEffect(terminalResult) {
                    when {
                        terminalResult == null -> Unit
                        // The no-login handoff has no login waiting for a result: leaving it means
                        // signing in the ordinary way instead.
                        terminalResult == MigrationImportLauncher.RESULT_SKIPPED && credentialHandoff -> openLogin()
                        // Promoted data is already on disk (the import failed only after its commit, or
                        // the sign-in did not finish). The live DB singleton still holds the pre-promote
                        // file, so the process must cold-start; the login then resumes the restore path.
                        terminalResult == MigrationImportLauncher.RESULT_SKIPPED && promotedOnDisk() -> {
                            L.w { "[MigrationV2][P2P][Rx] leaving after a promoted import → cold start instead of the in-process login" }
                            restartApp()
                        }
                        else -> finishWith(terminalResult)
                    }
                }

                // Handoff: nothing is waiting for a result — the session was written outside the
                // sign-in path, so every singleton holding a logged-out snapshot has to be rebuilt.
                val activated = state == MigrationP2pReceiveState.Activated
                LaunchedEffect(activated) {
                    if (activated) restartApp()
                }

                // The database was switched but the tail after it is not proven done, so the in-process
                // sign-in must not run: a cold start finishes the promote before anything else.
                val committedUnconfirmed = state == MigrationP2pReceiveState.CommittedUnconfirmed
                LaunchedEffect(committedUnconfirmed) {
                    if (committedUnconfirmed) {
                        L.w { "[MigrationV2][P2P][Rx] import committed; tail unconfirmed — cold start to finish it" }
                        restartApp()
                    }
                }

                // The history is on disk: finish the sign-in here without waiting for a tap, so the
                // completion page can come after it rather than before. Guarded by the phase, which
                // leaves Idle for good on the first start.
                val promotedForSignIn = state == MigrationP2pReceiveState.Promoted && !credentialHandoff
                LaunchedEffect(promotedForSignIn) {
                    if (promotedForSignIn && promotedSignIn == PromotedSignIn.Idle) signInAfterPromote()
                }

                when {
                    // Terminal states are handled by the LaunchedEffect above; render nothing while the
                    // Activity finishes.
                    terminalResult != null -> Unit
                    // The sign-in runs on this page, then the completion page. A failed sign-in hands
                    // back to the code page, which asks for a fresh code and registers directly — the
                    // data is never imported twice.
                    promotedForSignIn -> PromotedPages(
                        phase = promotedSignIn,
                        onFinish = ::finishPromotedSignIn,
                        onRetry = ::signInAfterPromote,
                        onContinueSignIn = { finishWith(MigrationImportLauncher.RESULT_RESTORED) },
                    )
                    else -> MigrationP2pReceiveScreen(state = state, onIntent = viewModel::onIntent, sameDevice = sameDevice)
                }
            }
        }
        setContentView(composeView)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect ->
                    when (effect) {
                        is MigrationP2pReceiveEffect.LaunchLegacySender -> launchLegacySender(effect.rawPayload)
                        MigrationP2pReceiveEffect.LaunchScanner -> launchScan()
                        MigrationP2pReceiveEffect.OpenUpdatePage -> MigrationLinks.openDownloadPage(this@MigrationP2pReceiveActivity)
                        is MigrationP2pReceiveEffect.Toast -> ToastUtil.show(effect.msg)
                    }
                }
            }
        }

        // First entry → start the receiver once. Re-entry across config change keeps ViewModel state.
        if (savedInstanceState == null) {
            viewModel.onIntent(MigrationP2pReceiveIntent.Start)
        }
    }

    private fun launchLegacySender(rawPayload: String) {
        val intent = Intent(PeerApps.ACTION_HANDOFF_SEND)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
            .putExtra(PeerApps.EXTRA_QR_PAYLOAD, rawPayload)
            // The old app decides auth + credential record from this flag; without it the receiver's
            // gate would refuse the handshake as a mode mismatch.
            .putExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, credentialHandoff)
        runCatching { legacyHandoff.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][P2P][Rx] could not start the old app's handoff: ${e.message}" }
                ToastUtil.show(getString(R.string.migration_p2p_receive_legacy_open_failed))
            }
    }

    private fun openLogin() {
        L.i { "[MigrationV2][Handoff] leaving the receive flow for the ordinary sign-in" }
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    /**
     * The home scanner, asked to hand the raw code back instead of acting on it (same seam the send
     * screen uses), dressed as the pairing page's scan state: what to open on the computer and the
     * network rule.
     */
    private fun launchScan() {
        val intent = Intent(this, ScanActivity::class.java).apply {
            putExtra(ScanActivity.EXTRA_RETURN_RESULT, true)
            putExtra(ScanActivity.EXTRA_TITLE, getString(R.string.migration_r2_scan_title))
            putExtra(ScanActivity.EXTRA_MIGRATION_STYLE, true)
            putExtra(ScanActivity.EXTRA_HINT, getString(R.string.migration_r2_scan_body))
            putExtra(ScanActivity.EXTRA_HINT_SECONDARY, getString(R.string.migration_r2_network))
        }
        runCatching { scanLauncher.launch(intent) }
            .onFailure {
                L.w { "[MigrationV2][P2P][Rx] scan launch failed: ${it.stackTraceToString()}" }
                ToastUtil.show(getString(R.string.migration_p2p_send_scanner_unavailable))
            }
    }

    private fun finishWith(resultCode: Int) {
        L.i { "[MigrationV2][P2P][Rx] finishing resultCode=$resultCode" }
        setResult(resultCode)
        finish()
    }

    /** True once a restore has promoted its data into the live DB on this install. */
    private suspend fun promotedOnDisk(): Boolean = withContext(Dispatchers.IO) {
        runCatching { RestoreFlagStore(this@MigrationP2pReceiveActivity).currentPhase() }.getOrNull()
            .let { it == RestorePhase.SIGNING_IN || it == RestorePhase.COMPLETED }
    }

    /**
     * The automatic start once the import is promoted, and "Try Again" after a failure: finish the
     * held sign-in here. Redeeming the transfer token is the moment the account moves; a retry
     * reuses the still-unspent token.
     */
    private fun signInAfterPromote() {
        if (promotedSignIn is PromotedSignIn.Running) return
        promotedSignIn = PromotedSignIn.Running
        lifecycleScope.launch {
            val pending = pendingSignIn ?: heldSignIn ?: pendingLoginStore.consume()?.also { heldSignIn = it }
            if (pending == null) {
                L.w { "[MigrationV2][P2P][Rx] promoted, but no verified sign-in to finish with" }
                promotedSignIn = PromotedSignIn.Failed(null, retryable = false)
                return@launch
            }
            L.i { "[MigrationV2][P2P][Rx] promoted → signing in on this page transferToken=${pending.renewToken != null}" }
            loginViewModel.continueSignInAfterPrompt(pending, restored = true)
        }
    }

    /**
     * "Done" on the completion page: let the held sign-in finish being erased, then cold start into
     * the migrated account.
     */
    private fun finishPromotedSignIn() {
        lifecycleScope.launch {
            pendingLoginCleanup?.join()
            restartApp()
        }
    }

    /** The chain's outcome, presented on this page; the success tail is the code page's, verbatim. */
    private fun observeSignIn() {
        loginViewModel.signInLiveData.observe(this) { result ->
            when (result.status) {
                Status.LOADING -> promotedSignIn = PromotedSignIn.Running
                Status.SUCCESS -> {
                    // LiveData re-delivers its last value to a new observer after a config change; the
                    // tail below must run once.
                    if (promotedSignIn == PromotedSignIn.Done) return@observe
                    // Clear SIGNING_IN first, or every later login would take the restore path. The cold
                    // start for the promoted database (the WCDB singleton may still hold the empty one)
                    // is what "Done" on the completion page does — the page itself shows no chat data,
                    // so it is safe to render before the restart.
                    migrationImportLauncher.markRestoreSignInComplete()
                    L.i { "[MigrationV2][P2P][Rx] restore-path sign-in succeeded → flag COMPLETED, showing the completion page" }
                    promotedSignIn = PromotedSignIn.Done
                    pendingLoginCleanup = lifecycleScope.launch { pendingLoginStore.clear() }
                }
                Status.ERROR -> {
                    // Only a request that got no answer leaves the held token unspent; a server
                    // verdict would answer a retry exactly the same way.
                    val retryable = result.exception is SignInTransportFailure
                    L.w { "[MigrationV2][P2P][Rx] restore-path sign-in failed retryable=$retryable: ${result.exception?.errorMsg}" }
                    promotedSignIn = PromotedSignIn.Failed(result.exception?.errorMsg, retryable = retryable)
                }
            }
        }
    }

    /**
     * The session is installed: relaunch the process so the login-time singletons are rebuilt around
     * it. The relaunch is best-effort but the process exit is not — a surviving process would keep
     * serving a logged-out snapshot of an account that is now signed in.
     */
    private fun restartApp() {
        handoffActivated = true
        L.i { "[MigrationV2][P2P][Rx] restarting into the migrated account" }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        launch?.let { runCatching { startActivity(it) }.onFailure { e -> L.w { "[MigrationV2][Handoff] relaunch failed: ${e.message}" } } }
        Runtime.getRuntime().exit(0)
    }

    // The receiver's no-peer clock runs only while this page is off screen: on screen the code keeps
    // rotating and the user is plainly still waiting; gone, a listener nobody can scan is bounded.
    override fun onStart() {
        super.onStart()
        MigrationTransferStateHolder.setPairingPageVisible(true)
    }

    override fun onStop() {
        MigrationTransferStateHolder.setPairingPageVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Every way out of the handoff path stops the receiver. The service's duplicate-start guard
        // only blocks a second transfer; it does nothing about a receiver still listening while the
        // user goes off to sign in normally.
        if (credentialHandoff && isFinishing && !handoffActivated) {
            L.i { "[MigrationV2][Handoff] leaving the receive screen — cancelling the receiver" }
            MigrationTransferService.cancel(this)
        } else if (isFinishing && MigrationTransferStateHolder.state.value.isListening()) {
            // Login path: leaving the QR page with no peer connected. A listener left behind would
            // block every later send from this device until its pairing timeout.
            L.i { "[MigrationV2][P2P][Rx] leaving the pairing page — cancelling the idle receiver" }
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    private fun TransferState.isListening(): Boolean = this is TransferState.WaitingForPeer || this == TransferState.Starting

    companion object {
        // Must match MigrationImportLauncherImpl.EXTRA_ACCOUNT ("extra_account").
        private const val EXTRA_ACCOUNT = "extra_account"
    }
}
