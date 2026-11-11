package com.difft.android.chat.contacts.groupcard

import android.app.Activity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallManager
import com.difft.android.call.manager.CallDataManager
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.chat.R
import com.difft.android.chat.call.LChatToCallController
import difft.android.messageserialization.For
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch


class GroupCardCallLauncher @Inject constructor(
    private val ongoing: OnGoingCallStateManager,
    private val calls: CallDataManager,
    private val controller: dagger.Lazy<LChatToCallController>,
) {
    fun launch(activity: Activity, gid: String, name: String, scope: CoroutineScope, onError: (String) -> Unit) {
        if (ongoing.isInCalling()) {
            if (ongoing.getConversationId() == gid) {
                LCallManager.bringCallScreenToFront(activity)
            } else {
                onError(activity.getString(R.string.call_is_calling_tip))
            }
            return
        }
        val call = calls.getCallDataByConversationId(gid)
        if (call != null) {
            scope.launch {
                if (!LCallManager.joinCall(activity.applicationContext, call)) {
                    L.w { "[GroupCard] call_join_failed gid=$gid" }
                    onError(activity.getString(com.difft.android.call.R.string.call_join_failed_tip))
                }
            }
        } else {
            controller.get().startCall(activity, For.Group(gid), name) { success, message ->
                if (!success) {
                    L.w { "[GroupCard] call_start_failed gid=$gid" }
                    message?.let(onError)
                }
            }
        }
    }
}
