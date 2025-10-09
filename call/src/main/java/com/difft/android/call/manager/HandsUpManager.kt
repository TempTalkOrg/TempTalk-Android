package com.difft.android.call.manager

import android.content.Context
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallManager
import com.difft.android.call.data.HandUpUserData
import com.difft.android.call.data.HandUpUserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HandsUpManager(private val scope: CoroutineScope, private val context: Context) {
    private val _handsUpUserInfo = MutableStateFlow<List<HandUpUserInfo>>(emptyList())
    val handsUpUserInfo: StateFlow<List<HandUpUserInfo>> = _handsUpUserInfo


    /**
     * Updates the list of participants who have raised their hands (requested to speak).
     *
     * This method processes the incoming hand-raise requests, sorts them by timestamp,
     * and transforms the raw data into a formatted list of user information including
     * display name and avatar.
     */
    fun updateHandsUpParticipants(
        hands: List<HandUpUserData>?,
    ) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val sorted = hands?.sortedBy { it.ts }?.map { it.identity } ?: emptyList()
                if (sorted.isEmpty()) {
                    _handsUpUserInfo.value = emptyList()
                    return@launch
                }
                val list = sorted.map { id ->
                    val userName = LCallManager.getDisplayNameById(id) ?: id
                    val userAvatar = LCallManager.getAvatarByUid(context, id)
                        ?: LCallManager.createAvatarByNameOrUid(context, userName, id)

                    HandUpUserInfo(userName = userName, userAvatar = userAvatar, userId = id)
                }
                _handsUpUserInfo.value = list
            }.onFailure {
                L.e { "[Call] HandsUpManager updateHandsUpParticipants error = ${it.message}" }
            }
        }
    }

}