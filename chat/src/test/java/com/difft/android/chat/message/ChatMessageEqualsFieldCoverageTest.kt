package com.difft.android.chat.message

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #30 — drift guard for the `equals` contracts of [ChatMessage] and [TextChatMessage].
 *
 * The batch child-row hydration argues "the hydrated ChatMessage is field-for-field equal to the
 * point-query ChatMessage" by asserting `assertEquals` on whole ChatMessage instances. That is
 * only as strong as `equals` is complete: a field that `equals` ignores is a field the equivalence
 * cases cannot see, and it is also a field `DiffUtil` cannot see, so a change to it never rebinds
 * the row.
 *
 * Reflection cannot read the body of `equals`, so this pins the declared-field sets instead.
 * Adding a field turns this red, and the fix is a deliberate decision: extend `equals`/`hashCode`
 * and add the name below, or add it to [EQUALS_EXCLUDED] with the reason.
 */
class ChatMessageEqualsFieldCoverageTest {

    @Test
    fun `ChatMessage declared fields are pinned`() {
        assertEquals(CHAT_MESSAGE_FIELDS, declaredFieldsOf(ChatMessage::class.java))
    }

    @Test
    fun `TextChatMessage declared fields are pinned`() {
        assertEquals(TEXT_CHAT_MESSAGE_FIELDS, declaredFieldsOf(TextChatMessage::class.java))
    }

    @Test
    fun `every pinned field is either compared by equals or explicitly excluded`() {
        val all = CHAT_MESSAGE_FIELDS + TEXT_CHAT_MESSAGE_FIELDS
        assertEquals(all, EQUALS_COMPARED + EQUALS_EXCLUDED)
        assertEquals(
            "a field cannot be both compared and excluded",
            emptySet<String>(),
            EQUALS_COMPARED intersect EQUALS_EXCLUDED,
        )
    }

    /**
     * Drops compiler-generated members: JVM synthetics, and the Compose compiler's `$stable`
     * stability marker (a real static field, not a synthetic one) that `:chat` gets on every class.
     */
    private fun declaredFieldsOf(type: Class<*>): Set<String> = type.declaredFields
        .filterNot { it.isSynthetic || it.name.startsWith("$") }
        .map { it.name }
        .toSet()

    private companion object {

        val CHAT_MESSAGE_FIELDS = setOf(
            "id", "authorId", "isMine", "forWhat", "sourceAuthorOverride", "sourceMode",
            "sendStatus", "timeStamp", "systemShowTimestamp", "readMaxSId", "notifySequenceId",
            "selectedStatus", "editMode", "mode", "showName", "showTime", "showDayTime",
            "showNewMsgDivider",
        )

        val TEXT_CHAT_MESSAGE_FIELDS = setOf(
            "message", "attachment", "quote", "forwardContext", "mentions", "reactions",
            "sharedContacts", "readStatus", "readContactNumber", "translateData",
            "speechToTextData", "playStatus", "criticalAlertType", "isScreenShotMessage",
        )

        /** Mirrors the comparisons in `ChatMessage.equals` + `TextChatMessage.equals`. */
        val EQUALS_COMPARED = setOf(
            "id", "authorId", "isMine", "sendStatus", "timeStamp", "systemShowTimestamp",
            "readMaxSId", "notifySequenceId", "selectedStatus", "editMode", "mode", "showName",
            "showTime", "showDayTime", "showNewMsgDivider",
            "message", "attachment", "quote", "forwardContext", "mentions", "reactions",
            "sharedContacts", "readStatus", "readContactNumber", "translateData",
            "speechToTextData", "playStatus", "criticalAlertType", "isScreenShotMessage",
        )

        /**
         * `@Transient` authoring metadata set by the combined-forward detail view, never part of
         * conversation-list identity — comparing them would rebind rows on a view-local override.
         */
        val EQUALS_EXCLUDED = setOf("forWhat", "sourceAuthorOverride", "sourceMode")
    }
}
