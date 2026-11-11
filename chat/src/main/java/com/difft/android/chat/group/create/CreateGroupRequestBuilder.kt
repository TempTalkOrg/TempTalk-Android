package com.difft.android.chat.group.create

import com.difft.android.base.utils.globalServices
import com.difft.android.chat.crypto.GroupCrypto
import com.difft.android.network.group.CreateGroupReq
import com.difft.android.network.group.GroupMemberBinding
import javax.inject.Inject





class CreateGroupRequestBuilder @Inject constructor() {

    class Built(val request: CreateGroupReq, val rGroup: ByteArray?)

    fun build(draft: CreateGroupOperation.Draft, avatarJson: String?): Built {
        if (!draft.encrypted) {
            return Built(CreateGroupReq(name = draft.name, numbers = draft.memberIds, avatar = avatarJson), null)
        }
        val rGroup = GroupCrypto.generateRGroup()
        val kGroup = GroupCrypto.deriveKGroup(rGroup)
        val skBind = GroupCrypto.deriveSkBind(rGroup)
        val pkBind = GroupCrypto.derivePkBind(rGroup)
        val allMembers = (draft.memberIds + globalServices.myId).distinct()
        val request = CreateGroupReq(
            name = null,
            numbers = draft.memberIds,
            avatar = null,
            groupCryptoMode = 1,
            encryptedName = GroupCrypto.encryptGroupName(kGroup, draft.name),
            encryptedAvatar = avatarJson?.let { GroupCrypto.encryptGroupAvatar(kGroup, it) },
            groupMemberVerifyPublicKey = GroupCrypto.pkBindToSpkiBase64(pkBind),
            memberBindings = allMembers.map { uid -> GroupMemberBinding(uid, GroupCrypto.signUid(skBind, uid)) },
        )
        return Built(request, rGroup)
    }
}
