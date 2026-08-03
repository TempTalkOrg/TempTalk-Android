package org.difft.app.database.cache

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [OfficialAccountCache] — the in-memory official-account membership set (P1-04).
 * Covers the pure memory paths (put / replaceAll / clear / state). [OfficialAccountCache.preload]
 * reads WCDB (native lib) and is covered by instrumentation only.
 *
 * Design source: tmp/p104-public-account-type/design-report.md §2.1, §11 (T1, T2, T3, T15b).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfficialAccountCacheTest {

    @Before
    fun setUp() {
        OfficialAccountCache.clear()
    }

    @After
    fun tearDown() {
        OfficialAccountCache.clear()
    }

    /** T1 — put adds then removes a single id. */
    @Test
    fun `T1 put toggles membership`() {
        OfficialAccountCache.put("a", true)
        assertTrue(OfficialAccountCache.contains("a"))
        OfficialAccountCache.put("a", false)
        assertFalse(OfficialAccountCache.contains("a"))
    }

    /** T2 — replaceAll swaps the whole set (full-sync semantics: stale ids dropped). */
    @Test
    fun `T2 replaceAll replaces the whole set`() {
        OfficialAccountCache.replaceAll(setOf("a", "b"))
        assertTrue(OfficialAccountCache.contains("a"))
        assertTrue(OfficialAccountCache.contains("b"))

        OfficialAccountCache.replaceAll(setOf("b", "c"))
        assertFalse(OfficialAccountCache.contains("a"))
        assertTrue(OfficialAccountCache.contains("b"))
        assertTrue(OfficialAccountCache.contains("c"))
    }

    /** T3 — clear empties the set (logout). */
    @Test
    fun `T3 clear empties the set`() {
        OfficialAccountCache.replaceAll(setOf("a", "b"))
        assertTrue(OfficialAccountCache.contains("a"))

        OfficialAccountCache.clear()
        assertFalse(OfficialAccountCache.contains("a"))
        assertFalse(OfficialAccountCache.contains("b"))
    }

    /**
     * T3b — generation guard: a preload result captured BEFORE a clear() must NOT repopulate the
     * cache (logout mid-preload). A fresh capture (current generation) applies normally.
     */
    @Test
    fun `T3b stale preload after clear does not repopulate but fresh preload applies`() {
        val staleGen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.clear()   // generation advances past staleGen

        assertFalse(OfficialAccountCache.applyPreload(staleGen, setOf("+10000")), "stale apply must abort")
        assertFalse(OfficialAccountCache.contains("+10000"), "stale generation must be discarded")

        val freshGen = OfficialAccountCache.snapshotGeneration()
        assertTrue(OfficialAccountCache.applyPreload(freshGen, setOf("+10000")), "fresh apply must succeed")
        assertTrue(OfficialAccountCache.contains("+10000"), "fresh generation must populate")
    }

    /**
     * T3c — replaceAll (full sync, authoritative) fired mid-preload advances the generation, so a
     * stale preload apply aborts instead of clobbering the just-written full set.
     */
    @Test
    fun `T3c replaceAll during preload aborts the stale apply`() {
        val staleGen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.replaceAll(setOf("x"))   // authoritative full sync mid-preload

        assertFalse(OfficialAccountCache.applyPreload(staleGen, setOf("y")), "stale apply must abort")
        assertTrue(OfficialAccountCache.contains("x"), "authoritative set must survive")
        assertFalse(OfficialAccountCache.contains("y"), "stale preload set must not apply")
    }

    /**
     * T3d — a concurrent put(id,true) delta that landed after the preload snapshot survives the
     * apply (union with the DB set), matching the ContactRemarkCache precedent.
     */
    @Test
    fun `T3d preload apply unions concurrent put delta`() {
        val gen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.put("concurrent", true)   // delta after snapshot, before apply

        assertTrue(OfficialAccountCache.applyPreload(gen, setOf("fromDb")), "same-gen apply must succeed")
        assertTrue(OfficialAccountCache.contains("fromDb"), "DB set must apply")
        assertTrue(OfficialAccountCache.contains("concurrent"), "concurrent put delta must survive (union)")
    }

    /**
     * T15b — a collector that starts on the empty cache (pre-preload window) sees the empty set,
     * then the populated set once replaceAll fires. Proves the §10.1 cold-start self-heal.
     */
    @Test
    fun `T15b late state collector self-heals when set populated`() = runTest {
        OfficialAccountCache.state.test {
            assertTrue(awaitItem().isEmpty())            // initial empty (badge off)
            OfficialAccountCache.replaceAll(setOf("+10000"))
            assertTrue(awaitItem().contains("+10000"))   // populated (badge on)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
