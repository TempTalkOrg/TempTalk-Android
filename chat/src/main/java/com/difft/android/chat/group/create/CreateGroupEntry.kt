package com.difft.android.chat.group.create

import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.recent.ListPaneLayerOpener







fun openCreateGroupFromList(from: Fragment) {
    val host = from.activity as? ListPaneLayerOpener
    if (host?.showsCreateGroup() == true) {
        alreadyShowing("list")
        return
    }
    if (host?.openCreateGroupLayer(emptyList()) == true) return
    CreateGroupActivity.startActivity(from.requireActivity(), emptyList())
}

fun openCreateGroupFromConversation(from: Fragment, contactId: String) {
    if ((from.activity as? ListPaneLayerOpener)?.showsCreateGroup() == true) {
        alreadyShowing("conversation")
        return
    }
    from.detailNavigation().push(from, CreateGroupDestination(listOf(contactId)))
}

private fun alreadyShowing(entry: String) {
    L.i { "[CreateGroup] open ignored entry=$entry reason=already_showing" }
}


class CreateGroupDestination(preselectedIds: List<String>) : DetailDestination {
    private val ids = preselectedIds.distinct()
    override fun newFragment(): Fragment = CreateGroupFragment.newInstance(ids)
    override fun shellIntent(context: Context): Intent = CreateGroupActivity.intent(context, ids)
}
