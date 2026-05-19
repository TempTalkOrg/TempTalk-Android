package com.difft.android.call.manager

import androidx.appcompat.app.AppCompatActivity
import com.difft.android.base.call.CallType
import com.difft.android.base.common.ScreenshotDetector
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ValidatorUtil
import com.difft.android.call.LCallToChatController
import com.difft.android.call.state.OnGoingCallStateManager
import kotlinx.coroutines.CoroutineScope

/**
 * Screenshot detection controller.
 *
 * Manages screenshot detection during an active call, deciding whether to enable
 * the detector based on the current call type, foreground/background state and
 * PIP mode, and notifying the peer when a screenshot is captured.
 *
 * Suppression rules:
 * - Instant calls never trigger a notification.
 * - PIP mode never triggers a notification.
 * - Activity not in the foreground never triggers a notification.
 * - Window focus lost longer than the threshold (treated as the notification
 *   shade being pulled down) never triggers a notification.
 */
class CallScreenshotController(
    private val activity: AppCompatActivity,
    private val coroutineScope: CoroutineScope,
    private val onGoingCallStateManager: OnGoingCallStateManager,
    private val callToChatController: LCallToChatController,
    private val conversationIdProvider: () -> String?,
    private val callTypeProvider: () -> String,
    private val isInPipModeProvider: () -> Boolean,
    private val focusLostAtProvider: () -> Long,
) {
    private var screenshotDetector: ScreenshotDetector? = null

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
        // Defensive final guard: re-check suppression conditions at callback
        // dispatch time to close the race window between `stopListening()` and
        // any in-flight `ContentObserver.onChange` callbacks.
        if (callType.isInstant() ||
            isInPipModeProvider() ||
            !onGoingCallStateManager.isInForeground.value
        ) {
            L.i { "[Call][Screenshot] Skipped: suppression condition met at dispatch" }
            return
        }
        val focusLostAt = focusLostAtProvider()
        val focusLostDuration =
            if (focusLostAt > 0L) System.currentTimeMillis() - focusLostAt else 0L
        if (focusLostAt > 0L && focusLostDuration >= NOTIFICATION_PANEL_THRESHOLD_MS) {
            L.i { "[Call][Screenshot] Skipped: focus lost ${focusLostDuration}ms ago (notification panel open)" }
            return
        }
        callToChatController.sendScreenshotNotification(conversationId, callType)
    }

    /**
     * Resolve the current [CallType].
     *
     * Prefers the value reported by the providers; when that string cannot be
     * parsed (legacy / unexpected payload) falls back to a type inferred from
     * the conversation id. Always returns a non-null value, so callers do not
     * need a null guard.
     */
    private fun resolveCurrentCallType(conversationId: String): CallType {
        val type = callTypeProvider()
        val resolved = CallType.fromString(type)
        if (resolved != null) {
            return resolved
        }
        return if (ValidatorUtil.isGid(conversationId)) CallType.GROUP else CallType.ONE_ON_ONE
    }

    companion object {
        private const val NOTIFICATION_PANEL_THRESHOLD_MS = 2000L
    }
}
