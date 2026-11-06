package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.chat.contacts.contactsdetail.ContactDetailFragment
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.recent.FoldedGroupsFragment
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatFragment
import com.difft.android.search.GlobalSearchFragment

/**
 * The pages IndexActivity mounts directly into a tab's detail stack. Every direct mount is built
 * here, so `IndexActivityFragmentContainerDetailThemeGuardTest` can enumerate them from this file.
 */
internal object IndexDetailPages : PaneNavigator.Pages, ListPaneLayerHostFragment.Pages {

    override fun conversationRoot(target: ConversationTarget): Fragment = when (target.kind) {
        ConversationTarget.Kind.ONE_ON_ONE -> ChatFragment.newInstance(
            contactId = target.id,
            sourceType = target.sourceType,
            source = target.source,
            jumpMessageTimestamp = target.jumpTimestamp,
        )
        ConversationTarget.Kind.GROUP -> GroupChatFragment.newInstance(
            groupId = target.id,
            jumpMessageTimestamp = target.jumpTimestamp,
        )
    }

    fun contactDetail(contactId: String): Fragment = ContactDetailFragment.newInstance(contactId = contactId)

    /** Root of the list pane's layer; its pushed pages come from their destinations. */
    override fun layerRoot(root: ListPaneLayerRoot): Fragment = when (root) {
        ListPaneLayerRoot.SEARCH -> GlobalSearchFragment.newInstance()
        ListPaneLayerRoot.FOLDED_GROUPS -> FoldedGroupsFragment.newInstance()
    }

    // Settings-idiom pages sit on bg, like their shell Activities' window background.
    override fun usesSettingsBackground(page: Fragment): Boolean = page is SettingsIdiomPage

    override fun conversationLayerBackground(context: Context, layer: View): Drawable =
        ChatBackgroundDrawable(context, layer, drawSystemBars = true, drawStatusBarStrip = false)
}
