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










@AndroidEntryPoint
class MigrationP2pReceiveActivity : BaseActivity() {

    private val sameDevice: Boolean by lazy {
        intent.getBooleanExtra(MigrationImportLauncherImpl.EXTRA_SAME_DEVICE, false)
    }

    
    private val scanDesktop: Boolean by lazy {
        intent.getBooleanExtra(MigrationImportLauncherImpl.EXTRA_SCAN_DESKTOP, false)
    }

    



    private val credentialHandoff: Boolean by lazy {
        intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
    }

    
    private var handoffActivated = false

    @Inject
    lateinit var migrationImportLauncher: MigrationImportLauncher

    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    




    private val loginViewModel: LoginViewModel by viewModels()

    private var promotedSignIn by mutableStateOf<PromotedSignIn>(PromotedSignIn.Idle)

    
    private val pendingSignIn: LoginViewModel.PendingSignIn? by lazy {
        IntentCompat.getParcelableExtra(intent, MigrationImportLauncherImpl.EXTRA_PENDING_SIGN_IN, LoginViewModel.PendingSignIn::class.java)
    }

    
    private var heldSignIn: LoginViewModel.PendingSignIn? = null

    
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

    




    private val legacyHandoff = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            L.w { "[MigrationV2][P2P][Rx] same-device handoff refused or failed resultCode=${result.resultCode}" }
            ToastUtil.show(getString(R.string.migration_p2p_receive_legacy_open_failed))
        }
    }

    
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
        
        
        
        
        if (!credentialHandoff && intent.getStringExtra(EXTRA_ACCOUNT).isNullOrEmpty()) {
            L.w { "[MigrationV2][P2P][Rx] opened without an account — nothing to resume, finishing" }
            finish()
            return
        }
        
        
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        
        
        
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        observeSignIn()

        val composeView = ComposeView(this)
        composeView.setContent {
            DifftTheme {
                val state by viewModel.state.collectAsState()

                
                
                
                
                val terminalResult = when (state) {
                    MigrationP2pReceiveState.Skipped -> MigrationImportLauncher.RESULT_SKIPPED
                    else -> null
                }
                LaunchedEffect(terminalResult) {
                    when {
                        terminalResult == null -> Unit
                        
                        
                        terminalResult == MigrationImportLauncher.RESULT_SKIPPED && credentialHandoff -> openLogin()
                        
                        
                        
                        terminalResult == MigrationImportLauncher.RESULT_SKIPPED && promotedOnDisk() -> {
                            L.w { "[MigrationV2][P2P][Rx] leaving after a promoted import → cold start instead of the in-process login" }
                            restartApp()
                        }
                        else -> finishWith(terminalResult)
                    }
                }

                
                
                val activated = state == MigrationP2pReceiveState.Activated
                LaunchedEffect(activated) {
                    if (activated) restartApp()
                }

                
                
                val committedUnconfirmed = state == MigrationP2pReceiveState.CommittedUnconfirmed
                LaunchedEffect(committedUnconfirmed) {
                    if (committedUnconfirmed) {
                        L.w { "[MigrationV2][P2P][Rx] import committed; tail unconfirmed — cold start to finish it" }
                        restartApp()
                    }
                }

                
                
                
                val promotedForSignIn = state == MigrationP2pReceiveState.Promoted && !credentialHandoff
                LaunchedEffect(promotedForSignIn) {
                    if (promotedForSignIn && promotedSignIn == PromotedSignIn.Idle) signInAfterPromote()
                }

                when {
                    
                    
                    terminalResult != null -> Unit
                    
                    
                    
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

        
        if (savedInstanceState == null) {
            viewModel.onIntent(MigrationP2pReceiveIntent.Start)
        }
    }

    private fun launchLegacySender(rawPayload: String) {
        val intent = Intent(PeerApps.ACTION_HANDOFF_SEND)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
            .putExtra(PeerApps.EXTRA_QR_PAYLOAD, rawPayload)
            
            
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

    
    private suspend fun promotedOnDisk(): Boolean = withContext(Dispatchers.IO) {
        runCatching { RestoreFlagStore(this@MigrationP2pReceiveActivity).currentPhase() }.getOrNull()
            .let { it == RestorePhase.SIGNING_IN || it == RestorePhase.COMPLETED }
    }

    




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

    



    private fun finishPromotedSignIn() {
        lifecycleScope.launch {
            pendingLoginCleanup?.join()
            restartApp()
        }
    }

    
    private fun observeSignIn() {
        loginViewModel.signInLiveData.observe(this) { result ->
            when (result.status) {
                Status.LOADING -> promotedSignIn = PromotedSignIn.Running
                Status.SUCCESS -> {
                    
                    
                    if (promotedSignIn == PromotedSignIn.Done) return@observe
                    
                    
                    
                    
                    migrationImportLauncher.markRestoreSignInComplete()
                    L.i { "[MigrationV2][P2P][Rx] restore-path sign-in succeeded → flag COMPLETED, showing the completion page" }
                    promotedSignIn = PromotedSignIn.Done
                    pendingLoginCleanup = lifecycleScope.launch { pendingLoginStore.clear() }
                }
                Status.ERROR -> {
                    
                    
                    val retryable = result.exception is SignInTransportFailure
                    L.w { "[MigrationV2][P2P][Rx] restore-path sign-in failed retryable=$retryable: ${result.exception?.errorMsg}" }
                    promotedSignIn = PromotedSignIn.Failed(result.exception?.errorMsg, retryable = retryable)
                }
            }
        }
    }

    




    private fun restartApp() {
        handoffActivated = true
        L.i { "[MigrationV2][P2P][Rx] restarting into the migrated account" }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        launch?.let { runCatching { startActivity(it) }.onFailure { e -> L.w { "[MigrationV2][Handoff] relaunch failed: ${e.message}" } } }
        Runtime.getRuntime().exit(0)
    }

    
    
    override fun onStart() {
        super.onStart()
        MigrationTransferStateHolder.setPairingPageVisible(true)
    }

    override fun onStop() {
        MigrationTransferStateHolder.setPairingPageVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        
        
        
        if (credentialHandoff && isFinishing && !handoffActivated) {
            L.i { "[MigrationV2][Handoff] leaving the receive screen — cancelling the receiver" }
            MigrationTransferService.cancel(this)
        } else if (isFinishing && MigrationTransferStateHolder.state.value.isListening()) {
            
            
            L.i { "[MigrationV2][P2P][Rx] leaving the pairing page — cancelling the idle receiver" }
            MigrationTransferService.cancel(this)
        }
        super.onDestroy()
    }

    private fun TransferState.isListening(): Boolean = this is TransferState.WaitingForPeer || this == TransferState.Starting

    companion object {
        
        private const val EXTRA_ACCOUNT = "extra_account"
    }
}
