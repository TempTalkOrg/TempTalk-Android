package com.difft.android.call.session

import android.content.Context
import android.content.Intent
import androidx.annotation.MainThread
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.callConfigSnapshot
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallManager
import com.difft.android.call.data.VoicePreset
import com.difft.android.network.config.GlobalConfigsManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts a call from its entry point (outgoing START, JOIN): launches the call page and starts the
 * call session in the same main-thread message, so the page's `onCreate` (a later message) always
 * finds the session, and a second launch while a call is live is always refused.
 *
 * The page launch comes first: a launch that throws leaves no session behind.
 */
@Singleton
class CallSessionLauncher @Inject constructor(
    private val holder: CallSessionHolder,
    private val globalConfigsManager: GlobalConfigsManager,
    private val userManager: UserManager,
) {
    /**
     * Launches the call page for [intent] and starts its session. Returns false, and starts nothing,
     * when a call is already live, the page launch throws or the session failed to start; the
     * caller then releases its wait-dialog claim [entryWaitToken]. On success the session owns it.
     * [callConfig] is the entry point's snapshot (START passes the one its envelope used, #1395);
     * null reads the live config now.
     */
    @MainThread
    fun launch(context: Context, intent: Intent, entryWaitToken: Int, callConfig: CallConfig? = null): Boolean {
        holder.current.value?.let {
            L.w { "[Call][Session] launch refused: id=${it.id} live" }
            return false
        }
        val params = paramsFor(CallIntent(intent), entryWaitToken, callConfig ?: globalConfigsManager.callConfigSnapshot())
        CallIntent.putSessionKey(intent, params.launchKey)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            L.e(e) { "[Call][Session] launch failed action=${params.callIntent.action}" }
            return false
        }
        holder.start(params) ?: return false
        if (params.callRole == CallRole.CALLEE) stopIncomingCallService(params)
        return true
    }

    private fun paramsFor(callIntent: CallIntent, entryWaitToken: Int, callConfig: CallConfig) = CallSessionParams(
        e2eeEnable = true,
        callIntent = callIntent,
        callConfig = callConfig,
        callRole = if (callIntent.callRole == CallRole.CALLER.type) CallRole.CALLER else CallRole.CALLEE,
        initialVoicePreset = VoicePreset.fromSdkKey(
            userManager.getUserData()?.callVoiceChangerPreset ?: VoicePreset.ORIGINAL.sdkKey
        ),
        launchKey = UUID.randomUUID().toString(),
        needAppLock = isAppLockEnabled() && callIntent.needAppLock,
        entryWaitToken = entryWaitToken,
    )

    private fun isAppLockEnabled(): Boolean {
        val user = userManager.getUserData() ?: return false
        return !user.pattern.isNullOrEmpty() || !user.passcode.isNullOrEmpty()
    }

    // The accepted call is live: its ringing (service, notification, sound) stops now.
    private fun stopIncomingCallService(params: CallSessionParams) {
        try {
            LCallManager.stopIncomingCallService(params.callIntent.roomId, tag = "accept: call session started")
        } catch (e: Exception) {
            L.e(e) { "[Call][Session] incoming-call service stop failed" }
        }
    }
}

/**
 * The session a call page attaches to: the live one, unless the page was launched for another
 * session (its [sessionKey] differs from the live session's launch key). A page with no key is a
 * return to the call; null means there is no session for this page and it must finish.
 */
internal fun resolveCallSession(current: CallSession?, sessionKey: String?): CallSession? = when {
    current == null -> null
    sessionKey == null || sessionKey == current.params.launchKey -> current
    else -> null
}
