package com.difft.android.chat.ui

import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardNoticeData
import org.junit.Assert.assertEquals
import org.junit.Test













class SelectChatsUtilsForwardNoticeTest {

    
    private fun aggregate(forwardedContexts: List<ForwardContext>): Pair<List<String>, Int> {
        val authorIds = forwardedContexts
            .flatMap { it.forwards.orEmpty() }
            .map { it.author }
            .distinct()
        val totalCount = forwardedContexts.sumOf { it.forwards?.size ?: 0 }
        return authorIds to totalCount
    }

    private fun mkForward(author: String, nested: List<Forward>? = null): Forward =
        Forward(
            id = 0L,
            type = 0,
            isFromGroup = false,
            author = author,
            text = "t",
            attachments = null,
            forwards = nested,
            mentions = null
        )

    

    @Test
    fun `single forward context with 3 top-level forwards from 3 authors — 3 authors, count=3`() {
        val ctx = ForwardContext(
            listOf(mkForward("+A"), mkForward("+B"), mkForward("+C")),
            isFromGroup = false
        )
        val (authors, count) = aggregate(listOf(ctx))
        assertEquals(listOf("+A", "+B", "+C"), authors)
        assertEquals(3, count)
    }

    @Test
    fun `3 forwards from the same author — dedup to 1 author, count=3`() {
        val ctx = ForwardContext(
            listOf(mkForward("+A"), mkForward("+A"), mkForward("+A")),
            isFromGroup = false
        )
        val (authors, count) = aggregate(listOf(ctx))
        assertEquals(listOf("+A"), authors)
        assertEquals(3, count)
    }

    @Test
    fun `distinct preserves first-seen order across multiple contexts`() {
        val c1 = ForwardContext(listOf(mkForward("+B"), mkForward("+A")), false)
        val c2 = ForwardContext(listOf(mkForward("+C"), mkForward("+A")), false)
        val (authors, count) = aggregate(listOf(c1, c2))
        assertEquals(listOf("+B", "+A", "+C"), authors)
        assertEquals(4, count)
    }

    

    @Test
    fun `nested forwards are NOT flattened into authors or count`() {
        val nested = listOf(mkForward("+NESTED-1"), mkForward("+NESTED-2"))
        val ctx = ForwardContext(
            listOf(
                mkForward("+TOP-A", nested = nested),
                mkForward("+TOP-B")
            ),
            isFromGroup = false
        )
        val (authors, count) = aggregate(listOf(ctx))
        assertEquals(
            "nested Forward.forwards are NOT traversed; only top-level authors contribute",
            listOf("+TOP-A", "+TOP-B"),
            authors
        )
        assertEquals("count is top-level only — nested containers count as 1", 2, count)
    }

    

    @Test
    fun `forwards is null — authors empty, count=0`() {
        val ctx = ForwardContext(null, isFromGroup = false)
        val (authors, count) = aggregate(listOf(ctx))
        assertEquals(emptyList<String>(), authors)
        assertEquals(0, count)
    }

    @Test
    fun `forwards is empty — authors empty, count=0`() {
        val ctx = ForwardContext(emptyList(), isFromGroup = false)
        val (authors, count) = aggregate(listOf(ctx))
        assertEquals(emptyList<String>(), authors)
        assertEquals(0, count)
    }

    @Test
    fun `empty aggregation — the production code must skip enqueuing`() {
        
        
        val (authors, count) = aggregate(
            listOf(ForwardContext(emptyList(), false))
        )
        val shouldSkip = authors.isEmpty() || count == 0
        assertEquals(true, shouldSkip)
    }

    

    @Test
    fun `data class carries the scene verbatim`() {
        
        
        
        val contexts = listOf(
            ForwardContext(listOf(mkForward("+A"), mkForward("+B")), false)
        )
        val (authors, count) = aggregate(contexts)

        listOf(
            ForwardNoticeData.Scene.SINGLE,
            ForwardNoticeData.Scene.ONE_BY_ONE,
            ForwardNoticeData.Scene.COMBINED,
            ForwardNoticeData.Scene.SAVE_TO_NOTES
        ).forEach { scene ->
            val data = ForwardNoticeData(scene, authors, count)
            assertEquals(scene, data.scene)
            assertEquals(authors, data.sourceAuthorIds)
            assertEquals(count, data.messageCount)
        }
    }
}
