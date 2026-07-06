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
        // The operator's own id, supplied by the caller (which already holds globalServices.myId).
        // Passed in rather than read here so this @Singleton stays stateless and unit-testable
        // without static-mocking the global accessor.
        myId: String,
        messageCount: Int = 1,
        combinedForwardMode: CombinedForwardMode = CombinedForwardMode.UNKNOWN,
    ) {
        if (sourceAuthorIds.isEmpty() || messageCount <= 0) return
        // PRD v2.0 §改动1 条件②: copying inside the user's own Saved (note-to-self) conversation has no
        // other audience → never trace. This is NOT subsumed by the author gating: a message saved to
        // Notes keeps its original (foreign) author in forwardContext, so original-author gating would
        // otherwise fire here. Central guard mirrors the forward path's guard in sendForwardNotice.
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
