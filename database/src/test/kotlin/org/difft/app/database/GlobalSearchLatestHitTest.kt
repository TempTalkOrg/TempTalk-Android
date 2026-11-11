package org.difft.app.database

import org.difft.app.database.models.MessageModel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull





class GlobalSearchLatestHitTest {

    private fun message(id: String, room: String, shownAt: Long, sentAt: Long = shownAt) = MessageModel().apply {
        this.id = id
        roomId = room
        systemShowTimestamp = shownAt
        timeStamp = sentAt
    }

    @Test
    fun `each conversation keeps the match at its own newest timestamp`() {
        
        
        val candidates = listOf(
            message("a", "r1", shownAt = 300),
            message("b", "r2", shownAt = 300),
            message("c", "r2", shownAt = 200),
        )

        val latest = latestHitPerRoom(mapOf("r1" to 300L, "r2" to 200L), candidates)

        assertEquals("a", latest.getValue("r1").id)
        assertEquals("c", latest.getValue("r2").id)
    }

    @Test
    fun `two matches shown at the same time pick the later sent one`() {
        val candidates = listOf(message("old", "r1", shownAt = 100, sentAt = 1), message("new", "r1", shownAt = 100, sentAt = 2))

        assertEquals("new", latestHitPerRoom(mapOf("r1" to 100L), candidates).getValue("r1").id)
    }

    @Test
    fun `a conversation without a candidate has no latest match`() {
        assertNull(latestHitPerRoom(mapOf("r1" to 100L), listOf(message("x", "r9", shownAt = 100)))["r1"])
    }
}
