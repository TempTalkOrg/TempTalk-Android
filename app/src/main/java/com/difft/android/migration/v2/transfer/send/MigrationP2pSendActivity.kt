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
import com.difft.android.migration.v2.pause.MigrationVerdictRecheck
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.transfer.PendingVerdictStore
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













@AndroidEntryPoint
class MigrationP2pSendActivity : BaseActivity() {

    
    @Inject
    lateinit var readSource: MigrationReadSource

    @Inject
    lateinit var userManager: StorageBoundUserManager

    
    @Inject
    lateinit var handoffGate: MigrationHandoffGate

    @Inject
    lateinit var handoffReconciler: MigrationHandoffReconciler

    @Inject
    lateinit var statusClient: MigrationStatusClient

    @Inject
    lateinit var pendingVerdictStore: PendingVerdictStore

    @Inject
    lateinit var verdictSettlement: SenderVerdictSettlement

    
    @Inject
    lateinit var verdictRecheck: MigrationVerdictRecheck

    
    private val verdicts by lazy { SendScreenVerdicts(pendingVerdictStore, verdictSettlement) }

    private val fromLink: Boolean by lazy { intent.getBooleanExtra(PeerApps.EXTRA_FROM_LINK, false) }

    




    private val legacyInitiated: Boolean by lazy { intent.getBooleanExtra(EXTRA_LEGACY_INITIATED, false) }

    
    private var selfContact by mutableStateOf<ContactorModel?>(null)

    



    private val credentialHandoff: Boolean by lazy {
        intent.getBooleanExtra(PeerApps.EXTRA_CREDENTIAL_HANDOFF, false)
    }

    private val viewModel: MigrationP2pSendViewModel by lazy {
        ViewModelProvider(
            this,
            object : NewInstanceFactory() {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    
                    
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
                        loadPendingVerdict = verdicts::load,
                        clearPendingVerdict = verdicts::clear,
                        probeVerdict = verdictRecheck::probe,
                        settleVerdict = verdicts::settle,
                        
                        deviceReplacement = !fromLink,
                    ) as T
            },
        )[MigrationP2pSendViewModel::class.java]
    }

    
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

    




    private val resumeReceiveLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                L.w { "[MigrationV2][P2P][Tx] new app refused resume-receive resultCode=${result.resultCode}" }
            }
        }

    






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

    




    private fun handoffBlockReason(): String? =
        if (handoffGate.canHandOver()) null
        else com.difft.android.migration.model.MigrationError.CredentialMissing.code

    override fun onResume() {
        super.onResume()
        
        
        viewModel.onScreenResumed()
    }

    override fun onDestroy() {
        
        
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
        
        
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        
        
        
        
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
        
        
        
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                
                
                if (state is MigrationP2pSendState.Done && fromLink) {
                    L.i { "[MigrationV2][P2P][Tx] send done — the Desktop shows the result, leaving the send screen" }
                    finish()
                }
                
                
                if (state is MigrationP2pSendState.Cancelled) {
                    L.i { "[MigrationV2][P2P][Tx] send cancelled — leaving the send screen" }
                    finish()
                }
            }
        }
    }

    



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

    





    private fun launchDeviceCredentialAuth() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        @Suppress("DEPRECATION") 
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

    





    private fun bringPeerToFront() {
        val intent = Intent(PeerApps.ACTION_HANDOFF_RESUME_RECEIVE)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
        runCatching { resumeReceiveLauncher.launch(intent) }
            .onFailure { e -> L.w { "[MigrationV2][P2P][Tx] could not bring the new app forward: ${e.message}" } }
    }

    




    private fun requestFreshPairing() {
        val intent = Intent(PeerApps.ACTION_HANDOFF_START_RECEIVE)
            .setClassName(PeerApps.peerPackage(this), PeerApps.HANDOFF_ACTIVITY)
        runCatching { startReceiveLauncher.launch(intent) }
            .onFailure { e ->
                L.w { "[MigrationV2][P2P][Tx] could not reach the new app's trampoline: ${e.message}" }
                ToastUtil.show(getString(R.string.legacy_migration_open_failed))
            }
    }

    
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
        
        const val EXTRA_LEGACY_INITIATED = "extra_legacy_initiated"

    }
}
