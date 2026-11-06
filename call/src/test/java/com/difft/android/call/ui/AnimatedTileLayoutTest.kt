package com.difft.android.call.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Mid-animation sample point. */
private const val HALF_WAY_MS = TILE_MOVE_ANIM_MS / 2L

/**
 * [AnimatedTileLayout] moves keyed tiles between the rects it is given without re-creating them,
 * snaps when told to, keeps a leaving tile composed while it fades out, places rects absolutely in
 * RTL, and sizes a fill tile from the layout pass. Each tile is a tagged [Box] counting its own
 * creations.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w900dp-h900dp-xhdpi")
class AnimatedTileLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val created = HashMap<String, Int>()

    @Composable
    private fun Tile(id: String) {
        remember { created.merge(id, 1, Int::plus) }
        Box(Modifier.testTag("tile_$id"))
    }

    @Composable
    private fun Harness(tiles: List<TilePlacement>, snap: Boolean = false) {
        Box(Modifier.size(800.dp, 400.dp)) {
            AnimatedTileLayout(tiles = tiles, snap = snap, modifier = Modifier.fillMaxSize()) { placement -> Tile(placement.id) }
        }
    }

    private fun placed(id: String, left: Int, top: Int = 0, size: Int = 100, z: Float = 0f) =
        TilePlacement(id, IntRect(left, top, left + size, top + size), z)

    /** Writes harness state on the UI thread and publishes it, so the next frame recomposes. */
    private fun update(write: () -> Unit) = composeTestRule.runOnIdle {
        write()
        Snapshot.sendApplyNotifications()
    }

    private fun start(direction: LayoutDirection = LayoutDirection.Ltr, content: @Composable () -> Unit) {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) { content() }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun bounds(id: String): Rect = composeTestRule.onNodeWithTag("tile_$id").fetchSemanticsNode().boundsInRoot

    private fun exists(id: String): Boolean =
        composeTestRule.onAllNodesWithTag("tile_$id").fetchSemanticsNodes().isNotEmpty()

    private fun assertAt(message: String, expected: IntRect, actual: Rect) {
        assertEquals("$message: left", expected.left.toFloat(), actual.left, 0.5f)
        assertEquals("$message: top", expected.top.toFloat(), actual.top, 0.5f)
        assertEquals("$message: right", expected.right.toFloat(), actual.right, 0.5f)
        assertEquals("$message: bottom", expected.bottom.toFloat(), actual.bottom, 0.5f)
    }

    private fun assertStrictlyBetween(message: String, from: Float, to: Float, actual: Float) {
        assertTrue("$message: $actual not strictly between $from and $to", actual > minOf(from, to) && actual < maxOf(from, to))
    }

    /** Asserts [id] is mid-way from [from] to [to] on every edge that moves. */
    private fun assertMidway(id: String, from: IntRect, to: IntRect) {
        val mid = bounds(id)
        if (from.left != to.left) assertStrictlyBetween("$id left", from.left.toFloat(), to.left.toFloat(), mid.left)
        if (from.top != to.top) assertStrictlyBetween("$id top", from.top.toFloat(), to.top.toFloat(), mid.top)
        if (from.width != to.width) assertStrictlyBetween("$id width", from.width.toFloat(), to.width.toFloat(), mid.width)
    }

    @Test
    fun `a tile moving and resizing eases there and keeps its composition`() {
        val tiles = mutableStateOf(listOf(placed("a", 0), placed("b", 200)))
        start { Harness(tiles.value) }
        assertAt("first composition places without animating", IntRect(200, 0, 300, 100), bounds("b"))

        update { tiles.value = listOf(placed("a", 0), placed("b", 400, top = 100, size = 200), placed("c", 0, top = 200)) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(HALF_WAY_MS)
        assertMidway("b", IntRect(200, 0, 300, 100), IntRect(400, 100, 600, 300))

        composeTestRule.mainClock.advanceTimeBy(TILE_MOVE_ANIM_MS.toLong())
        assertAt("settles exactly", IntRect(400, 100, 600, 300), bounds("b"))
        assertAt("a new tile appears at its rect", IntRect(0, 200, 100, 300), bounds("c"))
        assertEquals("a moved tile is not re-created", 1, created["b"])
    }

    @Test
    fun `a snap moves tiles at once`() {
        val tiles = mutableStateOf(listOf(placed("a", 0)))
        val snap = mutableStateOf(false)
        start { Harness(tiles.value, snap.value) }

        update {
            tiles.value = listOf(placed("a", 300))
            snap.value = true
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        assertAt("no easing on a snap", IntRect(300, 0, 400, 100), bounds("a"))
    }

    @Test
    fun `rects are not mirrored in RTL, eased positions included`() {
        val tiles = mutableStateOf(listOf(placed("a", 0), placed("b", 200)))
        start(LayoutDirection.Rtl) { Harness(tiles.value) }
        assertAt("the first tile keeps the left edge", IntRect(0, 0, 100, 100), bounds("a"))

        update { tiles.value = listOf(placed("a", 0), placed("b", 400)) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(HALF_WAY_MS)
        assertMidway("b", IntRect(200, 0, 300, 100), IntRect(400, 0, 500, 100))
        composeTestRule.mainClock.advanceTimeBy(TILE_MOVE_ANIM_MS.toLong())
        assertAt("settles unmirrored", IntRect(400, 0, 500, 100), bounds("b"))
    }

    @Test
    fun `a fill tile takes the layout's size from the layout pass`() {
        start { Harness(listOf(TilePlacement("a", IntRect.Zero, 0f, fill = true))) }
        val px = composeTestRule.density.density
        assertAt("the whole layout", IntRect(0, 0, (800 * px).toInt(), (400 * px).toInt()), bounds("a"))
    }

    @Test
    fun `a tile that leaves stays composed while it fades, then goes`() {
        val tiles = mutableStateOf(listOf(placed("a", 0), placed("b", 200)))
        start { Harness(tiles.value) }

        update { tiles.value = listOf(placed("a", 0)) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(HALF_WAY_MS)
        assertTrue("still composed mid-fade", exists("b"))
        assertAt("at its last place", IntRect(200, 0, 300, 100), bounds("b"))

        composeTestRule.mainClock.advanceTimeBy(TILE_MOVE_ANIM_MS.toLong() * 2)
        assertFalse("gone after the fade", exists("b"))
    }

    @Test
    fun `a tile that returns while fading reuses its node and eases to its new place`() {
        val tiles = mutableStateOf(listOf(placed("a", 0), placed("b", 200)))
        start { Harness(tiles.value) }

        update { tiles.value = listOf(placed("a", 0)) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(HALF_WAY_MS)
        update { tiles.value = listOf(placed("a", 0), placed("b", 400)) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeBy(HALF_WAY_MS)
        assertMidway("b", IntRect(200, 0, 300, 100), IntRect(400, 0, 500, 100))

        composeTestRule.mainClock.advanceTimeBy(TILE_MOVE_ANIM_MS.toLong() * 2)
        assertAt("settles at its new place", IntRect(400, 0, 500, 100), bounds("b"))
        assertEquals("the returning tile is the same node", 1, created["b"])
    }
}
