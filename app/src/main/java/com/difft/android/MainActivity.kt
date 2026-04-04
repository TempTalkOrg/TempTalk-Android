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
import com.difft.android.base.BaseActivity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatPopupActivity
import com.difft.android.login.LoginActivity
import org.thoughtcrime.securesms.util.NotificationTrampolineActivity
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
        // Check if should open popup chat (indicated by TrampolineActivity via EXTRA_OPEN_POPUP)
        if (shouldOpenPopupChat()) {
            openPopupChat()
            finish()
            return
        }

        lifecycleScope.launch {
            val (isLoggedIn, needsRecovery) = withContext(Dispatchers.IO) {
                val loggedIn = verifyLocalToken()
                val recovery = if (loggedIn) recoveryPreferences.isRecoveryNeeded() else false
                loggedIn to recovery
            }
            
            if (isLoggedIn) {
                if (needsRecovery) {
                    performDatabaseRecovery()
                } else {
                    navigateToIndexActivity()
                }
            } else {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun verifyLocalToken(): Boolean {
        val basicAuth = SecureSharedPrefsUtil.getBasicAuth()
        return if (TextUtils.isEmpty(basicAuth)) {
            false
        } else {
            val account = userManager.getUserData()?.account
            val token = SecureSharedPrefsUtil.getToken()

            if (!TextUtils.isEmpty(account) && !TextUtils.isEmpty(token)) {
                val jwt = JWT(token)
                val uid = jwt.getClaim("uid").asString()
                uid.equals(account)
            } else {
                true
            }
        }
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
     * Open popup chat activity.
     */
    private fun openPopupChat() {
        val groupId = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
        val contactId = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)
        
        if (groupId.isNullOrEmpty() && contactId.isNullOrEmpty()) {
            L.e { "[MainActivity] openPopupChat: No groupId or contactId" }
            return
        }
        
        val popupIntent = if (!groupId.isNullOrEmpty()) {
            Intent(this, GroupChatPopupActivity::class.java).apply {
                putExtra(GroupChatPopupActivity.INTENT_EXTRA_GROUP_ID, groupId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(this, ChatPopupActivity::class.java).apply {
                putExtra(ChatPopupActivity.BUNDLE_KEY_CONTACT_ID, contactId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        
        startActivity(popupIntent)
    }
}
