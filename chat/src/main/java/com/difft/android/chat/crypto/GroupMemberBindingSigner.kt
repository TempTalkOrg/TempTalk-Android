package com.difft.android.chat.crypto

import com.difft.android.network.group.GroupMemberBinding
import javax.inject.Inject


class GroupMemberBindingSigner @Inject constructor() {
    fun sign(key: ByteArray, ids: List<String>): List<GroupMemberBinding> {
        val signingKey = GroupCrypto.deriveSkBind(key)
        return ids.map { GroupMemberBinding(it, GroupCrypto.signUid(signingKey, it)) }
    }
}
