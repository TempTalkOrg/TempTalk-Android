package com.difft.android.chat.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.SecureSharedPrefsUtil
import com.difft.android.base.utils.appScope
import com.difft.android.base.utils.application
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallManager.EntryPoint
import com.difft.android.call.repo.LCallHttpService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.awaitFirst
import kotlinx.coroutines.withContext
import io.reactivex.rxjava3.schedulers.Schedulers
import org.thoughtcrime.securesms.util.MessageNotificationUtil

class CriticalAlertNotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MessageNotificationUtil.ACTION_CRITICAL_ALERT_CLICK) {
            return
        }

        if (intent.`package` != null && intent.`package` != context.packageName) {
            L.w { "[CriticalAlert] Broadcast not targeted to this package: ${intent.`package`}" }
            return
        }

        val conversationId = intent.getStringExtra("conversation_id")
        if (conversationId.isNullOrEmpty()) {
            L.e { "[CriticalAlert] conversationId is null or empty" }
            return
        }

        L.i { "[CriticalAlert] Notification clicked for conversationId: $conversationId" }

        // 使用 goAsync() 管理 BroadcastReceiver 生命周期，防止内存泄漏
        val pendingResult = goAsync()
        
        // 在协程中处理逻辑
        appScope.launch(Dispatchers.IO) {
            try {
                val messageNotificationUtil = getMessageNotificationUtil(context)
                
                // 1. 检查是否正在通话
                if (LCallActivity.isInCalling()) {
                    L.i { "[CriticalAlert] Currently in call, canceling critical alert notification" }
                    // 关闭当前 critical alert 通知（包括通知、声音以及闪光灯）
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } else {
                    // 2. 如果当前未进行通话，则调用 getCallingList 方法获取最新的会议列表
                    val callService = getCallService(context)
                    val response = callService.getCallingList(SecureSharedPrefsUtil.getToken())
                        .subscribeOn(Schedulers.io())
                        .awaitFirst()

                    if (response.status != 0) {
                        L.e { "[CriticalAlert] getCallingList failed with status: ${response.status}" }
                        // 获取失败，关闭通知
                        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                        withContext(Dispatchers.Main) {
                            jumpToIndexActivity(context)
                        }
                    } else {
                        val calls = response.data?.calls
                        
                        // 3. 如果会议列表为空，则关闭当前 critical alert 通知
                        if (calls.isNullOrEmpty()) {
                            L.i { "[CriticalAlert] Calling list is empty, canceling critical alert notification" }
                            messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                            withContext(Dispatchers.Main) {
                                jumpToIndexActivity(context)
                            }
                        } else {
                            // 4. 如果会议列表不为空，则将当前 critical alert 通知的 conversationId 与会议列表数据中的 conversation 匹配
                            val matchedCall = calls.firstOrNull { call ->
                                call.conversation == conversationId
                            }

                            if (matchedCall == null) {
                                // 5. 如果匹配失败，则关闭当前 critical alert 通知
                                L.i { "[CriticalAlert] No matching call found for conversationId: $conversationId, canceling notification" }
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                                withContext(Dispatchers.Main) {
                                    jumpToIndexActivity(context)
                                }
                            } else {
                                matchedCall.let { data ->
                                    if (data.callName.isNullOrEmpty()) {
                                        L.e { "[CriticalAlert] Call name is null or empty for conversationId: $conversationId" }
                                        when (data.type) {
                                            CallType.ONE_ON_ONE.type -> {
                                                data.callName = LCallManager.getDisplayNameById(data.conversation)
                                            }
                                            CallType.GROUP.type -> {
                                                data.callName = LCallManager.getDisplayGroupNameById(data.conversation)
                                            }
                                        }
                                    }
                                }

                                // 6. 如果匹配成功，则加入会议
                                L.i { "[CriticalAlert] Matching call found, joining call. roomId: ${matchedCall.roomId}, conversationId: $conversationId" }
                                
                                // 关闭通知
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)

                                // 加入会议
                                LCallManager.joinCall(context, matchedCall) { status ->
                                    if (status) {
                                        L.i { "[CriticalAlert] Successfully joined call for conversationId: $conversationId" }
                                    } else {
                                        L.e { "[CriticalAlert] Failed to join call for conversationId: $conversationId" }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "[CriticalAlert] Error handling notification click: ${e.message}, stackTrace: ${e.stackTraceToString()}" }
                // 发生错误时，关闭通知
                try {
                    getMessageNotificationUtil(context).cancelCriticalAlertNotification(conversationId)
                } catch (ex: Exception) {
                    L.e { "[CriticalAlert] Failed to cancel notification after error: ${ex.message}" }
                }
                // 发生错误时，也跳转到主页面，提供更好的用户体验
                withContext(Dispatchers.Main) {
                    jumpToIndexActivity(context)
                }
            } finally {
                // 完成异步操作，释放 BroadcastReceiver
                pendingResult.finish()
            }
        }
    }

    private fun getMessageNotificationUtil(context: Context): MessageNotificationUtil {
        val entryPoint = EntryPointAccessors.fromApplication<MessageNotificationUtil.CriticalAlertReceiverEntryPoint>(
            context.applicationContext
        )
        return entryPoint.messageNotificationUtil()
    }

    private fun getActivityProvider(context: Context): ActivityProvider {
        val entryPoint = EntryPointAccessors.fromApplication<MessageNotificationUtil.CriticalAlertReceiverEntryPoint>(
            context.applicationContext
        )
        return entryPoint.activityProvider()
    }

    private fun getCallService(context: Context): LCallHttpService {
        val entryPoint = EntryPointAccessors.fromApplication<EntryPoint>(
            context.applicationContext
        )
        val callHttpClient = entryPoint.callHttpClient()
        return callHttpClient.getService(LCallHttpService::class.java)
    }

    private fun jumpToIndexActivity(context: Context) {
        try {
            val intent = Intent(context.applicationContext, getActivityProvider(context).getActivityClass(ActivityType.INDEX)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.applicationContext.startActivity(intent)
        } catch (e: Exception) {
            L.e { "[CriticalAlert] jumpToIndexActivity error: ${e.message}"}
        }
    }
}

