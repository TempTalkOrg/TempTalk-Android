package com.difft.android.chat.focus

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.di.AppCoroutineScope
import com.difft.android.websocket.api.AppWebSocketHelper
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class GroupFocusSocketSync @Inject constructor(
    private val appWebSocketHelper: AppWebSocketHelper,
    private val repository: dagger.Lazy<GroupFocusRepository>,
    @param:AppCoroutineScope private val scope: CoroutineScope,
) {
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            appWebSocketHelper.chatDataWebSocketConnection.webSocketConnectionState
                .filter { it == WebSocketConnectionState.CONNECTED }
                .collect {
                    L.i { "[GroupFocus] socket connected, refreshing" }
                    repository.get().requestRefresh("ws-connected")
                }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}
