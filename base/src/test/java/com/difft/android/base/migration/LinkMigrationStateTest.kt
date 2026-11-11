package com.difft.android.base.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test





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

        assertNull(LinkMigrationInfo.parseState("retained"))
        assertNull(LinkMigrationInfo.parseState("retained.0123456789abcdef"))
        assertNull(LinkMigrationInfo.parseState("busy"))
    }
}
