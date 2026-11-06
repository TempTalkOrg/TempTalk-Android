package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.content.Intent
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.ShellNavigation
import com.difft.android.base.utils.pane.VisibleDetailStack
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailNavigationUnderDialog

/** The contact card as a detail destination (home detail stack, or ContactDetailActivity). */
class ContactDetailDestination(
    private val contactId: String,
    private val sourceType: String? = null,
    private val source: String? = null,
) : DetailDestination {
    override fun newFragment(): Fragment =
        ContactDetailFragment.newInstance(contactId = contactId, sourceType = sourceType, source = source)

    // Started from a page, so no NEW_TASK (unlike ContactDetailActivity.startActivity).
    override fun shellIntent(context: Context): Intent = ContactDetailActivity.intent(context, contactId, sourceType, source)

    override val subjectId: String get() = contactId
}

/**
 * Pushes from the contact card (design §3.6). A card in a sheet shown from a page (a
 * conversation's avatar or shared contact) pushes onto that page's stack; a sheet shown on the
 * home screen itself pushes onto the shown stack only while the conversation it was shown over
 * ([overConversation]) is still its root; anything else, or a refused push, opens the
 * destination's shell. The push is made while the card is still attached, and [sheet] is
 * dismissed after it. A card that is a page itself pushes as any page does.
 */
object ContactCardNavigation {

    fun push(card: Fragment, destination: DetailDestination, sheet: DialogFragment?, overConversation: String?) {
        if (sheet == null) {
            card.detailNavigation().push(card, destination)
            return
        }
        val underDialog = card.detailNavigationUnderDialog()
        val accepted = if (underDialog != null) {
            underDialog.push(card, destination)
        } else {
            (card.activity as? VisibleDetailStack)?.pushOnVisibleStack(destination, overConversation) ?: false
        }
        if (!accepted) {
            L.i { "[ContactCard] sheet push opens the shell underPage=${underDialog != null} over=${overConversation != null}" }
            ShellNavigation(card).push(card, destination)
        }
        sheet.dismiss()
    }
}
