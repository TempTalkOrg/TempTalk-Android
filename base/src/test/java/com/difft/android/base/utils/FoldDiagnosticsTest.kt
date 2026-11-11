package com.difft.android.base.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference


class FoldDiagnosticsTest {

    @Test
    fun `snapshot line carries every field in order`() {
        val line = formatFoldSnapshot(
            FoldDiagHeader(3, 500L, 4L, "IndexActivity", "1a2b3c", "STARTED", false, "1672x2364->1168x1712"),
            FoldDiagWindow(2.75f, 608, 859, 608, 1, "1168x1712", "1168x1712"),
            FoldDiagPolicy(OrientationPolicy.ShortSides(424, 424, 608, "1168x1712"), 1, -1, multiWindow = false, pip = false, unfoldPending = false),
            " gate=true tree=false",
        )
        assertEquals(
            "[Fold] diag snap seq=3 t=500 lagMs=4 cls=IndexActivity inst=1a2b3c life=STARTED fin=false " +
                "panel=1672x2364->1168x1712 den=2.75 cfgDp=608x859 cfgSw=608 cfgOri=1 winPx=1168x1712 rootPx=1168x1712 " +
                "modeDp=424 boundsDp=424 windowDp=608 resolvedDp=424 target=1 req=-1 mw=false pip=false unfoldPending=false " +
                "gate=true tree=false",
            line,
        )
    }

    @Test
    fun `long tail only while the configuration is more than 48dp off the panel`() {
        assertFalse(needsLongTail(cfgSw = 472, modeDp = 424, multiWindow = false))
        assertTrue(needsLongTail(cfgSw = 473, modeDp = 424, multiWindow = false))
        assertTrue(needsLongTail(cfgSw = 375, modeDp = 424, multiWindow = false))
        assertFalse(needsLongTail(cfgSw = 376, modeDp = 424, multiWindow = false))
        assertFalse("split-screen windows differ from the panel by design", needsLongTail(608, 424, multiWindow = true))
        assertFalse("no panel mode reported", needsLongTail(608, 0, multiWindow = false))
    }

    @Test
    fun `a session caps its snapshot, stall and configuration lines`() {
        val session = FoldDiagSession(1, 1_000L, "a->b", WeakReference(null))
        assertEquals(7, (1..10).count { session.trySnapshotLine() })
        assertEquals(1, (1..3).count { session.tryStallLine() })
        assertEquals(6, (1..10).count { session.tryConfigLine() })
        assertTrue(session.isLive(61_000L))
        assertFalse(session.isLive(61_001L))
    }

    @Test
    fun `at most three sessions start per 30 seconds with one throttled line per window`() {
        val throttle = FoldDiagThrottle()
        assertEquals(FoldDiagThrottle.Decision.START, throttle.tryStart(0L))
        assertEquals(FoldDiagThrottle.Decision.START, throttle.tryStart(1_000L))
        assertEquals(FoldDiagThrottle.Decision.START, throttle.tryStart(2_000L))
        assertEquals(FoldDiagThrottle.Decision.THROTTLED, throttle.tryStart(3_000L))
        assertEquals(FoldDiagThrottle.Decision.DROP, throttle.tryStart(4_000L))
        
        assertEquals(FoldDiagThrottle.Decision.START, throttle.tryStart(30_000L))
        assertEquals(FoldDiagThrottle.Decision.THROTTLED, throttle.tryStart(30_500L))
        assertEquals(FoldDiagThrottle.Decision.DROP, throttle.tryStart(30_600L))
    }

    @Test
    fun `stack frames are class, method and line only`() {
        val frames = arrayOf(
            StackTraceElement("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", -2),
            StackTraceElement("android.os.Looper", "loop", "Looper.java", 288),
        )
        assertEquals("android.os.MessageQueue.nativePollOnce:-2|android.os.Looper.loop:288", formatStack(frames))
        assertEquals("android.os.MessageQueue.nativePollOnce:-2", formatStack(frames, limit = 1))
    }
}
