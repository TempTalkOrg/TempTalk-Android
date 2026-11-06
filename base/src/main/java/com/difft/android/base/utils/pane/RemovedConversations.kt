package com.difft.android.base.utils.pane

/**
 * Conversations that stopped existing (a removed friend) while the home screen may still show
 * them, process-wide. Closing them is asked of the home navigator right away, but that request
 * can be lost: the Activity may be recreated during the one-pane close slide, or while the close
 * waits for state to be restorable. The navigator therefore re-checks this list after a restore
 * and on every stack change, closes the pages about a listed id at any depth (the whole stack when
 * its root is about it), and drops the entry once no page is about it. Opening the conversation, or
 * a page about it, again (the user chose to) drops it too.
 * Main thread only.
 */
object RemovedConversations {

    private val ids = LinkedHashSet<String>()

    fun add(conversationId: String) {
        ids += conversationId
    }

    fun contains(conversationId: String): Boolean = conversationId in ids

    fun snapshot(): List<String> = ids.toList()

    /** Handled (no stack shows it any more) or reopened by the user. */
    fun remove(conversationId: String) {
        ids -= conversationId
    }
}
