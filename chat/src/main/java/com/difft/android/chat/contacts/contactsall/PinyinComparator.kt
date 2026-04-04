package com.difft.android.chat.contacts.contactsall

import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.group.GroupMemberModel
import org.difft.app.database.models.ContactorModel

/**
 * Pinyin sort key for efficient sorting.
 * Letters (A-Z) come first, then non-letters (# group).
 */
private data class PinyinSortKey(val isNonLetter: Boolean, val pinyin: String) : Comparable<PinyinSortKey> {
    override fun compareTo(other: PinyinSortKey): Int {
        // Non-letters (# group) come after letters
        if (isNonLetter != other.isNonLetter) {
            return if (isNonLetter) 1 else -1
        }
        return pinyin.compareTo(other.pinyin)
    }
}

private fun String.toPinyinSortKey(): PinyinSortKey {
    val pinyin = CharacterParser.getSelling(this).lowercase()
    val isNonLetter = pinyin.firstOrNull()?.let { it !in 'a'..'z' } ?: true
    return PinyinSortKey(isNonLetter, pinyin)
}

/**
 * Sort contacts by pinyin with optimal performance.
 * Each contact's pinyin is calculated only once (cached by compareBy).
 * Letters (A-Z) come first, then non-letters (# group).
 */
@JvmName("sortedContactsByPinyin")
fun List<ContactorModel>.sortedByPinyin(): List<ContactorModel> {
    return sortedWith(compareBy { it.getDisplayNameForUI().toPinyinSortKey() })
}

/**
 * Sort group members by pinyin with optimal performance.
 */
@JvmName("sortedGroupMembersByPinyin")
fun List<GroupMemberModel>.sortedByPinyin(): List<GroupMemberModel> {
    return sortedWith(compareBy { (it.name ?: "").toPinyinSortKey() })
}

/**
 * Sort group members by role first, then by pinyin.
 */
fun List<GroupMemberModel>.sortedByRoleThenPinyin(): List<GroupMemberModel> {
    return sortedWith(compareBy({ it.role }, { (it.name ?: "").toPinyinSortKey() }))
}