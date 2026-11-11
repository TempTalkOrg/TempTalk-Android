package org.difft.app.database.hydration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test











class MessageChildRowChunkingTest {

    @Test
    fun `a key set larger than the chunk size is split by key and concatenated in order`() {
        val keys = (1..2 * IN_CHUNK_SIZE + 200).map { "k$it" }
        val chunks = mutableListOf<List<String>>()

        val rows = chunkKeys(keys) { chunk ->
            chunks += chunk
            
            chunk.flatMap { listOf("$it-a", "$it-b") }
        }

        assertEquals(listOf(IN_CHUNK_SIZE, IN_CHUNK_SIZE, 200), chunks.map { it.size })
        assertEquals(keys, chunks.flatten())
        assertEquals(keys.flatMap { listOf("$it-a", "$it-b") }, rows)
        assertTrue(
            "every key must appear in exactly one chunk",
            chunks.flatten().toSet().size == keys.size,
        )
    }

    @Test
    fun `a key set at or below the chunk size issues a single query`() {
        val keys = (1..IN_CHUNK_SIZE).map { "k$it" }
        var calls = 0

        val rows = chunkKeys(keys) { chunk ->
            calls++
            chunk
        }

        assertEquals(1, calls)
        assertEquals(keys, rows)
    }

    @Test
    fun `an empty key set issues no query`() {
        var calls = 0

        val rows = chunkKeys(emptyList<String>()) { chunk ->
            calls++
            chunk
        }

        assertEquals(0, calls)
        assertEquals(emptyList<String>(), rows)
    }
}
