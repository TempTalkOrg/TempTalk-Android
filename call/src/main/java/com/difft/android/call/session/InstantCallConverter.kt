package com.difft.android.call.session

import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.CallIntent
import com.difft.android.call.R
import com.difft.android.call.core.CallRoomController
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.manager.ContactorCacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Converts an in-progress call to an "instant" call: renames the active room,
 * updates the cached call-list entry, and flips the room-controller's call type.
 *
 * Factored out of the VM so the rename/type-mutation logic lives next to the
 * other session-level flows in [com.difft.android.call.session].
 */
class InstantCallConverter(
    private val scope: CoroutineScope,
    private val callDataManager: CallDataManager,
    private val contactorCacheManager: ContactorCacheManager,
    private val roomCtl: CallRoomController,
    private val callIntent: CallIntent,
    private val callRole: CallRole,
    private val mySelfId: String,
) {
    var currentRoomName: String = callIntent.roomName
        private set

    fun switchToInstantCall(roomId: String?) {
        val rid = roomId ?: return
        val callingListData = callDataManager.getCallListData()
        scope.launch(Dispatchers.IO) {
            if (rid.isEmpty() || !callingListData.containsKey(rid)) return@launch
            currentRoomName = if (callRole == CallRole.CALLER)
                "${contactorCacheManager.getDisplayNameById(mySelfId)}${getString(R.string.call_instant_call_title)}"
            else
                "${callIntent.roomName}${getString(R.string.call_instant_call_title)}"
            callingListData[rid]?.type = CallType.INSTANT.type
            callingListData[rid]?.callName = currentRoomName
            callDataManager.updateCallingListData(callingListData)
            roomCtl.updateCallType(CallType.INSTANT.type)
        }
    }
}
