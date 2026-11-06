package com.difft.android.call.ui.tabletop

import android.os.SystemClock
import com.difft.android.base.log.lumberjack.L
import kotlin.math.roundToInt

/** The `[Call] tabletop` enter / exit lines; every value is formatted before `L`. */
internal class TabletopLogger(private val now: () -> Long = { SystemClock.uptimeMillis() }) {
    private var enteredAt: Long? = null

    fun onStep(step: GateStep, isMultiWindow: () -> Boolean) {
        when (val event = step.event) {
            is GateEvent.Entered -> {
                enteredAt = now()
                val line = formatTabletopEnter(event.g, isMultiWindow())
                L.i { line }
            }
            is GateEvent.Exited -> {
                val heldMs = enteredAt?.let { now() - it }
                enteredAt = null
                val line = "[Call] tabletop exit reason=${event.reason} heldMs=$heldMs"
                L.i { line }
            }
            null -> Unit
        }
    }
}

/** `[Call] tabletop enter fold=horizontal bandDp=430-430 windowDp=608x860 mw=false`. */
internal fun formatTabletopEnter(g: TabletopGeometry, multiWindow: Boolean): String =
    "[Call] tabletop enter fold=${if (g.horizontal) "horizontal" else "vertical"} " +
        "bandDp=${g.bandTopDp.roundToInt()}-${g.bandBottomDp.roundToInt()} " +
        "windowDp=${g.widthDp.roundToInt()}x${g.heightDp.roundToInt()} mw=$multiWindow"
