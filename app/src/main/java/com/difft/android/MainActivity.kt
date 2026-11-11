package com.difft.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.difft.android.app.TempTalkApplication
import com.difft.android.app.startup.LocalTokenVerifier
import com.difft.android.app.startup.needsIdentityKeyRelogin
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.login.LoginActivity
import com.difft.android.login.VerifyCodeActivity
import com.difft.android.login.entry.PendingLoginStore
import com.difft.android.login.viewmodel.LoginViewModel
import com.difft.android.migration.legacy.LegacyGuideMode
import com.difft.android.migration.legacy.LegacyMigrationGuideActivity
import com.difft.android.migration.v2.handoff.HandoffOffer
import com.difft.android.migration.v2.handoff.HandoffState
import com.difft.android.migration.v2.handoff.MigrationHandoffEntryActivity
import com.difft.android.migration.v2.handoff.MigrationHandoffGate
import com.difft.android.migration.v2.handoff.MigrationStatusClient
import com.difft.android.migration.v2.login.MigrationSuccessActivity
import com.difft.android.migration.v2.login.PostRestoreWarmUp
import com.difft.android.migration.v2.login.PostRestoreWarmUpPage
import com.difft.android.migration.v2.restore.RestoreFlagStore
import com.difft.android.network.auth.AuthTokenProvider
import com.difft.android.chat.util.ChatPopupIntents
import com.difft.android.chat.util.NotificationTrampolineActivity
import com.difft.android.ui.DatabaseRecoveryScreen
import com.difft.android.ui.KeyUnavailableScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.difft.app.database.DatabaseRecoveryState
import org.difft.app.database.WCDB
import org.difft.app.database.wcdb
import util.PendingScreenLockDeeplink
import util.ScreenLockUtil
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : BaseActivity(), RecoveryFlowCoordinator.Host {
    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    @Inject
    lateinit var authTokenProvider: AuthTokenProvider

    
    @Inject
    lateinit var handoffGate: MigrationHandoffGate

    @Inject
    lateinit var statusClient: MigrationStatusClient

    
    @Inject
    lateinit var pendingLoginStore: PendingLoginStore

    @Inject
    lateinit var postRestoreWarmUp: PostRestoreWarmUp

    





    private val recoveryState by lazy { DatabaseRecoveryState(this) }

    



    private val recoveryCoordinator by lazy {
        RecoveryFlowCoordinator(this, wcdb, recoveryState, userManager, logoutManager)
    }

    



    var hostsLockScreen = false
        private set

    




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent()
    }

    private fun processIntent() {
        
        
        if (recoveryCoordinator.recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] processIntent ignored: recovery in progress" }
            return
        }
        lifecycleScope.launch {
            
            
            if (!recoveryCoordinator.routeOnDatabaseHealth()) return@launch

            
            val state = withContext(Dispatchers.IO) {
                val loggedIn = verifyLocalToken()
                
                
                
                val identityMissing = if (loggedIn) {
                    needsIdentityKeyRelogin(userManager.getUserData())
                } else {
                    false
                }
                
                
                
                
                val flagStore = RestoreFlagStore(this@MainActivity)
                val account = userManager.getUserData()?.account.orEmpty()
                
                
                
                val migrationJustCompleted = flagStore.consumeCompleted() ||
                    (loggedIn && flagStore.consumeSigningInFor(account))
                val handoffCompletedHere = userManager.getUserData()?.migrationHandoffCompletedId.orEmpty().isNotEmpty()
                val migratedToNewApp = !loggedIn && (userManager.getUserData()?.migrationHandoffDoneAt ?: 0L) > 0
                val handoffOffer = if (loggedIn || migratedToNewApp) HandoffOffer.NONE else probeHandoffOffer()
                
                
                val pendingAccountSwitch = if (!loggedIn && account.isEmpty()) pendingLoginStore.consume() else null.also { pendingLoginStore.clear() }
                StartupState(
                    isLoggedIn = loggedIn,
                    needsIdentityRelogin = identityMissing,
                    migrationJustCompleted = migrationJustCompleted,
                    migratedViaHandoff = handoffCompletedHere,
                    migratedToNewApp = migratedToNewApp,
                    handoffOffer = handoffOffer,
                    pendingAccountSwitch = pendingAccountSwitch,
                )
            }

            if (state.isLoggedIn && state.migrationJustCompleted) warmUpAfterRestore()

            when {
                
                
                !state.isLoggedIn && state.migratedToNewApp -> {
                    startActivity(LegacyMigrationGuideActivity.createIntent(this@MainActivity, LegacyGuideMode.MIGRATED))
                    finish()
                }
                
                
                !state.isLoggedIn && state.pendingAccountSwitch != null -> {
                    L.i { "[MainActivity] held sign-in after an account switch → migration chooser" }
                    startActivities(
                        arrayOf(
                            Intent(this@MainActivity, LoginActivity::class.java),
                            Intent(this@MainActivity, VerifyCodeActivity::class.java)
                                .putExtras(VerifyCodeActivity.createResumeBundle(state.pendingAccountSwitch)),
                        ),
                    )
                    finish()
                }
                
                
                !state.isLoggedIn && state.handoffOffer != HandoffOffer.NONE -> {
                    L.i { "[MigrationV2][Handoff] startup offer=${state.handoffOffer}" }
                    startActivity(MigrationHandoffEntryActivity.createIntent(this@MainActivity))
                    finish()
                }
                !state.isLoggedIn -> {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                
                
                state.needsIdentityRelogin -> {
                    L.w { "[MainActivity] logged in but identity key missing, forcing passive re-login" }
                    logoutManager.doLogoutWithoutRemoveData()
                    
                }
                
                
                
                state.migrationJustCompleted && state.migratedViaHandoff -> {
                    L.i { "[MainActivity] handoff completed on the previous launch → migration success page" }
                    startActivity(MigrationSuccessActivity.createIntent(this@MainActivity, credentialHandoff = true))
                    finish()
                }
                
                
                
                
                
                shouldOpenPopupChat() -> {
                    val app = application as? TempTalkApplication
                    
                    
                    
                    
                    
                    
                    if (app == null || app.isScreenLockRequiredOrShowing()) {
                        L.i { "[MainActivity] popup gated by app lock, queued for replay after unlock" }
                        PendingScreenLockDeeplink.offer(intent)
                        if (isTaskRoot) {
                            startActivity(Intent(this@MainActivity, IndexActivity::class.java))
                        }
                        app?.triggerScreenLockCheckOnce()
                        finish()
                    } else if (tryOpenPopupChat()) {
                        finish()
                    } else {
                        L.w { "[MainActivity] popup intent rejected (invalid id), falling back to deeplink path" }
                        navigateToIndexActivity()
                    }
                }
                else -> navigateToIndexActivity()
            }
        }
    }

    




    private suspend fun warmUpAfterRestore() {
        L.i { "[MainActivity] restore completed on the previous launch → warming up contacts and groups first" }
        setContent { DifftTheme { PostRestoreWarmUpPage() } }
        postRestoreWarmUp.run()
    }

    private data class StartupState(
        val isLoggedIn: Boolean,
        val needsIdentityRelogin: Boolean,
        val migrationJustCompleted: Boolean = false,
        
        val migratedViaHandoff: Boolean = false,
        
        val migratedToNewApp: Boolean = false,
        val handoffOffer: HandoffOffer = HandoffOffer.NONE,
        
        val pendingAccountSwitch: LoginViewModel.PendingSignIn? = null,
    )

    



    private suspend fun probeHandoffOffer(): HandoffOffer =
        withTimeoutOrNull(HANDOFF_PROBE_TIMEOUT_MS) {
            if (!handoffGate.canReceive()) return@withTimeoutOrNull HandoffOffer.NONE
            when (val reply = statusClient.queryStatus()) {
                MigrationStatusClient.PeerStatusReply.TooOld -> HandoffOffer.PEER_TOO_OLD
                MigrationStatusClient.PeerStatusReply.Unavailable -> HandoffOffer.NONE
                is MigrationStatusClient.PeerStatusReply.Ok ->
                    if (reply.state == HandoffState.READY) {
                        HandoffOffer.OFFER
                    } else {
                        HandoffOffer.NONE
                    }
            }
        } ?: HandoffOffer.NONE

    private fun verifyLocalToken(): Boolean = LocalTokenVerifier.isLocalTokenValid(
        baseAuth = userManager.getUserData()?.baseAuth,
        account = userManager.getUserData()?.account,
        token = authTokenProvider.peek(),
    )

    



    private fun navigateToIndexActivity() {
        if (isLaunchedFromHistory()) {
            L.i { "[MainActivity] relaunched from recents, deeplink and share extras dropped" }
            startActivity(Intent(this@MainActivity, IndexActivity::class.java))
            finish()
            return
        }
        val newIntent = Intent(this@MainActivity, IndexActivity::class.java)
        
        
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val pushData = intent.getStringExtra("pushData")
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)
        
        if (linkCategory != -1) {
            newIntent.putExtra(LinkDataEntity.LINK_CATEGORY, linkCategory)
        }
        pushData?.let { newIntent.putExtra("pushData", it) }
        groupId?.let { newIntent.putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, it) }
        contactId?.let { newIntent.putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, it) }
        
        com.difft.android.setting.notificationtest.NotificationTestReturnRoute.forward(intent, newIntent)

        
        intent.data?.let { newIntent.data = it }
        
        
        if (this.intent?.action == Intent.ACTION_SEND) {
            try {
                newIntent.action = intent.action
                newIntent.type = intent.type
                
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    newIntent.putExtra(Intent.EXTRA_TEXT, text)
                }
                
                if (intent.clipData != null) {
                    newIntent.clipData = intent.clipData
                    newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    
                    uri?.let { 
                        val clipData = ClipData.newUri(contentResolver, "shared_content", it)
                        newIntent.clipData = clipData
                        newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        newIntent.putExtra(Intent.EXTRA_STREAM, it)
                    }
                }
            } catch (e: Exception) {
                L.e { "[MainActivity] Exception handling share Intent: ${e.stackTraceToString()}" }
            }
        }

        try {
            startActivity(newIntent)
        } catch (e: SecurityException) {
            
            
            
            
            L.w { "[MainActivity] share uri grant rejected, entering IndexActivity without share payload" }
            ToastUtil.showLong(R.string.share_file_access_error)
            startActivity(Intent(this@MainActivity, IndexActivity::class.java))
        }
        finish()
    }

    

    override val scope: CoroutineScope get() = lifecycleScope

    override fun databaseFileExists(): Boolean = getDatabasePath(WCDB.DATABASE_NAME).exists()

    override fun renderRecoveryScreen() {
        setContent {
            DifftTheme {
                DatabaseRecoveryScreen()
            }
        }
        hostLockScreen()
    }

    override fun renderKeyUnavailableScreen() {
        setContent {
            DifftTheme {
                
                KeyUnavailableScreen(onRetry = { restartApp() })
            }
        }
        hostLockScreen()
    }

    
    private fun hostLockScreen() {
        hostsLockScreen = true
        (application as? TempTalkApplication)?.onLockHostReady(this)
    }

    override fun showToast(messageResId: Int) {
        ToastUtil.showLong(messageResId)
    }

    





    override fun restartApp() {
        L.i { "[MainActivity][DBRecovery] restarting app" }
        
        
        
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        intent?.let { launchIntent -> runCatching { startActivity(launchIntent) }.onFailure { e -> L.w { "[MainActivity][DBRecovery] relaunch failed: ${e.message}" } } }
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        
        const val HANDOFF_PROBE_TIMEOUT_MS = 2_000L
    }

    



    private fun isLaunchedFromHistory(): Boolean =
        intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    


    private fun shouldOpenPopupChat(): Boolean {
        if (isLaunchedFromHistory()) return false
        val openPopup = intent.getBooleanExtra(NotificationTrampolineActivity.EXTRA_OPEN_POPUP, false)
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val isFromMessageNotification = linkCategory == LinkDataEntity.CATEGORY_MESSAGE
        return openPopup && isFromMessageNotification
    }

    



    private fun tryOpenPopupChat(): Boolean {
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)

        val popupIntent = ChatPopupIntents.create(this, contactId, groupId) ?: run {
            L.e {
                "[MainActivity] tryOpenPopupChat: invalid or missing id " +
                    "groupId.length=${groupId?.length ?: 0} contactId.length=${contactId?.length ?: 0}"
            }
            return false
        }

        startActivity(popupIntent)
        return true
    }
}
