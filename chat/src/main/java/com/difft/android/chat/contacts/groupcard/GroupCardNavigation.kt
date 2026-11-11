package com.difft.android.chat.contacts.groupcard

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.chat.contacts.cardPreloadEntryPoint
import com.difft.android.chat.contacts.showCardAfterLocalRead


fun interface GroupCardNavigationCallback {
    fun onGroupCardSelected(groupId: String): Boolean
}

fun showGroupCard(context: Context, gid: String) {
    if (gid.isBlank()) return
    val activity = context.fragmentActivity()
    if (activity != null) {
        showGroupCard(activity, gid)
    } else {
        context.startActivity(GroupCardActivity.intent(context, gid).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}


fun showGroupCard(activity: FragmentActivity, gid: String) {
    val manager = activity.supportFragmentManager
    val tag = GroupCardBottomSheetDialogFragment.TAG
    if (gid.isBlank() || manager.isStateSaved || manager.findFragmentByTag(tag) != null) return
    val entry = activity.cardPreloadEntryPoint()
    showCardAfterLocalRead(activity, manager, tag, entry.cardPreloads(),
        read = { entry.cardPreloader().group(gid) }) { key ->
        if (manager.findFragmentByTag(tag) == null) GroupCardBottomSheetDialogFragment.newInstance(gid, key).show(manager, tag)
    }
}

private tailrec fun Context.fragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.fragmentActivity() else null
    else -> null
}

class GroupCardDestination(private val gid: String) : DetailDestination {
    override val subjectId: String get() = gid
    override fun newFragment(): Fragment = GroupCardFragment.newInstance(gid)
    override fun shellIntent(context: Context): Intent = GroupCardActivity.intent(context, gid)
}
