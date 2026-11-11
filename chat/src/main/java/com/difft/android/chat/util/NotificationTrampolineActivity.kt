package com.difft.android.chat.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.activity.NotificationPopupGate
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import dagger.hilt.android.EntryPointAccessors
import util.AppForegroundObserver














class NotificationTrampolineActivity : android.app.Activity() {

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_OPEN_POPUP = "extra_open_popup"

        fun createIntent(context: Context, contactId: String?, groupId: String?): Intent {
            return Intent(context, NotificationTrampolineActivity::class.java).apply {
                if (!groupId.isNullOrEmpty()) {
                    putExtra(EXTRA_GROUP_ID, groupId)
                } else {
                    putExtra(EXTRA_CONTACT_ID, contactId)
                }
            }
        }

        





        @VisibleForTesting
        internal fun mainActivityFlags(isForegrounded: Boolean): Int =
            if (isForegrounded) 0 else Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK

        






        @VisibleForTesting
        internal fun startPopupAfterLaunch(context: Context, popup: Intent, blocker: () -> String?) {
            val app = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                val reason = blocker()
                if (reason == null) {
                    app.startActivity(popup)
                } else {
                    L.w { "[NotificationTrampoline] delayed popup dropped blocker=$reason" }
                }
            }, POPUP_DELAY_MS)
        }

        
        private fun delayedPopupBlocker(gate: NotificationPopupGate): String? = when {
            !AppForegroundObserver.hasUserFacingActivity() -> "background"
            gate.isMainActivityHostingRecovery() -> "recovery"
            else -> gate.inPlacePopupBlocker()
        }

        
        @VisibleForTesting
        internal const val POPUP_DELAY_MS = 120L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)

        if (contactId.isNullOrEmpty() && groupId.isNullOrEmpty()) {
            L.e { "[NotificationTrampoline] No contactId or groupId provided" }
            finish()
            return
        }

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationTrampolineEntryPoint::class.java
        )
        
        
        val isForegrounded = AppForegroundObserver.hasUserFacingActivity()
        
        
        
        
        val gate = applicationContext as? NotificationPopupGate
        val popupIntent = if (isForegrounded) ChatPopupIntents.create(this, contactId, groupId) else null
        var popupBlocker: String? = null
        val route = when {
            !isForegrounded -> Route.MAIN.also { popupBlocker = "background" }
            gate == null -> Route.MAIN.also { popupBlocker = "no_gate" }
            gate.isMainActivityHostingRecovery() -> Route.DROP.also { popupBlocker = "recovery" }
            popupIntent == null -> Route.MAIN.also { popupBlocker = "invalid_id" }
            else -> {
                popupBlocker = gate.inPlacePopupBlocker()
                if (popupBlocker == null) Route.POPUP else Route.MAIN
            }
        }
        L.i { "[NotificationTrampoline] isForegrounded=$isForegrounded, processForegrounded=${AppForegroundObserver.isForegrounded()}, startedActivityCount=${AppForegroundObserver.getStartedActivityCount()}, route=$route, blocker=$popupBlocker, contactId=$contactId, groupId=$groupId" }

        when (route) {
            Route.POPUP -> if (popupIntent != null && gate != null) {
                startPopupAfterLaunch(this, popupIntent) { delayedPopupBlocker(gate) }
            }
            Route.MAIN -> startActivity(createMainActivityIntent(entryPoint, contactId, groupId, isForegrounded))
            Route.DROP -> Unit
        }
        finish()
    }

    private enum class Route { POPUP, MAIN, DROP }

    private fun createMainActivityIntent(
        entryPoint: NotificationTrampolineEntryPoint,
        contactId: String?,
        groupId: String?,
        isForegrounded: Boolean
    ): Intent {
        val activityProvider = entryPoint.activityProvider()

        return Intent(this, activityProvider.getActivityClass(ActivityType.MAIN)).apply {
            putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_MESSAGE)
            if (!groupId.isNullOrEmpty()) {
                putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, groupId)
            } else {
                putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, contactId)
            }

            addFlags(mainActivityFlags(isForegrounded))
            if (isForegrounded) {
                
                putExtra(EXTRA_OPEN_POPUP, true)
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface NotificationTrampolineEntryPoint {
        fun activityProvider(): ActivityProvider
    }
}
