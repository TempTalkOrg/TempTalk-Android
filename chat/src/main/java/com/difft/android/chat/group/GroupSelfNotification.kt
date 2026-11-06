package com.difft.android.chat.group

import com.difft.android.network.BaseResponse
import com.difft.android.network.group.ChangeSelfSettingsInGroupReq
import com.difft.android.network.group.GroupRepo
import com.tencent.wcdb.base.Value
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.wcdb

/** This user's notification fields in one group (the member row's `useGlobal` / `notification`). */
internal data class SelfNotification(val useGlobal: Boolean, val notification: Int)

/** Where the page reads and stores [SelfNotification]. Blocking: call on IO. */
internal interface SelfNotificationStore {
    fun read(groupId: String, myId: String): SelfNotification?
    fun write(groupId: String, myId: String, change: ChangeSelfSettingsInGroupReq)
}

/** The local group member row. */
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

/**
 * Sends this user's notification change for a group and, once the server accepted it, stores it
 * in the local member row too. The server does not echo the change back; the row would otherwise
 * keep the old value until the next group sync, and the page, which re-reads the row when the
 * write ends and on every resume, would show the old value again. Blocking IO (call on IO).
 */
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
