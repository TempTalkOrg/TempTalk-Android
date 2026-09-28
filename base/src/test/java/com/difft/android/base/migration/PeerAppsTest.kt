package com.difft.android.base.migration

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The peer mapping is the single place that decides which package the same-device flow detects,
 * launches and accepts calls from. Test must pair with test and production with production — a
 * cross-environment pairing would let a test build hand data to a production install or vice versa.
 */
class PeerAppsTest {

    private fun contextOf(packageName: String): Context =
        mockk<Context>(relaxed = true).also { every { it.packageName } returns packageName }

    /**
     * The general scanner routes on this shape alone, so it must recognise BOTH the payload this app
     * renders and the legacy-link envelope another platform may render. It stays a shape test: the
     * payload is decoded and validated by whoever acts on it.
     */
    @Test
    fun `both a bare payload and the legacy-link envelope read as a migration code`() {
        // Base45 text of a v2 payload: long enough and entirely within the alphabet (a leading space included).
        assertTrue(PeerApps.looksLikeMigrationQr(" NB0TF0XALDMDVBLA9038K38K38K38K38K38K38K38K38K38K38K38K38K38K38K.4KXR60JB0JB0JB0JB0JB0JB0JB0JB0JB0JB0J\n"))
        assertTrue(PeerApps.looksLikeMigrationQr("chative://transfer?version=1&peerId=ZGVm&migrationV2=e30"))
        assertTrue(PeerApps.looksLikeMigrationQr("temptalk://transfer?version=1&migrationV2=e30"))
        assertTrue(PeerApps.looksLikeMigrationQr("CHATIVE://TRANSFER?version=1&migrationV2=e30"))
    }

    /** Anything else must fall through to the scanner's other branches rather than be hijacked. */
    @Test
    fun `a legacy link without the payload, a linking code and plain text are not migration codes`() {
        assertFalse(PeerApps.looksLikeMigrationQr("chative://transfer?version=1&transferMode=primary&peerId=ZGVm"))
        assertFalse(PeerApps.looksLikeMigrationQr("tsdevice://linkdevice?uuid=abc&pub_key=def"))
        assertFalse(PeerApps.looksLikeMigrationQr("chative://linkdevice?uuid=abc&migrationV2=e30"))
        assertFalse(PeerApps.looksLikeMigrationQr("https://example.com/?migrationV2=e30"))
        assertFalse(PeerApps.looksLikeMigrationQr("""{"v":1,"ip":"1.2.3.4","transferKey":"a2V5"}"""))  // the retired JSON shape
        assertFalse(PeerApps.looksLikeMigrationQr("123456"))                                        // a PIN, not a code
        assertFalse(PeerApps.looksLikeMigrationQr("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abc")) // lowercase
        assertFalse(PeerApps.looksLikeMigrationQr("hello"))
    }

    @Test
    fun `production legacy pairs with production current`() {
        assertEquals("org.difft.temptalk", PeerApps.peerPackage(contextOf("org.difft.chative")))
    }

    @Test
    fun `production current pairs with production legacy`() {
        assertEquals("org.difft.chative", PeerApps.peerPackage(contextOf("org.difft.temptalk")))
    }

    @Test
    fun `test legacy pairs with test current`() {
        assertEquals("org.difft.temptalk.test", PeerApps.peerPackage(contextOf("org.difft.chative.test")))
    }

    @Test
    fun `test current pairs with test legacy`() {
        assertEquals("org.difft.chative.test", PeerApps.peerPackage(contextOf("org.difft.temptalk.test")))
    }

    @Test
    fun `the status authority pairs with the peer package, test with test`() {
        assertEquals(
            "org.difft.temptalk.migration.status",
            PeerApps.peerStatusAuthority(contextOf("org.difft.chative")),
        )
        assertEquals(
            "org.difft.chative.migration.status",
            PeerApps.peerStatusAuthority(contextOf("org.difft.temptalk")),
        )
        assertEquals(
            "org.difft.temptalk.test.migration.status",
            PeerApps.peerStatusAuthority(contextOf("org.difft.chative.test")),
        )
        assertEquals(
            "org.difft.chative.test.migration.status",
            PeerApps.peerStatusAuthority(contextOf("org.difft.temptalk.test")),
        )
    }

    @Test
    fun `only the chative family is legacy`() {
        assertTrue(PeerApps.isLegacy(contextOf("org.difft.chative")))
        assertTrue(PeerApps.isLegacy(contextOf("org.difft.chative.test")))
        assertFalse(PeerApps.isLegacy(contextOf("org.difft.temptalk")))
        assertFalse(PeerApps.isLegacy(contextOf("org.difft.temptalk.test")))
    }
}
