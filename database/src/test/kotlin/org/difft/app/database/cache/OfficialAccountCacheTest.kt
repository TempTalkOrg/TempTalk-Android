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

    
    @Test
    fun `T1 put toggles membership`() {
        OfficialAccountCache.put("a", true)
        assertTrue(OfficialAccountCache.contains("a"))
        OfficialAccountCache.put("a", false)
        assertFalse(OfficialAccountCache.contains("a"))
    }

    
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

    
    @Test
    fun `T3 clear empties the set`() {
        OfficialAccountCache.replaceAll(setOf("a", "b"))
        assertTrue(OfficialAccountCache.contains("a"))

        OfficialAccountCache.clear()
        assertFalse(OfficialAccountCache.contains("a"))
        assertFalse(OfficialAccountCache.contains("b"))
    }

    



    @Test
    fun `T3b stale preload after clear does not repopulate but fresh preload applies`() {
        val staleGen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.clear()   

        assertFalse(OfficialAccountCache.applyPreload(staleGen, setOf("+10000")), "stale apply must abort")
        assertFalse(OfficialAccountCache.contains("+10000"), "stale generation must be discarded")

        val freshGen = OfficialAccountCache.snapshotGeneration()
        assertTrue(OfficialAccountCache.applyPreload(freshGen, setOf("+10000")), "fresh apply must succeed")
        assertTrue(OfficialAccountCache.contains("+10000"), "fresh generation must populate")
    }

    



    @Test
    fun `T3c replaceAll during preload aborts the stale apply`() {
        val staleGen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.replaceAll(setOf("x"))   

        assertFalse(OfficialAccountCache.applyPreload(staleGen, setOf("y")), "stale apply must abort")
        assertTrue(OfficialAccountCache.contains("x"), "authoritative set must survive")
        assertFalse(OfficialAccountCache.contains("y"), "stale preload set must not apply")
    }

    



    @Test
    fun `T3d preload apply unions concurrent put delta`() {
        val gen = OfficialAccountCache.snapshotGeneration()
        OfficialAccountCache.put("concurrent", true)   

        assertTrue(OfficialAccountCache.applyPreload(gen, setOf("fromDb")), "same-gen apply must succeed")
        assertTrue(OfficialAccountCache.contains("fromDb"), "DB set must apply")
        assertTrue(OfficialAccountCache.contains("concurrent"), "concurrent put delta must survive (union)")
    }

    



    @Test
    fun `T15b late state collector self-heals when set populated`() = runTest {
        OfficialAccountCache.state.test {
            assertTrue(awaitItem().isEmpty())            
            OfficialAccountCache.replaceAll(setOf("+10000"))
            assertTrue(awaitItem().contains("+10000"))   
            cancelAndIgnoreRemainingEvents()
        }
    }
}
