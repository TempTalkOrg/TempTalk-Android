package com.difft.android.base.utils.pane











object RemovedConversations {

    private val ids = LinkedHashSet<String>()

    fun add(conversationId: String) {
        ids += conversationId
    }

    fun contains(conversationId: String): Boolean = conversationId in ids

    fun snapshot(): List<String> = ids.toList()

    
    fun remove(conversationId: String) {
        ids -= conversationId
    }
}
