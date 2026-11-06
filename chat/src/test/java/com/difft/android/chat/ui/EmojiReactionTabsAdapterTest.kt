package com.difft.android.chat.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Reaction tabs are keyed by emoji: after a fold recreation where the emoji set changed (👍
 * reactions removed meanwhile), ViewPager2 must not hand the restored 👍 tab to ❤️.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmojiReactionTabsAdapterTest {

    private fun page(): Fragment {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        return Fragment().also { activity.supportFragmentManager.beginTransaction().add(it, "page").commitNow() }
    }

    @Test
    fun `an emoji keeps its id when the set changes, a removed one is gone`() {
        val page = page()
        val before = EmojiReactionFragment.ReactionTabsAdapter(page, "m1", listOf("👍", "❤️"))
        val after = EmojiReactionFragment.ReactionTabsAdapter(page, "m1", listOf("❤️"))

        assertEquals("❤️ keeps its id at a new position", before.getItemId(1), after.getItemId(0))
        assertTrue(after.containsItem(before.getItemId(1)))
        assertFalse("the restored 👍 tab is not reused", after.containsItem(before.getItemId(0)))
    }
}
