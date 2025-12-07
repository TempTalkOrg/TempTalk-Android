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
import com.difft.android.base.utils.AppScheme
import com.difft.android.base.utils.DeeplinkUtils
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.data.PushCustomContent
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.login.LoginActivity
import com.google.gson.Gson
import com.tencent.wcdb.core.Database
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.DatabaseRecoveryPreferences
import org.difft.app.database.WCDB.Companion.DATABASE_NAME
import org.thoughtcrime.securesms.util.AppForegroundObserver
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

        lifecycleScope.launch {
            val (isLoggedIn, needsRecovery) = withContext(Dispatchers.IO) {
                val loggedIn = verifyLocalToken()
                val recovery = if (loggedIn) recoveryPreferences.isRecoveryNeeded() else false
                loggedIn to recovery
            }
            L.i { "[MainActivity] isLoggedIn: $isLoggedIn, needsRecovery: $needsRecovery" }
            if (isLoggedIn) {
                if (needsRecovery) {
                    performDatabaseRecovery()
                } else {
                    // 用户已登录情况下处理deeplink
                    handleDeepLink()
                    navigateToIndexActivity()
                }
            } else {
                startActivity(Intent(this@MainActivity, LoginActivity::class.java))
                finish()
            }
        }
    }

    private fun handleDeepLink() {
//        if (!checkIntent(this)) return
        var linkDataEntity: LinkDataEntity? = null
        val linkCategory = intent.getIntExtra(LinkDataEntity.LINK_CATEGORY, -1)
        val pushData = intent.getStringExtra("pushData")
        if (!TextUtils.isEmpty(pushData)) {//push
            try {
                val pushCustomContent = Gson().fromJson(pushData, PushCustomContent::class.java)
                L.d { "BH_Lin pushCustomContent: $pushCustomContent" }
                linkDataEntity = LinkDataEntity(
                    category = LinkDataEntity.CATEGORY_PUSH,
                    gid = pushCustomContent.gid,
                    uid = pushCustomContent.uid,
                    uri = null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (intent.data != null) {//scheme
            intent.data?.let {
                // 获取URI的scheme部分
                val scheme = it.scheme
                // 检查scheme是否符合预期
                if (scheme != null && AppScheme.allSchemes.contains(scheme)) {
                    linkDataEntity = LinkDataEntity(LinkDataEntity.CATEGORY_SCHEME, null, null, it)
                } else {
                    L.w { "[Deeplink] Unexpected scheme: $scheme" }
                }
            }
        } else {
            val gid: String? = intent.getStringExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID)
            val uid: String? = intent.getStringExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID)
            if (linkCategory != -1) {
                linkDataEntity = LinkDataEntity(linkCategory, gid, uid, null)
            }
        }

        linkDataEntity?.let {
            L.d { "[Deeplink] Emit Deeplink:$it" }
            DeeplinkUtils.emitDeeplink(it)
        }
    }


//    private fun checkIntent(activity: Activity): Boolean {
//        val referrer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
//            activity.referrer
//        } else {
//            null
//        }
//        if (referrer != null) {
//            val host = referrer.host //获取intent发送者身份
//            if (!TextUtils.isEmpty(host)) {
//                val packageName = activity.packageName //获取APP包名
//                return host == packageName
//            }
//        }
//        return false
//    }

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
                //验证token中uid与当前APP用户id是否一致
                uid.equals(account)
            } else {
                true
            }
        }
    }

    /**
     * 跳转到IndexActivity
     */
    private fun navigateToIndexActivity() {
        val newIntent = Intent(this@MainActivity, IndexActivity::class.java)
        
        if (this.intent?.action == Intent.ACTION_SEND) {
            try {
                newIntent.action = intent.action
                newIntent.type = intent.type
                
                // Handle text sharing
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    newIntent.putExtra(Intent.EXTRA_TEXT, text)
                }
                
                // Use original Intent's ClipData if exists (more reliable)
                if (intent.clipData != null) {
                    L.d { "[MainActivity] Using original Intent's ClipData for URI permission transfer" }
                    newIntent.clipData = intent.clipData
                    newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    // If no ClipData, try to create from EXTRA_STREAM
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    
                    uri?.let { 
                        L.d { "[MainActivity] Creating ClipData from EXTRA_STREAM" }
                        val clipData = ClipData.newUri(contentResolver, "shared_content", it)
                        newIntent.clipData = clipData
                        newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        
                        // Keep EXTRA_STREAM for compatibility
                        newIntent.putExtra(Intent.EXTRA_STREAM, it)
                    }
                }
                
            } catch (e: Exception) {
                L.e { "[MainActivity] Exception handling share Intent: ${e.stackTraceToString()}" }
                e.printStackTrace()
            }
        }
        
        startActivity(newIntent)
        finish()
    }

    /**
     * 执行数据库恢复
     */
    private suspend fun performDatabaseRecovery() {
        var messageDialog: ComposeDialog? = null
        var progressBar: ProgressBar? = null
        var messageText: TextView? = null

        try {
            // 创建MessageDialog并设置自定义视图
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

                // 使用WCDB的retrieve方法从损坏的数据库中恢复数据
                database.retrieve { percentage, _ ->
                    L.d { "[MainActivity][WCDB] Database recovery progress: $percentage" }
                    val progress = (percentage * 100).toInt()
                    val message = getString(R.string.database_recovery_progress, progress)

                    // 使用Handler在主线程更新UI
                    Handler(Looper.getMainLooper()).post {
                        if (percentage >= 1.0) {
                            L.i { "[MainActivity][WCDB] Database recovery completed successfully" }
                            messageDialog?.dismiss()

                            recoveryPreferences.clearRecoveryFlag()

                            navigateToIndexActivity()
                        } else {
                            progressBar?.progress = progress
                            messageText?.text = message
                        }
                    }
                    true // 继续恢复
                }
            }
        } catch (e: Exception) {
            L.e { "[MainActivity][WCDB] Database recovery exception: ${e.stackTraceToString()}" }

            // 关闭对话框
            messageDialog?.dismiss()

            // 每次失败后清除恢复标记，让下次启动时重新检测
            // 但记录失败次数，避免无限循环
            val failureCount = recoveryPreferences.getRecoveryFailureCount()

            if (failureCount >= 3) {
                L.w { "[MainActivity][WCDB] Database recovery failed too many times, clearing all data and requiring re-login" }
                ToastUtil.showLong(getString(R.string.database_recovery_failed))
                logoutManager.doLogout()
            } else {
                // 增加失败计数，但清除恢复标记让下次重新检测
                L.i { "[MainActivity][WCDB] Database recovery failed, attempt ${failureCount + 1}/3" }
                recoveryPreferences.incrementRecoveryFailureCount()

                ToastUtil.showLong(getString(R.string.database_recovery_retry_tip, failureCount + 1))
                navigateToIndexActivity()
            }
        }
    }
}

