package com.difft.android.chat.contacts.contactsall

import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.chat.contacts.data.getSortLetter
import com.difft.android.chat.group.GroupMemberModel
import org.difft.app.database.models.ContactorModel

/**
 *
 */
class PinyinComparator : Comparator<ContactorModel> {
    override fun compare(o1: ContactorModel, o2: ContactorModel): Int {
        return if (o1.getDisplayNameForUI().getSortLetter() == "@" || o2.getDisplayNameForUI().getSortLetter() == "#") {
            -1
        } else if (o1.getDisplayNameForUI().getSortLetter() == "#" || o2.getDisplayNameForUI().getSortLetter() == "@") {
            1
        } else {
            o1.getDisplayNameForUI().getSortLetter().compareTo(o2.getDisplayNameForUI().getSortLetter())
        }
    }
}

class PinyinComparator2 : Comparator<GroupMemberModel> {
    override fun compare(o1: GroupMemberModel, o2: GroupMemberModel): Int {
        return if (o1.sortLetters == "@" || o2.sortLetters == "#") {
            -1
        } else if (o1.sortLetters == "#" || o2.sortLetters == "@") {
            1
        } else {
            o1.sortLetters!!.compareTo(o2.sortLetters!!)
        }
    }
}

/**
 * GroupMemberModel 排序比较器：先按 role 排序，再按拼音排序
 */
class GroupMemberRoleComparator : Comparator<GroupMemberModel> {
    override fun compare(m1: GroupMemberModel, m2: GroupMemberModel): Int {
        // First compare by role
        val roleCompare = m1.role.compareTo(m2.role)
        return if (roleCompare != 0) {
            // role 值小的排在前面
            roleCompare
        } else {
            // If roles are the same, compare by name using PinyinComparator2
            PinyinComparator2().compare(m1, m2)
        }
    }
}