package com.difft.android.call.ui.layout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.difft.android.call.layout.Insets as LayoutInsets
import com.difft.android.call.layout.Rect as LayoutRect
import com.difft.android.call.layout.Size as LayoutSize

class CallLayoutReportGateTest {

    private fun report(w: Double, h: Double, top: Double = 24.0): CallLayoutWindowReport {
        val content = LayoutRect(12.0, 90.0, w - 24, h - 200)
        return CallLayoutWindowReport(LayoutSize(w, h), LayoutInsets(top = top), null, CallContentRects(content, content))
    }

    @Test
    fun `reports each change once`() {
        val gate = CallLayoutReportGate()
        assertTrue(gate.admit(false, report(400.0, 800.0)))
        assertFalse(gate.admit(false, report(400.0, 800.0)))
        assertTrue(gate.admit(false, report(400.0, 800.0, top = 0.0)))
        assertTrue(gate.admit(false, report(800.0, 400.0)))
    }

    @Test
    fun `skips picture in picture and resumes once the window leaves the PiP size`() {
        val gate = CallLayoutReportGate()
        assertTrue(gate.admit(false, report(400.0, 800.0)))
        assertFalse(gate.admit(true, report(400.0, 800.0)))
        assertFalse(gate.admit(true, report(200.0, 112.0)))
        // The flag clears before the window has grown back.
        assertFalse(gate.admit(false, report(200.0, 112.0)))
        assertTrue(gate.admit(false, report(420.0, 800.0)))
    }

    @Test
    fun `a window back at the reported size after PiP needs no new report`() {
        val gate = CallLayoutReportGate()
        assertTrue(gate.admit(false, report(400.0, 800.0)))
        assertFalse(gate.admit(true, report(200.0, 112.0)))
        assertFalse(gate.admit(false, report(400.0, 800.0)))
        assertTrue(gate.admit(false, report(800.0, 400.0)))
    }
}
