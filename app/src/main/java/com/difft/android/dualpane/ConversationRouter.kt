package com.difft.android.dualpane

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.IntentCompat
import com.difft.android.IndexActivity
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.ui.ConversationShells










object ConversationRouter {

    enum class Route { HOME_STACK, BRING_HOME, SHELL }

    fun route(context: Context): Route {
        val activity = context.findActivity()
        return when {
            activity is ConversationNavigationCallback && !activity.isFinishing -> Route.HOME_STACK
            activity != null && activity.resources.getBoolean(R.bool.dual_pane_layout_active) -> Route.BRING_HOME
            else -> Route.SHELL
        }
    }

    fun open(context: Context, target: ConversationTarget) {
        val route = route(context)
        L.i { "[ConvRouter] open route=$route kind=${target.kind} jump=${target.hasJump}" }
        when (route) {
            Route.HOME_STACK -> (context.findActivity() as ConversationNavigationCallback).openConversation(target)
            Route.BRING_HOME -> context.startActivity(
                OpenConversationExtra.put(Intent(context, IndexActivity::class.java), target)
            )
            Route.SHELL -> context.startActivity(ConversationShells.intent(context, target))
        }
    }

    
    fun close(conversationId: String) = PaneNavigator.closeConversationEverywhere(conversationId)

    private fun Context.findActivity(): Activity? {
        var current: Context? = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }
}


const val EXTRA_OPEN_CONVERSATION = "com.difft.android.extra.OPEN_CONVERSATION"

internal object OpenConversationExtra {

    fun put(intent: Intent, target: ConversationTarget): Intent = intent.putExtra(EXTRA_OPEN_CONVERSATION, target)

    



    fun consume(intent: Intent, isRecreation: Boolean): ConversationTarget? {
        if (!intent.hasExtra(EXTRA_OPEN_CONVERSATION)) return null
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_OPEN_CONVERSATION, ConversationTarget::class.java)
        intent.removeExtra(EXTRA_OPEN_CONVERSATION)
        return if (isRecreation) null else target
    }
}
