package com.difft.android.chat.group

import com.difft.android.network.BaseResponse
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import com.tencent.wcdb.base.Value
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb


internal data class SelfNotification(val useGlobal: Boolean, val notification: Int)


internal interface SelfNotificationStore {
    fun read(groupId: String, myId: String): SelfNotification?
    fun write(groupId: String, myId: String, change: ChangeSelfSettingsInGroupReq)
}


internal object WcdbSelfNotificationStore : SelfNotificationStore {

    override fun read(groupId: String, myId: String): SelfNotification? =
        wcdb.groupMemberContactor.getFirstObject(where(groupId, myId))
            ?.let { SelfNotification(useGlobal = it.useGlobal ?: false, notification = it.notification ?: 0) }

    override fun write(groupId: String, myId: String, change: ChangeSelfSettingsInGroupReq) {
        change.useGlobal?.let { wcdb.groupMemberContactor.updateValue(Value(it), DBGroupMemberContactorModel.useGlobal, where(groupId, myId)) }
        change.notification?.let { wcdb.groupMemberContactor.updateValue(it, DBGroupMemberContactorModel.notification, where(groupId, myId)) }
    }

    private fun where(groupId: String, myId: String) =
        DBGroupMemberContactorModel.gid.eq(groupId).and(DBGroupMemberContactorModel.id.eq(myId))
}







internal suspend fun changeSelfNotification(
    repo: GroupRepo,
    store: SelfNotificationStore,
    groupId: String,
    myId: String,
    change: ChangeSelfSettingsInGroupReq,
): BaseResponse<Any> {
    val response = repo.changeSelfSettingsInGroup(groupId, myId, change)
    if (response.status == 0) store.write(groupId, myId, change)
    return response
}
