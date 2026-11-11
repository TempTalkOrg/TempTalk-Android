package com.difft.android.chat.contacts.contactsdetail

import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.ShellNavigation
import com.difft.android.base.utils.pane.VisibleDetailStack
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.detailNavigationUnderDialog









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
