package com.difft.android.chat.util

import android.content.Context
import androidx.annotation.PluralsRes
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardNoticeData

/**
 * Renders the localized system-message text for a "forward notice" per PRD v1.0 §5.4.2.
 *
 * Design principles:
 *  - `myId` is an explicit parameter — Renderer never reads `globalServices.myId`.
 *    (1) Unit tests can construct without `GlobalStaticMockRule`.
 *    (2) As an `object`, avoids hidden global-state coupling (single responsibility).
 *  - Name resolution is performed via an injected resolver (no I/O inside Renderer),
 *    or via a pre-warmed [MessageContactsCacheUtil] in the convenience overload.
 *  - Author list formatting (5-author cap, locale separators, overflow suffix) is
 *    delegated to [SourceListFormatter] — the Renderer composes resolved names then
 *    hands them off. The plurals template owns the "from"/"来自" preamble.
 *  - The plurals key is chosen via [selectForwardPluralsResource] which branches on
 *    `combinedForwardMode` × count × useSelfOnly per PRD §5.4.2 (12 variants).
 *  - The legacy `ForwardNoticeData.Scene` field is intentionally NOT used for
 *    text selection anymore — PRD §5 keys text off `combinedForwardMode`. Scene
 *    remains on the data model for routing / older receivers.
 */
object ForwardNoticeRenderer {

    /**
     * Main entry point — caller supplies an id -> displayName resolver (no I/O) and `myId`.
     */
    fun render(
        operatorId: String,
        myId: String,
        notice: ForwardNoticeData,
        context: Context,
        resolveDisplayName: (String) -> String
    ): String {
        val operatorName = if (operatorId == myId) {
            context.getString(R.string.you)
        } else {
            resolveDisplayName(operatorId)
        }

        // Plurals quantity must be >= 1 — zero/negative counts are a business-layer
        // invariant violation but the Renderer defensively coerces to 1 so it never crashes.
        val quantity = notice.messageCount.coerceAtLeast(1)

        // Self-only shortcut only applies when BOTH sides are me:
        //   - operator == myId (I did the forwarding)
        //   - all distinct authors == [myId] (I forwarded my own messages)
        // If the peer forwards my messages (operator != myId, authors = [myId]),
        // keep the full "from You" wording.
        val distinctAuthors = notice.sourceAuthorIds.distinct()
        val useSelfOnly = operatorId == myId &&
            distinctAuthors.isNotEmpty() &&
            distinctAuthors.all { it == myId }

        val pluralsId = selectForwardPluralsResource(
            mode = notice.combinedForwardMode,
            useSelfOnly = useSelfOnly,
            count = quantity,
        )

        val body = if (useSelfOnly) {
            // Self-only templates do not reference %3$s — pass only (operator, count).
            context.resources.getQuantityString(pluralsId, quantity, operatorName, quantity)
        } else {
            val displayNames = distinctAuthors.map { id ->
                if (id == myId) context.getString(R.string.you) else resolveDisplayName(id)
            }
            val sourceList = SourceListFormatter.format(displayNames, context)
            context.resources.getQuantityString(pluralsId, quantity, operatorName, quantity, sourceList)
        }

        // Locale terminator (. / 。) — always appended. The locale-aware formatter no
        // longer emits ellipsis, so the renderer always closes the sentence properly.
        return body + context.getString(R.string.chat_forward_notice_sentence_terminator)
    }

    /**
     * Convenience overload backed by a pre-warmed [MessageContactsCacheUtil]
     * (synchronous, zero I/O). Use this from UI list binders.
     */
    fun render(
        operatorId: String,
        myId: String,
        notice: ForwardNoticeData,
        cache: MessageContactsCacheUtil,
        context: Context
    ): String = render(operatorId, myId, notice, context) { id ->
        cache.getContactor(id)?.getDisplayNameForUI() ?: id.formatBase58Id()
    }

    /**
     * Selects the plurals resource per PRD §5.4.2 mode × count × useSelfOnly matrix.
     * `when` over [CombinedForwardMode] is used as expression — compile-time
     * exhaustiveness, no catch-all `else`.
     *
     * Note (CONTAINS, count==1): a single-bubble selection that "contains a CF" means
     * that bubble IS the CF — so reuse the chat_history variant. Mixed plural only
     * fires when count > 1.
     *
     * Note (SUB, count > 1): PRD §5.4.3.1 marks detail-view multi-forward as Mac only;
     * Android still selects a sensible key (from_chat_history `other`) for safety.
     */
    @PluralsRes
    private fun selectForwardPluralsResource(
        mode: CombinedForwardMode,
        useSelfOnly: Boolean,
        count: Int,
    ): Int = when (mode) {
        CombinedForwardMode.UNKNOWN ->
            if (useSelfOnly) R.plurals.chat_forward_notice_self_only
            else R.plurals.chat_forward_notice
        CombinedForwardMode.CONTAINS_COMBINED_FORWARD ->
            when {
                count == 1 && useSelfOnly -> R.plurals.chat_forward_notice_chat_history_self_only
                count == 1 -> R.plurals.chat_forward_notice_chat_history
                useSelfOnly -> R.plurals.chat_forward_notice_plural_mixed_self_only
                else -> R.plurals.chat_forward_notice_plural_mixed
            }
        CombinedForwardMode.ALL_COMBINED_FORWARD ->
            if (useSelfOnly) R.plurals.chat_forward_notice_chat_history_self_only
            else R.plurals.chat_forward_notice_chat_history
        CombinedForwardMode.SUB_COMBINED_FORWARD ->
            if (useSelfOnly) R.plurals.chat_forward_notice_from_chat_history_self_only
            else R.plurals.chat_forward_notice_from_chat_history
    }
}
