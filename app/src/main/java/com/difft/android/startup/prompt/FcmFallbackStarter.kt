package com.difft.android.startup.prompt

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.chat.messages.MessageForegroundService
import com.difft.android.chat.messages.MessageServiceManager
import javax.inject.Inject

/**
 * Starts the background connection silently when FCM is unavailable and nothing stops it. When it
 * cannot start, the BACKGROUND_CONNECTION startup prompt explains why; this class never shows UI.
 */
class FcmFallbackStarter internal constructor(
    private val userManager: UserManager,
    private val messageServiceManager: MessageServiceManager,
    private val isServiceRunning: () -> Boolean,
) {

    @Inject
    constructor(userManager: UserManager, messageServiceManager: MessageServiceManager) :
        this(userManager, messageServiceManager, { MessageForegroundService.isRunning })

    /** Called on every unavailable FCM result, including replays; idempotent. */
    fun onFcmUnavailable() {
        if (isServiceRunning()) return
        val autoStart = userManager.getUserData()?.autoStartMessageService ?: true
        if (!autoStart) {
            L.i { "[MessageService] User disabled service, left to the startup prompt" }
            return
        }
        if (messageServiceManager.checkBackgroundConnectionRequirements()) {
            L.i { "[MessageService] Auto-starting service (conditions met)" }
            messageServiceManager.startService()
        } else {
            L.w { "[MessageService] Cannot auto-start, left to the startup prompt" }
        }
    }
}
