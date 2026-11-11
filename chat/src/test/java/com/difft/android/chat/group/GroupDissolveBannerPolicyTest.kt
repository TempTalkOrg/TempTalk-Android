package com.difft.android.chat.group

import com.difft.android.chat.group.mvi.shouldShowGroupDissolveBanner
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupDissolveBannerPolicyTest {
    @Test fun `only a strictly future deadline is visible`() {
        listOf(null, -1L, 0L, NOW - 1, NOW).forEach {
            assertFalse(shouldShowGroupDissolveBanner(it, NOW, false, false), "deadline=$it")
        }
        assertTrue(shouldShowGroupDissolveBanner(NOW + 1, NOW, false, false))
    }

    @Test fun `dismissal suppresses a future deadline`() {
        assertFalse(shouldShowGroupDissolveBanner(NOW + 1, NOW, true, false))
    }

    @Test fun `join call bar suppresses a future deadline independently of dismissal`() {
        assertFalse(shouldShowGroupDissolveBanner(NOW + 1, NOW, false, true))
        assertFalse(shouldShowGroupDissolveBanner(NOW + 1, NOW, true, true))
    }

    private companion object { const val NOW = 1_800_000_000_000L }
}
