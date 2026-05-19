package com.difft.android

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.auth0.android.jwt.JWT
import com.difft.android.app.startup.needsIdentityKeyRelogin
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatPopupActivity
import com.difft.android.login.LoginActivity
import com.difft.android.chat.util.NotificationTrampolineActivity
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.tencent.wcdb.core.Database
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.DatabaseRecoveryPreferences
import org.difft.app.database.WCDB.Companion.DATABASE_NAME
import util.ScreenLockUtil
import javax.inject.Inject


@AndroidEntryPoint
class MainActivity : BaseActivity() {
    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var logoutManager: LogoutManager

    @Inject
    lateinit var recoveryPreferences: DatabaseRecoveryPreferences

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
        lifecycleScope.launch {
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
                val recovery = if (loggedIn) recoveryPreferences.isRecoveryNeeded() else false

                StartupState(loggedIn, identityMissing, recovery)
            }

            when {
                !state.isLoggedIn -> {
                    startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                    finish()
                }
                // Identity-key branch runs BEFORE recovery — recovering WCDB is meaningless
                // if the identity keys are gone (decrypt would still fail).
                state.needsIdentityRelogin -> {
                    L.w { "[MainActivity] logged in but identity key missing, forcing passive re-login" }
                    FirebaseCrashlytics.getInstance().log("[LegacyCleanup] identity-key-missing relogin")
                    logoutManager.doLogoutWithoutRemoveData()
                    // doLogoutWithoutRemoveData() eventually calls Process.killProcess; code below never runs.
                }
                state.needsRecovery -> performDatabaseRecovery()
                // Popup branch runs only after auth + identity-key + recovery checks pass.
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
        val needsRecovery: Boolean,
    )

    private fun verifyLocalToken(): Boolean {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        if (TextUtils.isEmpty(basicAuth)) {
            return false
        }

        val account = userManager.getUserData()?.account
        val token = SecureSharedPrefsUtil.getToken()

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
     * Perform database recovery.
     */
    private suspend fun performDatabaseRecovery() {
        var messageDialog: ComposeDialog? = null
        var progressBar: ProgressBar? = null
        var messageText: TextView? = null

        try {
            messageDialog = ComposeDialogManager.showMessageDialog(
                context = this@MainActivity,
                title = getString(R.string.database_recovery_title),
                message = "",
                cancelable = false,
                showCancel = false,
                layoutId = R.layout.view_database_recovery_progress,
                onViewCreated = { view ->
                    progressBar = view.findViewById<ProgressBar>(R.id.pb_recovery_progress)
                    messageText = view.findViewById<TextView>(R.id.tv_recovery_message)
                    progressBar?.progress = 0
                    messageText?.text = getString(R.string.database_recovery_progress, 0)
                }
            )

            withContext(Dispatchers.IO) {
                val path = getDatabasePath(DATABASE_NAME).absolutePath
                val database = Database(path)

                database.retrieve { percentage, _ ->
                    val progress = (percentage * 100).toInt()
                    val message = getString(R.string.database_recovery_progress, progress)

                    Handler(Looper.getMainLooper()).post {
                        if (percentage >= 1.0) {
                            messageDialog?.dismiss()
                            recoveryPreferences.clearRecoveryFlag()
                            navigateToIndexActivity()
                        } else {
                            progressBar?.progress = progress
                            messageText?.text = message
                        }
                    }
                    true
                }
            }
        } catch (e: Exception) {
            L.e { "[MainActivity] Database recovery exception: ${e.stackTraceToString()}" }
            messageDialog?.dismiss()

            val failureCount = recoveryPreferences.getRecoveryFailureCount()

            if (failureCount >= 3) {
                ToastUtil.showLong(getString(R.string.database_recovery_failed))
                logoutManager.doLogout()
            } else {
                recoveryPreferences.incrementRecoveryFailureCount()
                ToastUtil.showLong(getString(R.string.database_recovery_retry_tip, failureCount + 1))
                navigateToIndexActivity()
            }
        }
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
