package com.difft.android.chat.message

import com.difft.android.PushActivityNoticeSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import javax.inject.Inject
import javax.inject.Singleton






@Singleton
class ActivityNoticeDispatcher @Inject constructor(
    private val factory: PushActivityNoticeSendJobFactory,
) {
    fun dispatchCopyNotice(
        sourceConversation: For,
        sourceAuthorIds: List<String>,
        
        
        
        myId: String,
        messageCount: Int = 1,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        if (sourceAuthorIds.isEmpty() || messageCount <= 0) return
        
        if (sourceConversation.id == myId) {
            L.i { "[ActivityNotice] skip copy notice — Saved conversation (conv=${sourceConversation.id})" }
            return
        }
        L.i { "[ActivityNotice] dispatchCopyNotice conv=${sourceConversation.id} authors=${sourceAuthorIds.size} count=$messageCount mode=$combinedForwardMode" }
        val noticeData = MessageActivityNoticeData(
            type = MessageActivityNoticeData.Type.COPY,
            sourceAuthorIds = sourceAuthorIds,
            messageCount = messageCount,
            combinedForwardMode = combinedForwardMode,
        )
        ApplicationDependencies.getJobManager().add(
            factory.create(null, sourceConversation, noticeData)
        )
        L.i { "[ActivityNotice] enqueued PushActivityNoticeSendJob conv=${sourceConversation.id} count=$messageCount mode=$combinedForwardMode" }
    }
}
