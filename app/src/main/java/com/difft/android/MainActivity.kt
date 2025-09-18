package com.difft.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
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
import com.difft.android.chat.data.PushCustomContent
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.login.LoginActivity
import com.google.gson.Gson
import com.kongzue.dialogx.dialogs.MessageDialog
import com.kongzue.dialogx.dialogs.TipDialog
import com.kongzue.dialogx.dialogs.WaitDialog
import com.kongzue.dialogx.interfaces.DialogLifecycleCallback
import com.kongzue.dialogx.interfaces.OnBindView
import com.tencent.wcdb.core.Database
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.DatabaseRecoveryPreferences
import org.difft.app.database.WCDB.Companion.DATABASE_NAME
import util.ScreenLockUtil
import org.thoughtcrime.securesms.util.AppForegroundObserver
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
        ScreenLockUtil.appIsForegroundBeforeHandleDeeplink = AppForegroundObserver.isForegrounded()
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
        startActivity(Intent(this@MainActivity, IndexActivity::class.java).apply {
            if (this@MainActivity.intent?.action == Intent.ACTION_SEND) {
                data = intent.data
                this.putExtras(intent)
                this.action = intent.action
                this.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
        finish()
    }

    /**
     * 执行数据库恢复
     */
    private suspend fun performDatabaseRecovery() {
        var messageDialog: MessageDialog? = null
        try {
            // 创建自定义进度视图
            val progressView = LayoutInflater.from(this@MainActivity).inflate(R.layout.view_database_recovery_progress, null)
            val progressBar = progressView.findViewById<ProgressBar>(R.id.pb_recovery_progress)
            val messageText = progressView.findViewById<TextView>(R.id.tv_recovery_message)

            // 设置初始进度
            progressBar.progress = 0
            messageText.text = getString(R.string.database_recovery_progress, 0)

            // 创建MessageDialog并设置自定义视图
            messageDialog = MessageDialog.show(getString(R.string.database_recovery_title), null)
                .setCustomView(object : OnBindView<MessageDialog>(progressView) {
                    override fun onBind(dialog: MessageDialog, v: View) {
                    }
                })
                .setCancelable(false)

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
                            messageDialog.dismiss()

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
                TipDialog.show(getString(R.string.database_recovery_failed), WaitDialog.TYPE.ERROR, 3000)
                    .dialogLifecycleCallback = object : DialogLifecycleCallback<WaitDialog?>() {
                    override fun onDismiss(dialog: WaitDialog?) {
                        logoutManager.doLogout()
                    }
                }
            } else {
                // 增加失败计数，但清除恢复标记让下次重新检测
                L.i { "[MainActivity][WCDB] Database recovery failed, attempt ${failureCount + 1}/3" }
                recoveryPreferences.incrementRecoveryFailureCount()

                TipDialog.show(getString(R.string.database_recovery_retry_tip, failureCount + 1), WaitDialog.TYPE.WARNING, 2000)
                    .dialogLifecycleCallback = object : DialogLifecycleCallback<WaitDialog?>() {
                    override fun onDismiss(dialog: WaitDialog?) {
                        navigateToIndexActivity()
                    }
                }
            }
        }
    }
}

