package com.difft.android.chat.contacts.contactsdetail

import org.difft.app.database.models.ContactorModel

enum class ContactCardKind { LOADING, FRIEND, STRANGER, PENDING_REMOVAL }

data class ContactCardPresentation(
    val kind: ContactCardKind = ContactCardKind.LOADING,
    val showCall: Boolean = false,
    val showShare: Boolean = false,
    val showMore: Boolean = false,
    val showDelete: Boolean = false,
    val showFriendInfo: Boolean = false,
    val showRemarkEdit: Boolean = false,
)

fun contactCardPresentation(resolved: Boolean, friend: Boolean, pending: Boolean, self: Boolean, official: Boolean): ContactCardPresentation {
    val kind = when {
        !resolved -> ContactCardKind.LOADING
        pending && !self -> ContactCardKind.PENDING_REMOVAL
        friend || self -> ContactCardKind.FRIEND
        else -> ContactCardKind.STRANGER
    }
    return ContactCardPresentation(
        kind = kind,
        showCall = kind == ContactCardKind.FRIEND && !self && !official,
        
        showShare = kind == ContactCardKind.FRIEND,
        
        showMore = kind == ContactCardKind.FRIEND,
        showDelete = kind == ContactCardKind.PENDING_REMOVAL && !official,
        showFriendInfo = kind == ContactCardKind.FRIEND,
        
        showRemarkEdit = !self && kind != ContactCardKind.LOADING,
    )
}

data class ContactDetailUiState(
    val contactor: ContactorModel? = null,
    val isFriend: Boolean = true,
    val isRelationResolved: Boolean = false,
    val isWeakPending: Boolean = false,
    val isSelf: Boolean = false,
    val isOfficialAccount: Boolean = false,
    val displayName: String = "",
    val originalName: String? = null,
    val hasRemark: Boolean = false,
    val hasRemarkAvatar: Boolean = false,
    val originalAvatarJson: String? = null,
    val userId: String = "",
    val joinedAt: String? = null,
    val sourceDescribe: String? = null,
    val commonGroupsCount: Int = 0,
    val website: String? = null,
    val pendingRemovalDays: Int? = null,
    val messageExpirySeconds: Long = 0,
    val actionsEnabled: Boolean = true,
    val presentation: ContactCardPresentation = ContactCardPresentation(),
    val isEditingRemark: Boolean = false,
    val editingRemarkName: String = "",
    val quickFillName: String = "",
    val showQuickFill: Boolean = false,
)
