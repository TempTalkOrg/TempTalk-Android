package com.difft.android.call.manager

import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.call.CallType
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.call.LCallToChatController
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.CoroutineScope


















internal class CallScreenshotController(
    private val activity: AppCompatActivity,
    private val coroutineScope: CoroutineScope,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callToChatController: LCallToChatController,
    private val conversationIdProvider: () -> String?,
    private val callTypeProvider: () -> String,
    private val isInPipModeProvider: () -> Boolean,
) {
    private var screenshotDetector: ScreenshotDetector? = null

    









    fun millisSinceLastScreenshot(): Long =
        screenshotDetector?.millisSinceLastCapture() ?: Long.MAX_VALUE

    fun updateListeningState() {
        val conversationId = conversationIdProvider()
        if (conversationId.isNullOrEmpty()) {
            screenshotDetector?.stopListening()
            return
        }

        val callType = resolveCurrentCallType(conversationId)
        val isInstantCall = callType.isInstant()
        val isInPipMode = isInPipModeProvider()
        val isInForeground = onGoingCallStateManager.isInForeground.value

        if (isInstantCall || isInPipMode || !isInForeground) {
            screenshotDetector?.stopListening()
            return
        }

        if (screenshotDetector == null) {
            screenshotDetector = ScreenshotDetector(
                activity = activity,
                coroutineScope = coroutineScope,
                onScreenshotDetected = {
                    L.i { "[Call][Screenshot] Screenshot detected, sending notification" }
                    handleScreenshotDetected()
                }
            )
        }
        screenshotDetector?.startListening()
    }

    fun stopListening() {
        screenshotDetector?.stopListening()
    }

    fun release() {
        screenshotDetector?.release()
        screenshotDetector = null
    }

    private fun handleScreenshotDetected() {
        val conversationId = conversationIdProvider() ?: return
        val callType = resolveCurrentCallType(conversationId)
        
        
        
        if (callType.isInstant() ||
            isInPipModeProvider() ||
            !onGoingCallStateManager.isInForeground.value
        ) {
            L.i { "[Call][Screenshot] Skipped: suppression condition met at dispatch" }
            return
        }
        callToChatController.sendScreenshotNotification(conversationId, callType)
    }

    







    private fun resolveCurrentCallType(conversationId: String): CallType {
        val type = callTypeProvider()
        val resolved = CallType.fromString(type)
        if (resolved != null) {
            return resolved
        }
        return if (ValidatorUtil.isGid(conversationId)) CallType.GROUP else CallType.ONE_ON_ONE
    }
}
