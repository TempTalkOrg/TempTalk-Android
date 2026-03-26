package com.difft.android.chat.recent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object RecentChatUtil {
    private val mChatDoubleTabSubject = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    fun emitChatDoubleTab() = mChatDoubleTabSubject.tryEmit(Unit)

    val chatDoubleTab: SharedFlow<Unit> = mChatDoubleTabSubject
}
