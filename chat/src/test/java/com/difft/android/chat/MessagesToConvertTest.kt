package com.difft.android.chat

import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Test










class MessagesToConvertTest {

    @Test
    fun `window plus both anchors, anchors first and last`() {
        val rows = buildMessageSequence(count = 7)
        val behavior = ChatMessageListBehavior(
            messageList = rows.subList(1, 6),
            anchorMessageBefore = rows.first(),
            anchorMessageAfter = rows.last(),
        )

        val toConvert = behavior.messagesToConvert()

        assertEquals(7, toConvert.size)
        assertEquals(rows.map { it.id }, toConvert.map { it.id })
    }

    @Test
    fun `absent anchors contribute no null elements`() {
        val rows = buildMessageSequence(count = 3)
        val behavior = ChatMessageListBehavior(messageList = rows)

        val toConvert = behavior.messagesToConvert()

        assertEquals(rows.map { it.id }, toConvert.map { it.id })
    }

    @Test
    fun `only the before-anchor present`() {
        val rows = buildMessageSequence(count = 4)

        val toConvert = ChatMessageListBehavior(
            messageList = rows.drop(1),
            anchorMessageBefore = rows.first(),
        ).messagesToConvert()

        assertEquals(rows.map { it.id }, toConvert.map { it.id })
    }

    @Test
    fun `only the after-anchor present`() {
        val rows = buildMessageSequence(count = 4)

        val toConvert = ChatMessageListBehavior(
            messageList = rows.dropLast(1),
            anchorMessageAfter = rows.last(),
        ).messagesToConvert()

        assertEquals(rows.map { it.id }, toConvert.map { it.id })
    }

    @Test
    fun `an empty window with no anchors converts nothing`() {
        assertEquals(emptyList<String>(), ChatMessageListBehavior().messagesToConvert().map { it.id })
    }
}
