package com.difft.android.chat.util

import android.content.Context
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import difft.android.messageserialization.model.ForwardNoticeData

/**
 * Renders the localized system-message text for a "forward notice".
 *
 * Design principles:
 *  - `myId` is an explicit parameter — Renderer never reads `globalServices.myId`.
 *    (1) Unit tests can construct without `GlobalStaticMockRule`.
 *    (2) As an `object`, avoids hidden global-state coupling (single responsibility).
 *    (3) All callers (LocalMessageCreator, list binders) already hold `myId`.
 *  - Name resolution is performed via an injected resolver (no I/O inside Renderer),
 *    or via a pre-warmed [MessageContactsCacheUtil] in the convenience overload.
 *  - The `when` over [ForwardNoticeData.Scene] is used as an expression (return value)
 *    so the compiler enforces exhaustiveness; adding a new Scene value without updating
 *    the mapping here fails compilation.
 */
object ForwardNoticeRenderer {

    /** Max number of distinct author names to spell out; remainder is collapsed to
     *  "...". Display-only cap — the persisted notice text already embeds the result
     *  of this rendering, and the raw id list is never stored, so no upstream layer
     *  needs to mirror this constant. */
    internal const val MAX_VISIBLE_AUTHORS = 3

    /** Suffix used when the author list is truncated. Also used to decide whether to
     *  append a sentence-ending period (ellipsis already closes the sentence). */
    private const val TRUNCATION_SUFFIX = "..."

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
        // keep the full "from You" wording — otherwise the peer's action would
        // read as though I did it. Mixed-author cases always use the full wording.
        val distinctAuthors = notice.sourceAuthorIds.distinct()
        val useSelfOnly = operatorId == myId &&
            distinctAuthors.isNotEmpty() &&
            distinctAuthors.all { it == myId }

        // `when` used as expression (return value) to force compile-time exhaustiveness.
        // All 4 scenes share one plurals resource today; if the text diverges per scene
        // later, split here (the compiler will still enforce exhaustive updates).
        val wasTruncated: Boolean
        val body = when (notice.scene) {
            ForwardNoticeData.Scene.SINGLE,
            ForwardNoticeData.Scene.ONE_BY_ONE,
            ForwardNoticeData.Scene.COMBINED,
            ForwardNoticeData.Scene.SAVE_TO_NOTES -> {
                if (useSelfOnly) {
                    wasTruncated = false   // no author list → no ellipsis
                    context.resources.getQuantityString(
                        R.plurals.chat_forward_notice_self_only,
                        quantity,
                        operatorName,
                        quantity
                    )
                } else {
                    // Display at most the first MAX_VISIBLE_AUTHORS distinct names.
                    // Dedup defends against peers sending repeated authors (e.g. the
                    // same user authored every forwarded message) — rendering
                    // "alice, alice, alice" would look broken. When longer, append an
                    // ellipsis (e.g. "a, b, c..."). The total messageCount in the text
                    // still reflects the full number of forwarded messages.
                    wasTruncated = distinctAuthors.size > MAX_VISIBLE_AUTHORS
                    val displayIds = distinctAuthors.take(MAX_VISIBLE_AUTHORS)
                    val resolvedNames = displayIds.joinToString(", ") { id ->
                        if (id == myId) context.getString(R.string.you) else resolveDisplayName(id)
                    }
                    val authorNames = if (wasTruncated) "$resolvedNames$TRUNCATION_SUFFIX" else resolvedNames
                    context.resources.getQuantityString(
                        R.plurals.chat_forward_notice,
                        quantity,
                        operatorName,
                        quantity,
                        authorNames
                    )
                }
            }
        }
        // When truncated, the ellipsis already closes the sentence — appending another
        // period would produce "...." (four dots). Otherwise we add the locale-appropriate
        // terminal punctuation (plurals text itself has none). We key off `wasTruncated`
        // (the authoritative cause), NOT `authorNames.endsWith("...")` — a resolved display
        // name that legitimately ends with "..." (unusual but possible user nickname) must
        // not falsely suppress the terminator.
        return if (wasTruncated) {
            body
        } else {
            body + context.getString(R.string.chat_forward_notice_sentence_terminator)
        }
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
}
