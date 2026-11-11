package com.difft.android.dualpane

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.chat.contacts.contactsdetail.ContactDetailFragment
import com.difft.android.chat.contacts.contactsdetail.ContactCardRequest
import com.difft.android.chat.contacts.groupcard.GroupCardFragment
import com.difft.android.chat.group.GroupChatFragment
import com.difft.android.chat.group.create.CreateGroupFragment
import com.difft.android.chat.recent.FoldedGroupsFragment
import com.difft.android.chat.ui.ChatBackgroundDrawable
import com.difft.android.chat.ui.ChatFragment
import com.difft.android.search.GlobalSearchFragment





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

    fun contactDetail(request: ContactCardRequest): Fragment = ContactDetailFragment.newInstance(
        request.contactId, request.customId, request.contactName, request.sourceType, request.source, request.avatar, request.joinedAt,
    )

    fun groupCard(gid: String): Fragment = GroupCardFragment.newInstance(gid)

    
    override fun layerRoot(root: ListPaneLayerRoot): Fragment = layerRoot(root, null)

    override fun layerRoot(root: ListPaneLayerRoot, args: Bundle?): Fragment = when (root) {
        ListPaneLayerRoot.SEARCH -> GlobalSearchFragment.newInstance()
        ListPaneLayerRoot.FOLDED_GROUPS -> FoldedGroupsFragment.newInstance()
        ListPaneLayerRoot.CREATE_GROUP -> CreateGroupFragment.newInstance(layerRootArgs = args)
    }

    
    override fun usesSettingsBackground(page: Fragment): Boolean = page is SettingsIdiomPage

    override fun conversationLayerBackground(context: Context, layer: View): Drawable =
        ChatBackgroundDrawable(context, layer)
}
