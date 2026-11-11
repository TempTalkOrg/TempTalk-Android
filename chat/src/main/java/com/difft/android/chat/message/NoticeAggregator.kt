package com.difft.android.chat.message

import difft.android.messageserialization.model.CombinedForwardMode
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.TextMessage




object NoticeAggregator {

    









    fun computeCombinedForwardMode(
        messages: List<ChatMessage>,
        isSubContext: Boolean = false,
    ): CombinedForwardMode {
        if (isSubContext) return CombinedForwardMode.SUB_COMBINED_FORWARD
        if (messages.isEmpty()) return CombinedForwardMode.UNKNOWN

        val cfCount = messages.count { it.isCombinedForward() }
        return when {
            cfCount == 0 -> CombinedForwardMode.UNKNOWN
            cfCount == messages.size -> CombinedForwardMode.ALL_COMBINED_FORWARD
            else -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
        }
    }

    











    fun computeSortedSourceAuthorIds(messages: List<ChatMessage>, selfIdLast: String? = null): List<String> {
        if (messages.isEmpty()) return emptyList()
        return messages
            .groupBy { it.authorId }
            .map { (authorId, group) ->
                AuthorAggregate(
                    authorId = authorId,
                    isCombinedForwardSender = group.any { it.isCombinedForward() },
                    contributedCount = group.size,
                    latestTimestamp = group.maxOf { it.timeStamp },
                )
            }
            .sortedWith(selfLastComparator(selfIdLast))
            .map { it.authorId }
    }

    private data class AuthorAggregate(
        val authorId: String,
        val isCombinedForwardSender: Boolean,
        val contributedCount: Int,
        val latestTimestamp: Long,
    )

    
    
    
    
    

    
    fun computeCombinedForwardModeFromTextMessages(
        messages: List<TextMessage>,
        isSubContext: Boolean = false,
    ): CombinedForwardMode {
        if (isSubContext) return CombinedForwardMode.SUB_COMBINED_FORWARD
        if (messages.isEmpty()) return CombinedForwardMode.UNKNOWN

        val cfCount = messages.count { it.isCombinedForward() }
        return when {
            cfCount == 0 -> CombinedForwardMode.UNKNOWN
            cfCount == messages.size -> CombinedForwardMode.ALL_COMBINED_FORWARD
            else -> CombinedForwardMode.CONTAINS_COMBINED_FORWARD
        }
    }

    
    fun computeSortedSourceAuthorIdsFromTextMessages(messages: List<TextMessage>, selfIdLast: String? = null): List<String> {
        if (messages.isEmpty()) return emptyList()
        return messages
            .groupBy { it.fromWho.id }
            .map { (authorId, group) ->
                AuthorAggregate(
                    authorId = authorId,
                    isCombinedForwardSender = group.any { it.isCombinedForward() },
                    contributedCount = group.size,
                    latestTimestamp = group.maxOf { it.timeStamp },
                )
            }
            .sortedWith(selfLastComparator(selfIdLast))
            .map { it.authorId }
    }

    
    private fun selfLastComparator(selfIdLast: String?): Comparator<AuthorAggregate> =
        
        compareBy<AuthorAggregate> { selfIdLast != null && it.authorId == selfIdLast }
            .thenByDescending { it.isCombinedForwardSender }
            .thenByDescending { it.contributedCount }
            .thenByDescending { it.latestTimestamp }
            .thenBy { it.authorId }

    

    
    private const val MAX_FORWARD_DEPTH = 4

    
    fun copyCarriesForeignContentFromTextMessages(messages: List<TextMessage>, myId: String): Boolean =
        messages.any { it.forwardContext == null && it.fromWho.id != myId }

    






    fun copyCarriesForeignContent(messages: List<ChatMessage>, myId: String): Boolean =
        messages.any { !it.isCombinedForward() && carriesForeignOriginalAuthor((it as? TextChatMessage)?.forwardContext, it.authorId, myId) }

    



    fun forwardCarriesForeignContentFromTextMessages(messages: List<TextMessage>, myId: String): Boolean =
        messages.any { carriesForeignOriginalAuthor(it.forwardContext, it.fromWho.id, myId) }

    
    fun forwardCarriesForeignContent(messages: List<ChatMessage>, myId: String): Boolean =
        messages.any { carriesForeignOriginalAuthor((it as? TextChatMessage)?.forwardContext, it.authorId, myId) }

    



    private fun carriesForeignOriginalAuthor(forwardContext: ForwardContext?, bubbleAuthorId: String, myId: String): Boolean {
        val forwards = forwardContext?.forwards
        if (forwards.isNullOrEmpty()) return bubbleAuthorId != myId
        return leafAuthorsContainForeign(forwards, myId, depth = 1)
    }

    



    private fun leafAuthorsContainForeign(forwards: List<Forward>, myId: String, depth: Int): Boolean {
        if (depth > MAX_FORWARD_DEPTH) return true
        return forwards.any { fwd ->
            val nested = fwd.forwards
            if (nested.isNullOrEmpty()) fwd.author != myId
            else leafAuthorsContainForeign(nested, myId, depth + 1)
        }
    }
}







fun TextMessage.isCombinedForward(): Boolean {
    val forwards = forwardContext?.forwards ?: return false
    return forwards.size > 1
}
