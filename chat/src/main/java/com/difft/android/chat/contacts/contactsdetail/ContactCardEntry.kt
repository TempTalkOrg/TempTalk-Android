package com.difft.android.chat.contacts.contactsdetail

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity

data class ContactCardRequest(
    val contactId: String,
    val customId: String? = null,
    val contactName: String? = null,
    val sourceType: String? = null,
    val source: String? = null,
    val avatar: String? = null,
    val joinedAt: String? = null,
)


fun interface ContactCardHost {
    fun onContactCardSelected(request: ContactCardRequest): Boolean
}

fun showContactCard(context: Context, request: ContactCardRequest) {
    if (request.contactId.isBlank()) return
    val activity = context.cardActivity()
    if (activity != null) {
        ContactDetailBottomSheetDialogFragment.show(activity, request.contactId, request.contactName,
            request.sourceType, request.source, request.avatar, request.joinedAt, customId = request.customId)
    } else {
        context.startActivity(ContactDetailActivity.intent(context, request))
    }
}

fun showContactCard(fragment: Fragment, contactId: String, sourceType: String? = null, source: String? = null) {
    ContactDetailBottomSheetDialogFragment.show(fragment, contactId, sourceType = sourceType, source = source)
}

private tailrec fun Context.cardActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> if (baseContext === this) null else baseContext.cardActivity()
    else -> null
}
