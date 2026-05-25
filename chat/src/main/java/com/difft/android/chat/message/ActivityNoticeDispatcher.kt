package com.difft.android.chat.message

import com.difft.android.PushActivityNoticeSendJobFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.dependencies.ApplicationDependencies
import difft.android.messageserialization.For
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared entry point for activity notices (current type: COPY). All copy surfaces
 * (chat list / preview / derived content) build their (forWhat, authorIds, count)
 * and call dispatchCopyNotice — wraps proto + Job enqueue.
 */
@Singleton
class ActivityNoticeDispatcher @Inject constructor(
    private val factory: PushActivityNoticeSendJobFactory,
) {
    fun dispatchCopyNotice(
        sourceConversation: For,
        sourceAuthorIds: List<String>,
        messageCount: Int = 1,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        if (sourceAuthorIds.isEmpty() || messageCount <= 0) return
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
