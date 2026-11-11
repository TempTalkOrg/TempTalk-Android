package com.difft.android.call.manager

import com.difft.android.base.utils.globalServices

import android.app.Activity
import androidx.compose.ui.platform.ComposeView
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.difft.android.base.call.CallFeedbackRequestBody
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.base.storage.di.AppStateDataStore
import com.difft.android.base.utils.appScope
import com.difft.android.call.data.FeedbackCallInfo
import com.difft.android.call.repo.LCallHttpService
import com.difft.android.call.ui.feedback.CallRatingFeedbackView
import com.difft.android.call.util.CallComposeUiUtil
import com.difft.android.network.ChativeHttpClient
import com.difft.android.network.di.ChativeHttpClientModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallFeedbackManager @Inject constructor(
    @ChativeHttpClientModule.Call private val callHttpClient: dagger.Lazy<ChativeHttpClient>,
    @param:AppStateDataStore private val appStateStore: DataStore<Preferences>,
    private val userManager: com.difft.android.base.user.UserManager,
) {
    private val callService by lazy {
        callHttpClient.get().getService(LCallHttpService::class.java)
    }

    private var callFeedbackInfo: FeedbackCallInfo? = null

    private companion object {
        private const val RESET_INTERVAL_MS = 24 * 60 * 60 * 1000L 
    }

    
    @Suppress("BanRunBlockingOutsideTests")
    private fun <T> readBlocking(read: suspend (Preferences) -> T?, default: T): T =
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(1_000) { read(appStateStore.data.first()) } ?: default
        }

    @Suppress("BanRunBlockingOutsideTests")
    private fun writeBlocking(edit: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        runBlocking(Dispatchers.IO) {
            runCatching {
                withTimeoutOrNull(1_000) { appStateStore.edit(edit) }
            }.onFailure {
                L.w { "[Call] CallFeedbackManager write failed: ${it.message}" }
            }
        }
    }

    

    fun submitCallFeedback(params: CallFeedbackRequestBody) {
        appScope.launch {
            submitCallFeedbackInternal(params)
        }
    }

    private suspend fun submitCallFeedbackInternal(params: CallFeedbackRequestBody) {
        
        if (globalServices.userManager.getUserData()?.microToken.isNullOrEmpty()) {
            L.e { "[Call] CallFeedbackManager submitCallFeedback failed: missing authentication token" }
            return
        }

        try {
            val response = withContext(Dispatchers.IO) {
                callService.callFeedback(params)
            }

            if (response.status == 0) {
                L.i { "[Call] CallFeedbackManager submitCallFeedback, request success" }
            } else {
                L.e { "[Call] CallFeedbackManager submitCallFeedback, request fail: ${response.reason}" }
            }
        } catch (error: Exception) {
            L.e { "[Call] CallFeedbackManager submitCallFeedback, request fail, error: ${error.message}" }
        }
    }

    fun showCallFeedbackView(activity: Activity, callInfo: FeedbackCallInfo) {
        val composeView = ComposeView(activity)
        composeView.setContent {
            
            
            DifftTheme(applyWindowBackground = false) {
                CallRatingFeedbackView(
                    callInfo = callInfo,
                    onDisplay = {
                        clearCallFeedbackInfo()
                    },
                    onDismiss = {
                        CallComposeUiUtil.removeComposeViewFromActivity(activity, composeView)
                    },
                    onSubmit = { data ->
                        submitCallFeedback(data)
                    }
                )
            }
        }
        try {
            CallComposeUiUtil.addComposeViewToActivity(activity, composeView)
        } catch (e: Exception) {
            L.e { "[Call] CallFeedbackManager Feedback addComposeViewToActivity error: ${e.message}" }
        }
    }

    

    fun setCallFeedbackInfo(info: FeedbackCallInfo?) {
        callFeedbackInfo = info
    }

    fun clearCallFeedbackInfo() {
        callFeedbackInfo = null
    }

    
    fun getAndClearCallFeedbackInfo(): FeedbackCallInfo? {
        val info = callFeedbackInfo
        callFeedbackInfo = null
        return info
    }

    fun getCallFeedbackInfo(): FeedbackCallInfo? {
        return callFeedbackInfo
    }


    


    @Synchronized
    private fun ensure24HourReset() {
        val snapshot = userManager.getUserData()
        val lastReset = snapshot?.callLastFeedbackResetTime ?: 0L
        val now = System.currentTimeMillis()

        if (now - lastReset >= RESET_INTERVAL_MS || lastReset == 0L) {
            val newThreshold = (1..5).random()
            
            
            
            
            
            
            userManager.update(commit = true) {
                callLastFeedbackResetTime = now
                callFeedbackRandomThreshold = newThreshold
                callFeedbackHasTriggered = false
            }
            
            writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = 0 }
        }
    }

    


    @Synchronized
    fun shouldTriggerFeedback(isForce: Boolean): Boolean {
        ensure24HourReset()

        val snapshot = userManager.getUserData()
        val hasTriggered = snapshot?.callFeedbackHasTriggered ?: false
        if (hasTriggered) return false

        val currentCount = readBlocking({ it[AppStateKeys.CALL_COUNT] }, 0) + 1
        val threshold = snapshot?.callFeedbackRandomThreshold ?: 3

        if ((currentCount >= threshold) || isForce) {
            
            
            
            
            userManager.update(commit = true) { callFeedbackHasTriggered = true }
            writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = currentCount }
            return true
        }

        writeBlocking { prefs -> prefs[AppStateKeys.CALL_COUNT] = currentCount }
        return false
    }
}

