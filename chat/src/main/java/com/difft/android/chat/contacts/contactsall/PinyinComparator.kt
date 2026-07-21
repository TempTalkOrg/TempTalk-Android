package com.difft.android.chat.contacts.contactsall

import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.group.GroupMemberModel
import org.difft.app.database.models.ContactorModel

/**
 * Pinyin sort key for efficient sorting.
 * Letters (A-Z) come first, then non-letters (# group).
 */
// internal: shared with the mention package's candidate sorter for pinyin fallback ordering.
internal data class PinyinSortKey(val isNonLetter: Boolean, val pinyin: String) : Comparable<PinyinSortKey> {
    override fun compareTo(other: PinyinSortKey): Int {
        // Non-letters (# group) come after letters
        if (isNonLetter != other.isNonLetter) {
            return if (isNonLetter) 1 else -1
        }
        return pinyin.compareTo(other.pinyin)
    }
}

internal fun String.toPinyinSortKey(): PinyinSortKey {
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
 * Build the contacts list with weak-pending contacts placed in a single group at the very top,
 * followed by the normal A-Z list. Both groups are pinyin-sorted independently.
 *
 * A contact is "pending" iff its id is a key in [weakExpire]; that expireAt is carried on the
 * [ContactListItem] so it drives the countdown subtitle and the pending grouping (expireAt != null),
 * matching the detail screen's isWeakPending semantics. Pure function so it is unit-testable.
 *
 * The pending group is ordered by expireAt ascending (soonest removal first), ties broken by pinyin;
 * the normal group stays pinyin-sorted.
 */
internal fun buildContactListItems(
    contacts: List<ContactorModel>,
    weakExpire: Map<String, Long>,
): List<ContactListItem> {
    val (pending, normal) = contacts
        .distinctBy { it.id }
        .partition { weakExpire.containsKey(it.id) }
    val sortedPending = pending.sortedWith(
        compareBy({ weakExpire.getValue(it.id) }, { it.getDisplayNameForUI().toPinyinSortKey() })
    )
    return (sortedPending + normal.sortedByPinyin())
        .map { ContactListItem(it, weakExpire[it.id]) }
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