package com.difft.android.base.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `dataMigrationState` (登录时导入由手机决定 §5.2): two known values, everything else — including a
 * missing parameter from an older Desktop — reads as "no state", which the page treats as a legacy
 * Desktop. The v2 draft's `retained.<hash>` form must not be accepted by accident.
 */
class LinkMigrationStateTest {

    @Test
    fun `the one known value parses case-insensitively`() {
        assertEquals(LinkMigrationState.UNAVAILABLE, LinkMigrationInfo.parseState("unavailable"))
        assertEquals(LinkMigrationState.UNAVAILABLE, LinkMigrationInfo.parseState(" Unavailable "))
    }

    @Test
    fun `missing, retired and unknown values are no state`() {
        assertNull(LinkMigrationInfo.parseState(null))
        assertNull(LinkMigrationInfo.parseState(""))
        // v3's `retained` was withdrawn in v4: the history relation is answered by link-precheck.
        assertNull(LinkMigrationInfo.parseState("retained"))
        assertNull(LinkMigrationInfo.parseState("retained.0123456789abcdef"))
        assertNull(LinkMigrationInfo.parseState("busy"))
    }
}
