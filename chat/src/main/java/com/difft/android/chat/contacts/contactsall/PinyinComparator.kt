package com.difft.android.chat.contacts.contactsall

import com.difft.android.base.widget.sideBar.CharacterParser
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.group.GroupMemberModel
import org.difft.app.database.models.ContactorModel






internal data class PinyinSortKey(val isNonLetter: Boolean, val pinyin: String) : Comparable<PinyinSortKey> {
    override fun compareTo(other: PinyinSortKey): Int {
        
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






@JvmName("sortedContactsByPinyin")
fun List<ContactorModel>.sortedByPinyin(): List<ContactorModel> {
    return sortedWith(compareBy { it.getDisplayNameForUI().toPinyinSortKey() })
}




@JvmName("sortedGroupMembersByPinyin")
fun List<GroupMemberModel>.sortedByPinyin(): List<GroupMemberModel> {
    return sortedWith(compareBy { (it.name ?: "").toPinyinSortKey() })
}




fun List<GroupMemberModel>.sortedByRoleThenPinyin(): List<GroupMemberModel> {
    return sortedWith(compareBy({ it.role }, { (it.name ?: "").toPinyinSortKey() }))
}
