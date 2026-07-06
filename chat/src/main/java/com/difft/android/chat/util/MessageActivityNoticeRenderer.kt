package com.difft.android.chat.util

import android.content.Context
import androidx.annotation.PluralsRes
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.MessageActivityNoticeData

/**
 * Renders the localized system-message text for a "message activity notice" per
 * PRD v1.0 §5.4.1. Current activity types: COPY (this iteration).
 *
 * Design mirrors [ForwardNoticeRenderer]:
 *  - `myId` is an explicit parameter — renderer never reads `globalServices.myId`.
 *  - Name resolution via injected resolver (no I/O) or pre-warmed cache overload.
 *  - Author list formatting (5-author cap, locale separators, overflow) delegated
 *    to [SourceListFormatter]; the plurals template owns the "from"/"来自" preamble.
 *  - The `when` over [MessageActivityNoticeData.Type] is an expression → compile-time
 *    exhaustiveness; adding a new Type without updating here fails to compile.
 *  - Plurals key chosen via [selectCopyPluralsResource] which branches on
 *    `combinedForwardMode` × useSelfOnly per PRD §5.4.1 (4 variants: main-conv
 *    UNKNOWN/CONTAINS/ALL collapse to the same key; SUB diverges).
 */
object MessageActivityNoticeRenderer {

    fun render(
        operatorId: String,
        myId: String,
        notice: MessageActivityNoticeData,
        context: Context,
        resolveDisplayName: (String) -> String,
    ): String {
        val operatorName = if (operatorId == myId) {
            context.getString(R.string.you)
        } else {
            resolveDisplayName(operatorId)
        }

        // Plurals quantity must be >= 1 — defensively coerce so the renderer never
        // crashes on a malformed payload (1 is the natural minimum).
        val quantity = notice.messageCount.coerceAtLeast(1)

        val distinctAuthors = notice.sourceAuthorIds.distinct()
        val useSelfOnly = operatorId == myId &&
            distinctAuthors.isNotEmpty() &&
            distinctAuthors.all { it == myId }

        val body = when (notice.type) {
            MessageActivityNoticeData.Type.COPY -> {
                val pluralsId = selectCopyPluralsResource(
                    mode = notice.combinedForwardMode,
                    useSelfOnly = useSelfOnly,
                )
                if (useSelfOnly) {
                    context.resources.getQuantityString(pluralsId, quantity, operatorName, quantity)
                } else {
                    val displayNames = distinctAuthors.map { id ->
                        if (id == myId) context.getString(R.string.you) else resolveDisplayName(id)
                    }
                    val sourceList = SourceListFormatter.format(displayNames, context)
                    context.resources.getQuantityString(
                        pluralsId, quantity, operatorName, quantity, sourceList
                    )
                }
            }
        }

        return body + context.getString(R.string.chat_copy_notice_sentence_terminator)
    }

    /**
     * Convenience overload backed by a pre-warmed [MessageContactsCacheUtil]. Uses
     * [getDisplayNameForUI] (remark-priority) consistent with the app's normal
     * system-message display conventions.
     */
    fun render(
        operatorId: String,
        myId: String,
        notice: MessageActivityNoticeData,
        cache: MessageContactsCacheUtil,
        context: Context,
    ): String = render(operatorId, myId, notice, context) { id ->
        cache.getContactor(id)?.getDisplayNameForUI() ?: id.formatBase58Id()
    }

    /**
     * Selects the COPY plurals resource per PRD §5.4.1. Main-conv copy
     * (UNKNOWN/CONTAINS/ALL) collapses to the same plural family — PRD does
     * NOT distinguish copy text by CF involvement on the main conv path.
     * Only SUB_COMBINED_FORWARD (copy from inside a CF detail view) diverges.
     *
     * `when` over [CombinedForwardMode] is used as expression — compile-time
     * exhaustiveness, no catch-all `else`.
     */
    @PluralsRes
    private fun selectCopyPluralsResource(
        mode: CombinedForwardMode,
        useSelfOnly: Boolean,
    ): Int = when (mode) {
        CombinedForwardMode.UNKNOWN,
        CombinedForwardMode.CONTAINS_COMBINED_FORWARD,
        CombinedForwardMode.ALL_COMBINED_FORWARD ->
            if (useSelfOnly) R.plurals.chat_copy_notice_self_only else R.plurals.chat_copy_notice
        CombinedForwardMode.SUB_COMBINED_FORWARD ->
            if (useSelfOnly) R.plurals.chat_copy_notice_from_chat_history_self_only
            else R.plurals.chat_copy_notice_from_chat_history
    }
}
