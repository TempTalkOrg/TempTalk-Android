package com.difft.android.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.call.CallType
import com.difft.android.base.call.LCallConstants
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.manager.CriticalAlertManager
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.state.CriticalAlertCallGate
import com.difft.android.call.state.CriticalAlertStateManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.call.ui.alert.CriticalAlertFullScreen
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.chat.util.MessageNotificationUtil
import javax.inject.Inject
import kotlin.collections.any
import kotlin.collections.orEmpty

@AndroidEntryPoint
class CriticalAlertActivity: ComponentActivity() {

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var onGoingCallStateManager: OnGoingCallStateManager

    @Inject
    lateinit var criticalAlertStateManager: CriticalAlertStateManager

    @Inject
    lateinit var criticalAlertCallGate: CriticalAlertCallGate

    @Inject
    lateinit var criticalAlertManager: CriticalAlertManager

    @Inject
    lateinit var contactorCacheManager: ContactorCacheManager

    @Inject
    lateinit var activityProvider: ActivityProvider

    @Inject
    lateinit var groupRepo: GroupRepo

    private val mySelfId: String by lazy {
        globalServices.myId
    }

    @ChativeHttpClientModule.Call
    @Inject
    lateinit var httpClient: ChativeHttpClient
    private val callService by lazy {
        httpClient.getService(LCallHttpService::class.java)
    }
    
    private var autoFinishJob: Job? = null

    
    private var handledForCurrentCall = false

    
    
    private var finishedForEndedRoom = false

    
    private var ownNotificationId: Int? = null
    private var ownConversationId: String? = null
    private var receiverRegistered = false
    
    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        L.i { "[CriticalAlert] onCreate" }

        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }


        val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        if (conversationId.isNullOrEmpty()) {
            L.e { "[CriticalAlert] conversationId is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        if (title.isNullOrEmpty()) {
            L.e { "[CriticalAlert] title is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        if (message.isNullOrEmpty()) {
            L.e { "[CriticalAlert] message is null or empty" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val notificationId = intent.getIntExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, -1)
        if (notificationId == -1) {
            L.e { "[CriticalAlert] notificationId is error" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        val isNotification = intent.getBooleanExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, false)

        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID)

        
        
        
        registerCriticalAlertReceiver()
        ownNotificationId = notificationId
        ownConversationId = conversationId
        if (criticalAlertManager.publishPageAndCheckSilence(notificationId, conversationId, roomId) &&
            intent.action != LCallConstants.CRITICAL_ALERT_ACTION_CLICKED
        ) {
            undoSilencedAlert(notificationId, roomId)
            
            if (criticalAlertCallGate.isForCurrentCall(conversationId, roomId)) {
                handledForCurrentCall = true
            } else {
                finishedForEndedRoom = true
            }
            criticalAlertStateManager.setPageClosingSilently(notificationId)
            finish()
            return
        }

        criticalAlertStateManager.setConversationId(conversationId)
        criticalAlertStateManager.setIsShowing(true)

        when(intent.action) {
            LCallConstants.CRITICAL_ALERT_ACTION_CLICKED -> {
                handleJoinAction(conversationId, roomId)
                showBlankTransparentUI()
            }
            else -> {
                showCriticalAlertUI(conversationId, title, message, roomId)
            }
        }

        registerOnBackPressedHandler()

        scheduleAutoFinish()

        
        if (!isNotification) criticalAlertManager.playSoundAndFlashLight(conversationId, notificationId, roomId)
    }

    private fun showBlankTransparentUI() {
        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
            )
        }
    }

    private fun showCriticalAlertUI(conversationId: String, title: String, message: String, roomId: String? = null) {
        setContent {
            
            
            
            
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                Content(
                    title = title,
                    message = message,
                    conversationId = conversationId,
                    roomId = roomId
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        L.i { "[CriticalAlert] onNewIntent: Handling new intent" }
        val conversationId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_CONVERSATION)
        val title = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_TITLE)
        val message = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_MESSAGE)
        val notificationId = intent.getIntExtra(LCallConstants.BUNDLE_KEY_CRITICAL_NOTIFICATION_ID, -1)
        val roomId = intent.getStringExtra(LCallConstants.BUNDLE_KEY_CRITICAL_ROOM_ID)

        if (conversationId.isNullOrEmpty() || title.isNullOrEmpty() || message.isNullOrEmpty() || notificationId == -1) {
            L.e { "[CriticalAlert] onNewIntent: Invalid parameters, finishing activity" }
            criticalAlertStateManager.reset()
            finish()
            return
        }

        
        if (isSilenced(intent.action, conversationId, roomId)) {
            undoSilencedAlert(notificationId, roomId)
            return
        }
        setIntent(intent)

        
        
        
        val previousNotificationId = ownNotificationId
        ownNotificationId = notificationId
        ownConversationId = conversationId
        if (criticalAlertManager.publishPageAndCheckSilence(notificationId, conversationId, roomId) &&
            intent.action != LCallConstants.CRITICAL_ALERT_ACTION_CLICKED
        ) {
            undoSilencedAlert(notificationId, roomId)
            previousNotificationId?.let { criticalAlertManager.stopIfOwner(it) }
            finishedForEndedRoom = true
            criticalAlertStateManager.setPageClosingSilently(notificationId)
            finishAndRemoveTask()
            return
        }

        
        criticalAlertStateManager.setConversationId(conversationId)
        criticalAlertStateManager.setIsShowing(true)
        
        autoFinishJob?.cancel()
        
        when(intent.action) {
            LCallConstants.CRITICAL_ALERT_ACTION_CLICKED -> {
                handleJoinAction(conversationId, roomId)
                showBlankTransparentUI()
            }
            else -> {
                showCriticalAlertUI(conversationId, title, message, roomId)
            }
        }
        
        scheduleAutoFinish()

        
        val isNotification = intent.getBooleanExtra(LCallConstants.BUNDLE_KEY_CRITICAL_IS_NOTIFICATION, false)
        if (!isNotification) criticalAlertManager.playSoundAndFlashLight(conversationId, notificationId, roomId)
    }


    




    private fun isSilenced(action: String?, conversationId: String, roomId: String?): Boolean =
        action != LCallConstants.CRITICAL_ALERT_ACTION_CLICKED && criticalAlertCallGate.shouldSilence(conversationId, roomId)

    
    private fun undoSilencedAlert(notificationId: Int, roomId: String?) {
        L.i { "[CriticalAlert] skip page reason=silenced notificationId=$notificationId roomId=$roomId" }
        messageNotificationUtil.cancelNotificationsById(notificationId)
        criticalAlertManager.stopIfOwner(notificationId)
    }

    private fun registerCriticalAlertReceiver() {
        val filter = IntentFilter()
        filter.addAction(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS)
        filter.addAction(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID)
        filter.addAction(LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_NOTIFICATION_ID)
        ContextCompat.registerReceiver(
            this,
            criticalAlertReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }
    
    
    private fun registerOnBackPressedHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                L.i { "[CriticalAlert] Back pressed or edge swipe detected, but ignoring to prevent accidental dismissal" }
                
            }
        }
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
    }

    private val criticalAlertReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.`package` == ApplicationHelper.instance.packageName) {
                    when (intent.action) {
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS -> {
                            L.i { "[CriticalAlert] Received dismiss action" }
                            messageNotificationUtil.cancelCriticalAlertNotification()
                            finishAndRemoveTask()
                        }
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_CONID -> {
                            L.i { "[CriticalAlert] Received dismiss action by conversationId" }
                            val conversationId = it.getStringExtra(LCallConstants.CRITICAL_ALERT_PARAM_CONVERSATION)
                            if (conversationId != null && conversationId == criticalAlertStateManager.getConversationId()) {
                                finishAndRemoveTask()
                            }
                        }
                        LCallConstants.CRITICAL_ALERT_ACTION_DISMISS_BY_NOTIFICATION_ID -> {
                            
                            
                            val id = it.getIntExtra(LCallConstants.CRITICAL_ALERT_PARAM_NOTIFICATION_ID, -1)
                            if (id != -1 && id == ownNotificationId) {
                                L.i { "[CriticalAlert] dismiss action notificationId=$id" }
                                finishedForEndedRoom = true
                                criticalAlertStateManager.setPageClosingSilently(id)
                                finishAndRemoveTask()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        
        
        criticalAlertStateManager.setPageVisible(true)
    }

    override fun onPause() {
        criticalAlertStateManager.setPageVisible(false)
        super.onPause()
    }


    override fun onDestroy() {
        super.onDestroy()
        L.i { "[CriticalAlert] onDestroy" }
        autoFinishJob?.cancel()
        autoFinishJob = null
        if (::backPressedCallback.isInitialized) {
            backPressedCallback.remove()
        }
        if (receiverRegistered) {
            try {
                unregisterReceiver(criticalAlertReceiver)
            } catch (e: Exception) {
                
                L.w { "[CriticalAlert] Failed to unregister receiver: ${e.message}" }
            }
        }
        val ownId = ownNotificationId
        if (ownId != null && (handledForCurrentCall || finishedForEndedRoom)) {
            
            
            criticalAlertManager.releasePage(ownId, ownConversationId, stopOwnedAlert = finishedForEndedRoom)
            return
        }
        criticalAlertStateManager.reset()
        criticalAlertManager.stopSoundAndFlashLight()
    }

    @Composable
    fun Content(
        title: String,
        message: String,
        conversationId: String,
        roomId: String? = null
    ) {
        CriticalAlertFullScreen(
            title = title,
            message = message,
            onJoinClick = { handleJoinAction(conversationId, roomId) },
            onCloseClick = { handleCancelAction(conversationId) }
        )
    }

    private fun handleJoinAction(conversationId: String, roomId: String? = null) {
        autoFinishJob?.cancel()

        criticalAlertStateManager.setIsJoining(true)

        
        
        val joinToken = criticalAlertCallGate.beginJoin(roomId, conversationId)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (onGoingCallStateManager.isInCalling()) {
                    L.i { "[CriticalAlert] Currently in call, canceling critical alert notification" }
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } else {
                    val response = callService.getCallingList()

                    if (response.status != 0) {
                        L.e { "[CriticalAlert] getCallingList failed with status: ${response.status}" }
                        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                    } else {
                        val calls = response.data?.calls

                        if (calls.isNullOrEmpty()) {
                            L.i { "[CriticalAlert] Calling list is empty, canceling critical alert notification" }
                            messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                            jumpToIndexActivity(this@CriticalAlertActivity)
                        } else {
                            val matchedCall = calls.firstOrNull { call ->
                                if (!roomId.isNullOrEmpty()) {
                                    call.roomId == roomId
                                } else {
                                    call.conversation == conversationId
                                }
                            }

                            if (matchedCall == null) {
                                L.i { "[CriticalAlert] No matching call found for conversationId: $conversationId, canceling notification" }
                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                                jumpToIndexActivity(this@CriticalAlertActivity)
                            } else {
                                matchedCall.let { data ->
                                    if (data.callName.isNullOrEmpty()) {
                                        L.w { "[CriticalAlert] callName empty conversationId=$conversationId type=${data.type} roomId=${data.roomId} caller=${data.caller.uid} source=${data.source} notifying=${data.notifying}" }
                                        when (data.type) {
                                            CallType.ONE_ON_ONE.type -> {
                                                data.callName = contactorCacheManager.getDisplayNameById(data.conversation)
                                            }
                                            CallType.GROUP.type -> {
                                                val inGroup = checkUserIsInGroup(mySelfId, conversationId)
                                                if (inGroup) {
                                                    data.callName = contactorCacheManager.getDisplayGroupNameById(data.conversation)
                                                } else {
                                                    data.callName = "${contactorCacheManager.getDisplayNameById(conversationId)}${ResUtils.getString(
                                                        R.string.call_instant_call_title
                                                    )}"
                                                }
                                            }
                                            CallType.INSTANT.type -> {
                                                data.callName = "${contactorCacheManager.getDisplayNameById(conversationId)}${ResUtils.getString(
                                                        R.string.call_instant_call_title
                                                    )}"
                                            }
                                        }
                                    }
                                }

                                L.i { "[CriticalAlert] Matching call found, joining call. roomId: ${matchedCall.roomId}, conversationId: $conversationId" }

                                messageNotificationUtil.cancelCriticalAlertNotification(conversationId)

                                val status = LCallManager.joinCall(this@CriticalAlertActivity, matchedCall)
                                if (status) {
                                    L.i { "[CriticalAlert] Successfully joined call for conversationId: $conversationId" }
                                } else {
                                    L.e { "[CriticalAlert] Failed to join call for conversationId: $conversationId" }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                L.e { "[CriticalAlert] Error handling notification click: ${e.message}, stackTrace: ${e.stackTraceToString()}" }
                try {
                    messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
                } catch (ex: Exception) {
                    L.e { "[CriticalAlert] Failed to cancel notification after error: ${ex.message}" }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    criticalAlertStateManager.setIsJoining(false)
                    if (!isFinishing && !isDestroyed) {
                        finish()
                    }
                }
            }
        }.invokeOnCompletion { criticalAlertCallGate.endJoin(joinToken) }
    }

    private fun handleCancelAction(conversationId: String) {
        autoFinishJob?.cancel()
        messageNotificationUtil.cancelCriticalAlertNotification(conversationId)
        finish()
    }

    private fun scheduleAutoFinish(delayMillis: Long = 60_000L) {
        autoFinishJob?.cancel()
        autoFinishJob = lifecycleScope.launch {
            delay(delayMillis)
            if (!isFinishing && !isDestroyed) {
                L.i { "[CriticalAlert] Auto finishing after ${delayMillis}ms" }
                finishAndRemoveTask()
            }
        }
    }

    private fun jumpToIndexActivity(context: Context) {
        try {
            if (isFinishing || isDestroyed) {
                L.w { "[CriticalAlert] Activity is finishing or destroyed, skipping jumpToIndexActivity" }
                return
            }
            
            val intent = Intent(context.applicationContext, activityProvider.getActivityClass(ActivityType.INDEX)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.applicationContext.startActivity(intent)
            
            
            if (!isFinishing && !isDestroyed) {
                finishAndRemoveTask()
            }
        } catch (e: Exception) {
            L.e { "[CriticalAlert] jumpToIndexActivity error: ${e.message}"}
        }
    }

    private suspend fun checkUserIsInGroup(userId: String, gid: String): Boolean {
        return try {
            val resp = groupRepo.getGroupInfo(gid)

            val members = resp.data?.members.orEmpty()
            members.any { it.uid == userId }
        } catch (e: Exception) {
            L.e { "[Call] CallMessageHandler checkUserIsInGroup error: ${e.stackTraceToString()}" }
            false
        }
    }
}