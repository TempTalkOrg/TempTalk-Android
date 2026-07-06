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
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.DatabaseRecoveryState
import org.difft.app.database.DbHealth
import org.difft.app.database.WCDB
import org.difft.app.database.wcdb
import util.ScreenLockUtil
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : BaseActivity() {
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
     * Re-entry guard: a second [processIntent] (e.g. from [onNewIntent]) must NOT
     * launch a second concurrent recovery — two coroutines racing
     * `retrieve`/`close`/`delete` on the same handle can crash natively.
     */
    @Volatile
    private var recoveryInProgress = false

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
        if (recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] processIntent ignored: recovery in progress" }
            return
        }
        lifecycleScope.launch {
            // 1. DB integrity FIRST — off main thread. A corrupt DB routes to recovery
            //    before any auth/identity/routing work.
            if (!checkDatabaseIntegrity()) {
                showRecoveryUI()
                return@launch
            }
            // Healthy DB → clear any accumulated recovery attempts so a normal launch
            // never lets the circuit-breaker count drift upward.
            recoveryState.reset()

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
                    if (tryOpenPopupChat()) {
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

    /**
     * Probe DB integrity off the main thread. Delegates to [wcdb.probeHealthy] (the
     * single owner of the PRAGMA probe + all catch logic; it also sets `wcdb.dbCorrupted`).
     */
    private suspend fun checkDatabaseIntegrity(): Boolean = withContext(Dispatchers.IO) {
        // A brand-new install or a logged-out user has no DB file. Treat absence as
        // HEALTHY: probing would force-create a fresh (empty) encrypted DB and, on a
        // broken Keystore, would trap an already-logged-out user on the recovery screen.
        // The normal login/identity/routing flow handles the no-DB case correctly.
        if (!getDatabasePath(WCDB.DATABASE_NAME).exists()) {
            L.i { "[MainActivity][DBRecovery] no DB file present, treating as healthy" }
            return@withContext true
        }
        wcdb.probeHealthy() == DbHealth.HEALTHY
    }

    /**
     * Render the full-screen recovery UI (Compose) and kick off recovery.
     */
    private fun showRecoveryUI() {
        // Authoritative re-entry guard. showRecoveryUI() always runs on the main thread
        // (setContent requires it) and is the single funnel into recovery, so this
        // check-then-set is atomic: two concurrent processIntent() coroutines that both
        // passed the early `recoveryInProgress` check (set happens after their IO probe)
        // still serialize here — only the first starts recovery, the second bails out.
        if (recoveryInProgress) {
            L.w { "[MainActivity][DBRecovery] showRecoveryUI ignored: recovery already in progress" }
            return
        }
        recoveryInProgress = true
        L.i { "[MainActivity][DBRecovery] showing recovery UI" }
        setContent {
            DifftTheme {
                DatabaseRecoveryScreen()
            }
        }
        performRecovery()
    }

    /**
     * Try backup-restore first; restart on success (poisoned lazy singletons must be
     * re-created). On failure, fall through to a destructive reset + resync + restart.
     *
     * Circuit-breaker: the attempt count is incremented BEFORE any recovery work. Once
     * it exceeds [DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS] the DB is treated as
     * permanently unrecoverable (dead Keystore / failing storage) and we terminate the
     * loop with a logout instead of retrying forever.
     */
    private fun performRecovery() = lifecycleScope.launch(Dispatchers.IO) {
        // recoveryInProgress is already set by showRecoveryUI() on the main thread.
        val attempt = recoveryState.incrementAndGet()
        if (attempt > DatabaseRecoveryState.MAX_RECOVERY_ATTEMPTS) {
            L.e { "[MainActivity][DBRecovery] giving up after $attempt attempts, forcing logout" }
            wcdb.markCorrupted()
            withContext(Dispatchers.Main) {
                ToastUtil.showLong(getString(R.string.db_recovery_unrecoverable_message))
                logoutManager.doLogout()
            }
            return@launch
        }

        L.i { "[MainActivity][DBRecovery] starting recovery attempt=$attempt" }
        if (tryBackupRecovery()) {
            withContext(Dispatchers.Main) { restartApp() }
            return@launch
        }
        resetDatabaseAndResync()
    }

    /**
     * Attempt WCDB auto-backup recovery on the cipher-configured singleton handle.
     *
     * ARCH-CRIT-2: `retrieve()` returning a score > 0 (fraction of data repaired) is the
     * SOLE success criterion — recovering some data beats wiping everything. The
     * post-retrieve `SELECT 1` is a diagnostic-only smoke check wrapped in [runCatching]
     * — a throw there must NOT downgrade a successful restore to "failed" (which would
     * trigger the destructive wipe and permanently destroy a recoverable backup).
     */
    private fun tryBackupRecovery(): Boolean = try {
        // retrieve() returns a fraction in [0,1]: the percentage of data repaired.
        // <= 0 means recovery failed; > 0 means we recovered at least some data
        // (better than wiping everything). REUSES the cipher-configured singleton handle.
        val score = wcdb.db.retrieve(null)
        if (score > 0) {
            runCatching { wcdb.db.execute("SELECT 1") }
                .onFailure { L.w { "[MainActivity][DBRecovery] post-retrieve smoke check threw (non-fatal, restore kept): ${it.message}" } }
            L.i { "[MainActivity][DBRecovery] backup recovery ok score=$score" }
            true
        } else {
            L.w { "[MainActivity][DBRecovery] no backup material (score=$score)" }
            false
        }
    } catch (e: Exception) {
        L.w { "[MainActivity][DBRecovery] backup recovery (retrieve) failed: ${e.message}" }
        false
    }

    /**
     * Delete the corrupt DB and reset the server-resync gates, then restart.
     *
     * RACE-2: flip `wcdb.dbCorrupted = true` (via [WCDB.markCorrupted]) BEFORE `close()`
     * so any straggler background consumer fast-skips the closing handle.
     *
     * The three resync gates (`syncedContactsV4` / `syncedGroupAndMembers` /
     * `directoryVersionForContactors`) live in `app_state` (DataStore), NOT the WCDB main
     * DB, so wiping the DB does not reset them — we set them explicitly so IndexActivity
     * re-pulls contacts + groups. NOTE: local message history + already-received media are
     * permanently lost by the wipe (the server message queue is ephemeral, no backfill).
     */
    private suspend fun resetDatabaseAndResync() {
        wcdb.markCorrupted() // RACE-2: flip flag BEFORE close()
        // Decouple close from delete: when the Keystore cipher is dead, touching
        // `wcdb.db` rethrows the cached key exception, so close() can throw. If close
        // and delete shared a try, that throw would skip the delete and the corrupt
        // file would never be removed. deleteDatabaseFile() goes through
        // context.deleteDatabase() and does NOT touch the db handle, so it must run
        // unconditionally regardless of close()'s outcome.
        runCatching { wcdb.db.close() }
            .onFailure { L.w { "[MainActivity][DBRecovery] db close failed (continuing to delete): ${it.message}" } }
        wcdb.deleteDatabaseFile()
        try {
            userManager.update {
                syncedContactsV4 = false          // force ContactorUtil.fetchAndSaveContactors re-pull
                syncedGroupAndMembers = false      // force GroupUtil.syncAllGroupAndAllGroupMembers re-pull
                directoryVersionForContactors = 0  // reset the directory cursor so the version gate can't skip the pull
            }
            L.i { "[MainActivity][DBRecovery] reset done; sync flags cleared (contacts/groups/dirVersion)" }
        } catch (e: Exception) {
            L.e { "[MainActivity][DBRecovery] reset failed: ${e.stackTraceToString()}" }
        }
        withContext(Dispatchers.Main) {
            ToastUtil.showLong(getString(R.string.db_recovery_resync_message))
            restartApp()
        }
    }

    /**
     * Restart the process to reinitialize all lazy singletons with fresh DB handles.
     *
     * Uses `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK` (issue #725 §9.5): on
     * API ≥ 24 a `startActivity` from some contexts with `CLEAR_TASK` alone throws.
     */
    private fun restartApp() {
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
