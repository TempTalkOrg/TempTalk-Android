package com.difft.android.chat.message

import org.junit.Assert.assertEquals
import org.junit.Test














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

        
        val EQUALS_COMPARED = setOf(
            "id", "authorId", "isMine", "sendStatus", "timeStamp", "systemShowTimestamp",
            "readMaxSId", "notifySequenceId", "selectedStatus", "editMode", "mode", "showName",
            "showTime", "showDayTime", "showNewMsgDivider",
            "message", "attachment", "quote", "forwardContext", "mentions", "reactions",
            "sharedContacts", "readStatus", "readContactNumber", "translateData",
            "speechToTextData", "playStatus", "criticalAlertType", "isScreenShotMessage",
        )

        



        val EQUALS_EXCLUDED = setOf("forWhat", "sourceAuthorOverride", "sourceMode")
    }
}
