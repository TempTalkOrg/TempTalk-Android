package com.difft.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.auth0.android.jwt.JWT
import com.difft.android.app.TempTalkApplication
import com.difft.android.app.startup.needsIdentityKeyRelogin
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatPopupActivity
import com.difft.android.login.LoginActivity
import com.difft.android.chat.util.NotificationTrampolineActivity
import com.difft.android.ui.DatabaseRecoveryScreen
import com.difft.android.ui.KeyUnavailableScreen
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Recovery circuit-breaker. Bounds consecutive recovery attempts so a permanently
     * unrecoverable DB (dead Keystore, persistently failing delete) terminates in a
     * logout instead of an infinite recovery loop. Backed by a standalone
     * SharedPreferences file — independent of the (possibly corrupt) WCDB main DB.
     */
    private val recoveryState by lazy { DatabaseRecoveryState(this) }

    /**
     * WCDB cold-start recovery / key-loss routing, extracted so it's unit-testable
     * without hitting `Runtime.exit`.
     */
    private val recoveryCoordinator by lazy {
        RecoveryFlowCoordinator(this, wcdb, recoveryState, userManager, logoutManager)
    }

    /**
     * Option Y (no splash library): onCreate does NOT call setContent. The window
     * shows the launch theme's background until the IO integrity probe decides what
     * to render — keeping the main thread free of any DB call during onCreate.
     */
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
        // Re-entry guard: if recovery is already running, a re-delivered intent must
        // not start a second recovery coroutine on the same DB handle.
        if (recoveryCoordinator.recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] processIntent ignored: recovery in progress" }
            return
        }
        lifecycleScope.launch {
            // 1. DB integrity FIRST — corrupt DB (wipe-eligible) vs cipher-key failure
            //    (fail-soft, never wiped) take over the screen; only HEALTHY continues.
            if (!recoveryCoordinator.routeOnDatabaseHealth()) return@launch

            // 2. Healthy DB → existing routing, still off-main for token/identity reads.
            val state = withContext(Dispatchers.IO) {
                val loggedIn = verifyLocalToken()
                // Identity-key consistency guard — only meaningful if logged in.
                // Protects straggler users (pre-1.8.1) whose signal-key-value.db was deleted
                // by TempTalkApplication's cleanupLegacyKeyValueDbIfNeeded.
                val identityMissing = if (loggedIn) {
                    needsIdentityKeyRelogin(userManager.getUserData())
                } else {
                    false
                }
                StartupState(loggedIn, identityMissing)
            }

            when {
                !state.isLoggedIn -> {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                // Identity-key branch runs BEFORE routing — recovering WCDB is meaningless
                // if the identity keys are gone (decrypt would still fail).
                state.needsIdentityRelogin -> {
                    L.w { "[MainActivity] logged in but identity key missing, forcing passive re-login" }
                    FirebaseCrashlytics.getInstance().log("[LegacyCleanup] identity-key-missing relogin")
                    logoutManager.doLogoutWithoutRemoveData()
                    // doLogoutWithoutRemoveData() eventually calls Process.killProcess; code below never runs.
                }
                // Popup branch runs only after integrity + auth + identity-key checks pass.
                // EXTRA_OPEN_POPUP is set by NotificationTrampolineActivity but MainActivity is
                // exported=true, so any installed app can forge it (issue #758). Auth is gated
                // above; tryOpenPopupChat re-validates id format. Falls back to deeplink path
                // (IndexActivity.handleDeeplink runs ValidatorUtil too) if the id is malformed.
                shouldOpenPopupChat() -> {
                    val app = application as? TempTalkApplication
                    // Fail closed: if the app class can't be resolved (should never happen) OR the lock
                    // is required, do NOT open the popup. Queue it for replay after unlock and run a
                    // single-pass lock check (the full check's 1100ms second pass could re-lock right
                    // after a fast unlock+replay). Route to IndexActivity only on a cold start (task
                    // root); otherwise finish to return to the previous top — IndexActivity is
                    // singleTask and would clear it (reset to home).
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

    private data class StartupState(
        val isLoggedIn: Boolean,
        val needsIdentityRelogin: Boolean,
    )

    private fun verifyLocalToken(): Boolean {
        val basicAuth = (userManager.getUserData()?.baseAuth ?: "")
        if (TextUtils.isEmpty(basicAuth)) {
            return false
        }

        val account = userManager.getUserData()?.account
        val token = (userManager.getUserData()?.microToken ?: "")

        // Inconsistent state: basicAuth present but account/token missing (partial logout,
        // migration edge). Treat as unauthenticated — this is a security gate (issue #758).
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(token)) {
            L.w {
                "[MainActivity] verifyLocalToken: basicAuth set but account/token missing, " +
                    "account.empty=${TextUtils.isEmpty(account)} token.empty=${TextUtils.isEmpty(token)}"
            }
            return false
        }

        val jwt = JWT(token)
        val uid = jwt.getClaim("uid").asString()
        return uid.equals(account)
    }

    /**
     * Navigate to IndexActivity, passing all deeplink/share data via Intent.
     */
    private fun navigateToIndexActivity() {
        val newIntent = Intent(this@MainActivity, IndexActivity::class.java)
        
        // Pass deeplink data
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
        
        // Pass scheme URL
        intent.data?.let { newIntent.data = it }
        
        // Handle external share (ACTION_SEND)
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
        
        startActivity(newIntent)
        finish()
    }

    // RecoveryFlowCoordinator.Host — Activity-coupled seams the recovery flow delegates to.

    override val scope: CoroutineScope get() = lifecycleScope

    override fun databaseFileExists(): Boolean = getDatabasePath(WCDB.DATABASE_NAME).exists()

    override fun renderRecoveryScreen() {
        setContent {
            DifftTheme {
                DatabaseRecoveryScreen()
            }
        }
    }

    override fun renderKeyUnavailableScreen() {
        setContent {
            DifftTheme {
                // Retry restarts the process to re-attempt the Keystore read in a fresh process.
                KeyUnavailableScreen(onRetry = { restartApp() })
            }
        }
    }

    override fun showToast(messageResId: Int) {
        ToastUtil.showLong(messageResId)
    }

    /**
     * Restart the process to reinitialize all lazy singletons with fresh DB handles.
     *
     * Uses `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` (issue #725 §9.5): on
     * API ≥ 24 a `startActivity` from some contexts with `CLEAR_TASK` alone throws.
     */
    override fun restartApp() {
        L.i { "[MainActivity][DBRecovery] restarting app" }
        // getLaunchIntentForPackage may return null; passing null to startActivity throws
        // NPE and would skip exit(0), leaving a zombie process with poisoned singletons.
        // Guard the relaunch but ALWAYS kill the process (issue #725 §9.5 semantics).
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        intent?.let { launchIntent -> runCatching { startActivity(launchIntent) }.onFailure { e -> L.w { "[MainActivity][DBRecovery] relaunch failed: ${e.message}" } } }
        Runtime.getRuntime().exit(0)
    }

    /**
     * Check if should open popup chat (indicated by TrampolineActivity).
     */
    private fun shouldOpenPopupChat(): Boolean {
        val openPopup = intent.getBooleanExtra(NotificationTrampolineActivity.EXTRA_OPEN_POPUP, false)
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val isFromMessageNotification = linkCategory == LinkDataEntity.CATEGORY_MESSAGE
        return openPopup && isFromMessageNotification
    }

    /**
     * Try to open popup chat. Returns true on success, false if the id is missing/malformed
     * (so the caller can fall through to the normal startup flow).
     */
    private fun tryOpenPopupChat(): Boolean {
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)

        val popupIntent = when {
            !groupId.isNullOrEmpty() && ValidatorUtil.isGid(groupId) -> {
                Intent(this, GroupChatPopupActivity::class.java).apply {
                    putExtra(GroupChatPopupActivity.INTENT_EXTRA_GROUP_ID, groupId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            !contactId.isNullOrEmpty() && ValidatorUtil.isUid(contactId) -> {
                Intent(this, ChatPopupActivity::class.java).apply {
                    putExtra(ChatPopupActivity.BUNDLE_KEY_CONTACT_ID, contactId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> {
                L.e {
                    "[MainActivity] tryOpenPopupChat: invalid or missing id " +
                        "groupId.length=${groupId?.length ?: 0} contactId.length=${contactId?.length ?: 0}"
                }
                return false
            }
        }

        startActivity(popupIntent)
        return true
    }
}
