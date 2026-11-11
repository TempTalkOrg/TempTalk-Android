package com.difft.android.chat.util

import android.content.Context
import androidx.annotation.PluralsRes
import com.difft.android.chat.MessageContactsCacheUtil
import com.difft.android.chat.R
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.ForwardNoticeData









object ForwardNoticeRenderer {

    


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

        
        
        val quantity = notice.messageCount.coerceAtLeast(1)

        
        
        
        
        
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
            
            context.resources.getQuantityString(pluralsId, quantity, operatorName, quantity)
        } else {
            val displayNames = distinctAuthors.map { id ->
                if (id == myId) context.getString(R.string.you) else resolveDisplayName(id)
            }
            val sourceList = SourceListFormatter.format(displayNames, context)
            context.resources.getQuantityString(pluralsId, quantity, operatorName, quantity, sourceList)
        }

        
        return body + context.getString(R.string.chat_forward_notice_sentence_terminator)
    }

    



    fun render(
        operatorId: String,
        myId: String,
        notice: ForwardNoticeData,
        cache: MessageContactsCacheUtil,
        context: Context
    ): String = render(operatorId, myId, notice, context) { id ->
        cache.getContactor(id)?.getDisplayNameForUI() ?: id.formatBase58Id()
    }

    




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
