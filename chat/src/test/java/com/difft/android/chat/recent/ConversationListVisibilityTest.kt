package com.difft.android.chat.recent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The list counts as visible only while resumed AND not under the one-pane detail layer (§3.7),
 * and the "back on the list" refresh runs when the layer uncovers a resumed list, since closing a
 * conversation there does not resume the list as closing ChatActivity did.
 */
class ConversationListVisibilityTest {

    private val published = mutableListOf<Boolean>()
    private var returns = 0
    private val visibility = ConversationListVisibility(onReturnedToList = { returns++ }, publish = { published += it })

    @Test
    fun `resumed and uncovered is visible`() {
        visibility.onCoveredChanged(false)
        visibility.onResumedChanged(true)
        assertEquals(true, published.last())
    }

    @Test
    fun `the detail layer hides a list that stays resumed`() {
        visibility.onResumedChanged(true)
        visibility.onCoveredChanged(true)
        assertEquals(false, published.last())

        visibility.onCoveredChanged(false)
        assertEquals(true, published.last())
    }

    @Test
    fun `a paused list is not visible even uncovered`() {
        visibility.onCoveredChanged(false)
        visibility.onResumedChanged(true)
        visibility.onResumedChanged(false)
        assertEquals(false, published.last())
    }

    @Test
    fun `uncovering a resumed list runs the return refresh once`() {
        visibility.onResumedChanged(true)
        visibility.onCoveredChanged(true)
        visibility.onCoveredChanged(false)
        assertEquals(1, returns)
    }

    @Test
    fun `no return refresh without an uncover`() {
        visibility.onResumedChanged(true)
        visibility.onCoveredChanged(false)
        visibility.onCoveredChanged(false)
        visibility.onResumedChanged(false)
        visibility.onResumedChanged(true)
        assertEquals("the resume path runs its own refresh; this hook must not add one", 0, returns)
    }

    @Test
    fun `uncovering a paused list leaves the refresh to its next resume`() {
        visibility.onCoveredChanged(true)
        visibility.onCoveredChanged(false)
        assertEquals(0, returns)
    }
}
