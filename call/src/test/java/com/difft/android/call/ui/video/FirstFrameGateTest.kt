package com.difft.android.call.ui.video

import livekit.org.webrtc.EglRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFrameGateTest {

    
    private val gate = FirstFrameGate().apply { setAccepting(true) }
    private val first = Any()
    private val second = Any()

    @Test
    fun `no frame until the attached renderer draws one`() {
        assertFalse("nothing attached", gate.hasFrame)
        gate.attach(first)
        assertFalse("attached but still black", gate.hasFrame)
        gate.onFrameRendered(first)
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `a frame drawn just before its renderer attaches still counts`() {
        gate.onFrameRendered(first)
        assertFalse("nothing attached yet", gate.hasFrame)
        gate.attach(first)
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `a frame from a renderer other than the attached one is ignored`() {
        gate.attach(second)
        gate.onFrameRendered(first)
        assertFalse("late report from another renderer", gate.hasFrame)
    }

    @Test
    fun `a new renderer starts black even if the previous one drew`() {
        gate.attach(first)
        gate.onFrameRendered(first)
        
        gate.attach(second)
        gate.detach(first)
        assertFalse(gate.hasFrame)
        gate.onFrameRendered(second)
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `detaching the renderer drops the frame`() {
        gate.attach(first)
        gate.onFrameRendered(first)
        gate.detach(first)
        assertFalse(gate.hasFrame)
        gate.attach(first)
        assertFalse("re-attached renderer must draw again", gate.hasFrame)
    }

    @Test
    fun `reset forgets the frame until the renderer draws again`() {
        gate.attach(first)
        gate.onFrameRendered(first)
        gate.reset()
        assertFalse("camera off keeps a stale image", gate.hasFrame)
        gate.onFrameRendered(first)
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `re-attaching the same renderer keeps its frame`() {
        gate.attach(first)
        gate.onFrameRendered(first)
        gate.attach(first)
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `a frame drawn before a reset does not count after it`() {
        gate.attach(first)
        val drawnBefore = gate.generation
        gate.reset()
        gate.onFrameRendered(first, drawnBefore)
        assertFalse("stale frame delivered after the camera turned off", gate.hasFrame)
        gate.onFrameRendered(first, gate.generation)
        assertTrue(gate.hasFrame)
    }

    
    private class FakeRenderer {
        val listeners = mutableListOf<EglRenderer.FrameListener>()
        val mainQueue = ArrayDeque<() -> Unit>()

        fun drawFrame() {
            val fired = listeners.toList()
            listeners.clear()
            fired.forEach { it.onFrame(null) }
        }

        fun runMain() {
            while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
        }
    }

    private fun reporterFor(renderer: FakeRenderer) = FirstFrameReporter(
        renderer = renderer,
        gate = { gate },
        addListener = { renderer.listeners += it },
        postToMain = { renderer.mainQueue += it },
    )

    @Test
    fun `a kept renderer re-armed after a reset reveals its next frame`() {
        val renderer = FakeRenderer()
        val reporter = reporterFor(renderer)
        gate.attach(renderer)
        reporter.arm()
        renderer.drawFrame()
        renderer.runMain()
        assertTrue(gate.hasFrame)

        
        gate.reset()
        assertEquals("the first listener was spent", 0, renderer.listeners.size)
        reporter.arm()
        assertFalse(gate.hasFrame)

        renderer.drawFrame()
        renderer.runMain()
        assertTrue("the returning camera's frame lifts the placeholder", gate.hasFrame)
    }

    @Test
    fun `arming while a listener is pending adds no duplicate`() {
        val renderer = FakeRenderer()
        val reporter = reporterFor(renderer)
        gate.attach(renderer)
        reporter.arm()
        gate.reset()
        reporter.arm()
        assertEquals(1, renderer.listeners.size)

        renderer.drawFrame()
        renderer.runMain()
        assertTrue("the pending listener reports the post-reset frame", gate.hasFrame)
    }

    @Test
    fun `a frame drawn before a reset but delivered after it is ignored`() {
        val renderer = FakeRenderer()
        val reporter = reporterFor(renderer)
        gate.attach(renderer)
        reporter.arm()
        renderer.drawFrame()
        gate.reset()
        reporter.arm()
        renderer.runMain()
        assertFalse("the stale frame must not lift the placeholder", gate.hasFrame)

        renderer.drawFrame()
        renderer.runMain()
        assertTrue(gate.hasFrame)
    }

    @Test
    fun `nothing counts before the tile reports its camera on`() {
        val fresh = FirstFrameGate()
        fresh.attach(first)
        fresh.onFrameRendered(first)
        assertFalse(fresh.hasFrame)
    }

    @Test
    fun `a frame drawn while muted never reveals the video after unmute`() {
        
        val renderer = FakeRenderer()
        val reporter = reporterFor(renderer)
        gate.setAccepting(false)
        gate.attach(renderer)
        reporter.arm()
        renderer.drawFrame()
        renderer.runMain()
        
        assertFalse("no flicker in the unmute composition", gate.hasFrame)

        
        reporter.arm()
        renderer.drawFrame()
        gate.setAccepting(true)
        reporter.arm()
        renderer.runMain()
        assertFalse("the pre-unmute frame stays ignored", gate.hasFrame)

        renderer.drawFrame()
        renderer.runMain()
        assertTrue("the first post-unmute frame reveals the video", gate.hasFrame)
    }

    @Test
    fun `the first ever unmute waits for a frame`() {
        val renderer = FakeRenderer()
        val fresh = FirstFrameGate()
        val reporter = FirstFrameReporter(renderer, { fresh }, { renderer.listeners += it }, { renderer.mainQueue += it })
        fresh.attach(renderer)
        reporter.arm()
        fresh.setAccepting(true)
        reporter.arm()
        assertFalse(fresh.hasFrame)
        assertEquals("no duplicate listener", 1, renderer.listeners.size)
        renderer.drawFrame()
        renderer.runMain()
        assertTrue(fresh.hasFrame)
    }

    @Test
    fun `mute and unmute cycles hide at once and reveal only on a new frame`() {
        val renderer = FakeRenderer()
        val reporter = reporterFor(renderer)
        gate.attach(renderer)
        reporter.arm()
        repeat(3) { cycle ->
            renderer.drawFrame()
            renderer.runMain()
            assertTrue("cycle $cycle shows video", gate.hasFrame)

            gate.setAccepting(false)
            reporter.arm()
            assertFalse("cycle $cycle muted", gate.hasFrame)
            
            renderer.drawFrame()
            renderer.runMain()
            assertFalse("cycle $cycle muted redraw ignored", gate.hasFrame)

            gate.setAccepting(true)
            reporter.arm()
            assertFalse("cycle $cycle waits for a post-unmute frame", gate.hasFrame)
            assertEquals("re-armed once", 1, renderer.listeners.size)
        }
    }

    @Test
    fun `repeating the same camera state does not reset`() {
        gate.attach(first)
        gate.onFrameRendered(first)
        val generation = gate.generation
        gate.setAccepting(true)
        assertTrue(gate.hasFrame)
        assertEquals(generation, gate.generation)
    }
}
