package com.difft.android.chat.pagination

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test





class PageLoadDecisionTest {

    
    
    @Test
    fun `the top threshold fires inside the edge band and never on NO_POSITION`() {
        val expected = mapOf(
            RecyclerView.NO_POSITION to false,
            0 to true,
            PREFETCH_EDGE_ROWS - 1 to true,
            PREFETCH_EDGE_ROWS to false,
            PREFETCH_EDGE_ROWS + 1 to false,
        )
        expected.forEach { (firstVisible, loadOlder) ->
            val decision = decide(firstVisible = firstVisible)
            assertEquals("firstVisible=$firstVisible", loadOlder, decision.loadOlder)
        }
    }

    
    
    
    @Test
    fun `the bottom threshold fires inside the edge band and never on NO_POSITION`() {
        val expected = mapOf(
            RecyclerView.NO_POSITION to false,
            ITEM_COUNT - PREFETCH_EDGE_ROWS to false,
            ITEM_COUNT - PREFETCH_EDGE_ROWS + 1 to true,
            ITEM_COUNT - 1 to true,
        )
        expected.forEach { (lastVisible, loadNewer) ->
            val decision = decide(lastVisible = lastVisible, isAtBottom = false)
            assertEquals("lastVisible=$lastVisible", loadNewer, decision.loadNewer)
        }
    }

    @Test
    fun `isAtBottom alone fires the newer load whatever the visible positions are`() {
        listOf(RecyclerView.NO_POSITION, 0, ITEM_COUNT / 2).forEach { lastVisible ->
            assertTrue(
                "lastVisible=$lastVisible",
                decide(lastVisible = lastVisible, isAtBottom = true).loadNewer,
            )
        }
    }

    
    
    
    @Test
    fun `no programmatic scroll landing triggers a load`() {
        val landings = mapOf(
            
            "quote" to Landing(firstVisible = 4, lastVisible = 12, itemCount = 180, isAtBottom = false),
            
            "mention" to Landing(firstVisible = 0, lastVisible = 8, itemCount = 180, isAtBottom = false),
            
            "search" to Landing(firstVisible = 1, lastVisible = 9, itemCount = 20, isAtBottom = false),
            
            "jumpToBottom" to Landing(firstVisible = 12, lastVisible = 19, itemCount = 20, isAtBottom = true),
            
            "popHandoff" to Landing(firstVisible = 0, lastVisible = 5, itemCount = 20, isAtBottom = false),
        )
        landings.forEach { (name, landing) ->
            val decision = decidePageLoad(
                userScrolling = false,
                firstVisible = landing.firstVisible,
                lastVisible = landing.lastVisible,
                itemCount = landing.itemCount,
                isAtBottom = landing.isAtBottom,
                hasReachedHistoryStart = false,
                hasReachedLatest = false,
            )
            assertEquals("$name must not page", PageLoadDecision.NONE, decision)
        }
    }

    
    @Test
    fun `each edge flag suppresses only its own direction`() {
        val decision = decidePageLoad(
            userScrolling = true,
            firstVisible = 3,
            lastVisible = ITEM_COUNT - 1,
            itemCount = ITEM_COUNT,
            isAtBottom = true,
            hasReachedHistoryStart = true,
            hasReachedLatest = false,
        )
        assertFalse("history start reached: no older page", decision.loadOlder)
        assertTrue("newest end not reached: newer page still allowed", decision.loadNewer)
    }

    private fun decide(
        userScrolling: Boolean = true,
        firstVisible: Int = MID_WINDOW,
        lastVisible: Int = MID_WINDOW,
        itemCount: Int = ITEM_COUNT,
        isAtBottom: Boolean = false,
        hasReachedHistoryStart: Boolean = false,
        hasReachedLatest: Boolean = false,
    ) = decidePageLoad(
        userScrolling = userScrolling,
        firstVisible = firstVisible,
        lastVisible = lastVisible,
        itemCount = itemCount,
        isAtBottom = isAtBottom,
        hasReachedHistoryStart = hasReachedHistoryStart,
        hasReachedLatest = hasReachedLatest,
    )

    private data class Landing(
        val firstVisible: Int,
        val lastVisible: Int,
        val itemCount: Int,
        val isAtBottom: Boolean,
    )

    private companion object {
        const val ITEM_COUNT = 180
        
        const val MID_WINDOW = 90
    }
}
