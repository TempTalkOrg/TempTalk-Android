package com.difft.android.chat.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for resolveByMessageId (design-report §8.3, H8/H9) — the RecyclerView-reuse-safe
 * re-query behind [ChatMessageListFragment.currentItemView]. Extracted to a pure generic so the
 * position-hit / position-miss semantics are testable without a full Robolectric Fragment harness:
 * ChatMessageListFragment's Hilt graph makes direct instantiation impractical, and design §7.2/§8.4
 * authorizes lifting the helper logic to a testable location. The tested risk is exactly §7.2's
 * reuse-safe re-query — resolve by id-derived position at click time, null on miss (no stale view).
 */
class ResolveByMessageIdTest {

    @Test
    fun H8_positionHit_returnsReQueriedItem() {
        val currentList = listOf("a", "b", "c")
        var itemAtCalledWith = -1
        val result = resolveByMessageId(
            messageId = "b",
            indexOf = { id -> currentList.indexOf(id) },
            itemAt = { pos -> itemAtCalledWith = pos; "view@$pos" }
        )
        // Re-queried at the id's *current* position (not a captured view).
        assertEquals(1, itemAtCalledWith)
        assertEquals("view@1", result)
    }

    @Test
    fun H9_positionMiss_returnsNull_andItemAtNotCalled() {
        val currentList = listOf("a", "b", "c")
        var itemAtCalled = false
        val result = resolveByMessageId<String>(
            messageId = "missing",
            indexOf = { id -> currentList.indexOf(id) }, // -1
            itemAt = { itemAtCalled = true; "should-not-happen" }
        )
        assertNull(result)
        assertFalse(itemAtCalled)
    }
}
