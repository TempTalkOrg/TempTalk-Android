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

/**
 * Where a conversation opens (design: foldable detail stacks, §3.5). Installed as the
 * Application's `ConversationOpener`, so every `ChatActivity.startActivity` /
 * `GroupChatContentActivity.startActivity` call and `ShellNavigation.openConversation` lands here:
 * 1. the context is the home screen: open in its current tab's detail stack;
 * 2. another Activity in a two-pane window: bring the home screen forward with [EXTRA_OPEN_CONVERSATION]
 *    (singleTask finishes the launching page), so the conversation opens in the detail pane;
 * 3. otherwise (one-pane, or no Activity): the full-window shell, so Back returns to the launcher.
 */
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

    /** A removed conversation closes on the home screen (see [ConversationOpener.closeConversation]). */
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

/** The conversation an IndexActivity launch asks to open (router rule 2). */
const val EXTRA_OPEN_CONVERSATION = "com.difft.android.extra.OPEN_CONVERSATION"

internal object OpenConversationExtra {

    fun put(intent: Intent, target: ConversationTarget): Intent = intent.putExtra(EXTRA_OPEN_CONVERSATION, target)

    /**
     * Takes the request out of [intent] so it is handled once. A recreation (fold, language) keeps
     * the launch intent and already restored that conversation, so it is dropped there (#1395).
     */
    fun consume(intent: Intent, isRecreation: Boolean): ConversationTarget? {
        if (!intent.hasExtra(EXTRA_OPEN_CONVERSATION)) return null
        val target = IntentCompat.getParcelableExtra(intent, EXTRA_OPEN_CONVERSATION, ConversationTarget::class.java)
        intent.removeExtra(EXTRA_OPEN_CONVERSATION)
        return if (isRecreation) null else target
    }
}
