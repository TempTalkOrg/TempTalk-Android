package com.difft.android.call.ui.layout

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import com.difft.android.call.layout.ContentMode
import com.difft.android.call.layout.ControllerState
import com.difft.android.call.layout.LayoutMode
import com.difft.android.call.layout.LayoutPlan
import com.difft.android.call.layout.Orientation
import com.difft.android.call.layout.Posture
import com.difft.android.call.layout.Rect
import com.difft.android.call.layout.RequestOrientation
import com.difft.android.call.layout.Tile
import com.difft.android.call.layout.TileRole
import com.difft.android.call.layout.WindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStageTest {

    private fun tile(id: String, z: Int = 0, mode: ContentMode? = ContentMode.FILL) =
        Tile(id, TileRole.GRID, Rect(0.0, 0.0, 100.0, 100.0), mode, 0.0, z)

    private fun plan(vararg tiles: Tile) = LayoutPlan(
        windowClass = WindowClass.COMPACT,
        orientation = Orientation.PORTRAIT,
        requestOrientation = RequestOrientation.PORTRAIT,
        mode = LayoutMode.GRID,
        posture = Posture.FLAT,
        tiles = tiles.toList(),
        grid = null,
        dock = null,
        pipArea = null,
    )

    private fun state(animated: Boolean, vararg tiles: Tile) = ControllerState(plan = plan(*tiles), animated = animated)

    @Test
    fun `the first state drawn snaps, later animated states animate`() {
        val rule = StageSnapRule()
        assertTrue(rule.snapFor(state(true, tile("a")), "k", inPip = false))
        assertFalse(rule.snapFor(state(true, tile("a"), tile("b")), "k", inPip = false))
    }

    @Test
    fun `a state published as not animated snaps`() {
        val rule = StageSnapRule()
        rule.snapFor(state(true, tile("a")), "k", inPip = false)
        assertTrue(rule.snapFor(state(false, tile("a"), tile("b")), "k", inPip = false))
    }

    @Test
    fun `a window key change snaps even when the not-animated state was conflated away`() {
        val rule = StageSnapRule()
        rule.snapFor(state(true, tile("a")), "k1", inPip = false)
        // setWindow published animated=false and a tick replaced it before the stage drew.
        assertTrue(rule.snapFor(state(true, tile("a"), tile("b")), "k2", inPip = false))
        assertFalse("the next state for the same key animates", rule.snapFor(state(true, tile("b")), "k2", inPip = false))
    }

    @Test
    fun `a key seen before its state arrives still snaps that state`() {
        val rule = StageSnapRule()
        val s1 = state(true, tile("a"))
        rule.snapFor(s1, "k1", inPip = false)
        // The report key recomposed the stage before the collector delivered the new state.
        rule.snapFor(s1, "k2", inPip = false)
        assertTrue(rule.snapFor(state(true, tile("a"), tile("b")), "k2", inPip = false))
    }

    @Test
    fun `recomposing the same state keeps its decision`() {
        val rule = StageSnapRule()
        rule.snapFor(state(true, tile("a")), "k", inPip = false)
        val s = state(true, tile("a"), tile("b"))
        assertFalse(rule.snapFor(s, "k", inPip = false))
        assertFalse(rule.snapFor(s, "k", inPip = false))
    }

    @Test
    fun `entering and leaving picture in picture snap, and so does every state in it`() {
        val rule = StageSnapRule()
        rule.snapFor(state(true, tile("a")), "k", inPip = false)
        val s = state(true, tile("a"), tile("b"))
        assertFalse(rule.snapFor(s, "k", inPip = false))
        assertTrue(rule.snapFor(s, "k", inPip = true))
        assertTrue(rule.snapFor(state(true, tile("b")), "k", inPip = true))
        assertTrue("the first draw after it", rule.snapFor(state(true, tile("b")), "k", inPip = false))
    }

    @Test
    fun `tiles are drawn in ascending z`() {
        val tiles = stageTiles(plan(tile("pip", z = 1), tile("main", z = 0)), focusId = null, inPip = false)
        assertEquals(listOf("main", "pip"), tiles.map { it.id })
    }

    @Test
    fun `picture in picture adds a tile for an off-page focus, above the others, showing the whole video`() {
        val p = plan(tile("me"), tile("a"))
        assertEquals(2, stageTiles(p, "a", inPip = true).size)
        assertEquals("not outside picture in picture", 2, stageTiles(p, "z", inPip = false).size)
        val focus = stageTiles(p, "z", inPip = true).last()
        assertEquals("z", focus.id)
        // Its first-frame gate keeps the avatar while the camera is off.
        assertEquals(ContentMode.FIT, focus.contentMode)
        assertTrue(focus.z > 0)
    }

    @Test
    fun `the page pill appears only when the page or the page count changes`() {
        val memory = PagerPillMemory()
        assertTrue(memory.appearsFor(0, 3))
        assertFalse("the indicator coming back on the same page", memory.appearsFor(0, 3))
        assertTrue(memory.appearsFor(1, 3))
        assertTrue(memory.appearsFor(1, 4))
    }

    @Test
    fun `plan rects move by the stage origin and scale to px`() {
        val r = Rect(10.0, 20.0, 100.0, 50.0).toStagePx(Offset(0f, 5f), density = 2f)
        assertEquals(IntRect(20, 30, 220, 130), r)
    }
}
