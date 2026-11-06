package com.difft.android.base.utils.pane

import android.content.Intent
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L

/**
 * [DetailNavigation] for a page that is not inside a detail stack: a full-window shell Activity,
 * a popup Activity or a bottom sheet. It keeps the Activity behaviour those pages have today.
 */
class ShellNavigation(private val fragment: Fragment) : DetailNavigation {

    /** Starts the destination's shell Activity; [from] does not matter outside a stack. */
    override fun push(from: Fragment, destination: DetailDestination): Boolean {
        val context = fragment.context ?: return false
        val intent = destination.shellIntent(context).putExtra(DetailShellPop.EXTRA_SUBJECT_ID, destination.subjectId)
        val host = fragment.activity as? DetailShellHost
        // Reusable/task launches cannot authenticate a fresh linear child's result.
        if (host != null && intent.isFreshDetailShellIntent()) return host.launchPush(intent)
        fragment.startActivity(Intent(intent).apply { removeExtra(DetailShellPop.EXTRA_REQUEST_ID) })
        return true
    }

    /** Dismisses the nearest enclosing dialog (a contact sheet) before finishing the Activity. */
    override fun pop(from: Fragment): Boolean = pop(from, 1)

    override fun pop(from: Fragment, count: Int): Boolean {
        if (count <= 0) return false
        from.enclosingDialog()?.let {
            it.dismiss()
            return true
        }
        val activity = from.activity ?: return false
        return DetailShellPop.finish(activity, count)
    }

    override fun openConversation(target: ConversationTarget) {
        val context = fragment.context ?: return
        val opener = context.applicationContext as? ConversationOpener
        if (opener == null) {
            L.w { "[DetailNav] shell open ignored, no opener kind=${target.kind}" }
            return
        }
        opener.openConversation(context, target)
    }

    override fun closeConversation(from: Fragment) {
        from.activity?.finish()
    }

    override fun canPop(from: Fragment): Boolean = true

    private fun Fragment.enclosingDialog(): DialogFragment? {
        var current: Fragment? = this
        while (current != null) {
            if (current is DialogFragment) return current
            current = current.parentFragment
        }
        return null
    }
}

internal fun Intent.isFreshDetailShellIntent(): Boolean = flags and (
    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
    ) == 0
