package com.difft.android.chat

import org.difft.app.database.test.builders.buildMessageSequence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Case #35 — `ChatMessageListBehavior.messagesToConvert()`, the single owner of the set of messages
 * an emission needs child-table data for (window + both invisible anchors).
 *
 * Why the anchors must be in it: they are converted by `generateMessageTwo` too (they drive the
 * first row's day header / name and the last row's time), so an anchor missing from the hydration
 * `IN` set would be decorated from empty sub-data — a forwarded anchor would compare unequal to the
 * same row rendered inside the window and DiffUtil would rebind the whole edge.
 */
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
