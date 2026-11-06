package com.difft.android.call.ui.tabletop

/** Debounce state for the tabletop posture: only [On] and [Leaving] publish a geometry. */
internal sealed interface GateState {
    /**
     * Before the first report: the device is already in the posture the call opens in, not in the
     * middle of folding, so an accepted geometry shows at once instead of after the enter wait.
     */
    data object Initial : GateState

    data object Off : GateState

    /** Accepted, waiting [TabletopGate.ENTER_STABLE_MS] for the posture to hold. */
    data class Pending(val g: TabletopGeometry) : GateState

    data class On(val g: TabletopGeometry) : GateState

    /** Shown while a flat report lasts under [TabletopGate.FLAT_EXIT_MS] (angle threshold jitter). */
    data class Leaving(val g: TabletopGeometry) : GateState
}

internal sealed interface GateEvent {
    data class Entered(val g: TabletopGeometry) : GateEvent

    data class Exited(val reason: String) : GateEvent
}

/** One reducer step: the next state, the delay before [TabletopGate.onTimeout] (null: none), and what to log. */
internal data class GateStep(val state: GateState, val timerMs: Long?, val event: GateEvent?)

internal val GateState.geometry: TabletopGeometry?
    get() = when (this) {
        is GateState.On -> g
        is GateState.Leaving -> g
        else -> null
    }

/**
 * Pure debounce for [TabletopResolution]s. Entering needs the same accepted geometry for
 * [ENTER_STABLE_MS], which filters the fold reports a device sends while it opens or closes; a
 * flat report on the same window leaves after [FLAT_EXIT_MS]; anything else leaves at once.
 */
internal object TabletopGate {
    /** Longer than every opening transient seen on device (0.08–0.34 s). */
    const val ENTER_STABLE_MS = 600L

    /** Hysteresis around the FLAT / HALF_OPENED angle threshold. */
    const val FLAT_EXIT_MS = 300L

    const val EXIT_GEOMETRY = "geometry"
    const val EXIT_FLAT = "flat"
    const val EXIT_NO_FOLD = "noFold"

    fun onInput(s: GateState, r: TabletopResolution): GateStep = when (s) {
        GateState.Initial -> if (r is TabletopResolution.Accepted) {
            GateStep(GateState.On(r.geometry), null, GateEvent.Entered(r.geometry))
        } else {
            GateStep(GateState.Off, null, null)
        }
        // Nothing shown yet: only an accepted geometry (re)starts the wait.
        GateState.Off, is GateState.Pending -> fromHidden(r)
        is GateState.On -> fromShown(s.g, r)
        is GateState.Leaving -> fromShown(s.g, r)
    }

    fun onTimeout(s: GateState): GateStep = when (s) {
        is GateState.Pending -> GateStep(GateState.On(s.g), null, GateEvent.Entered(s.g))
        is GateState.Leaving -> GateStep(GateState.Off, null, GateEvent.Exited(EXIT_FLAT))
        else -> GateStep(s, null, null)
    }

    private fun fromHidden(r: TabletopResolution): GateStep =
        if (r is TabletopResolution.Accepted) pending(r.geometry, null) else GateStep(GateState.Off, null, null)

    /** [GateState.On] and [GateState.Leaving] differ only in whether a timer runs, so one rule serves both. */
    private fun fromShown(g: TabletopGeometry, r: TabletopResolution): GateStep = when (r) {
        // The same geometry keeps (or, from Leaving, restores) On without a timer.
        is TabletopResolution.Accepted ->
            if (r.geometry != g) pending(r.geometry, GateEvent.Exited(EXIT_GEOMETRY)) else GateStep(GateState.On(g), null, null)
        is TabletopResolution.Flat -> if (r.widthPx == g.widthPx && r.heightPx == g.heightPx) {
            GateStep(GateState.Leaving(g), FLAT_EXIT_MS, null)
        } else {
            off(EXIT_GEOMETRY)
        }
        TabletopResolution.NoFold -> off(EXIT_NO_FOLD)
    }

    private fun pending(g: TabletopGeometry, event: GateEvent?) = GateStep(GateState.Pending(g), ENTER_STABLE_MS, event)

    private fun off(reason: String) = GateStep(GateState.Off, null, GateEvent.Exited(reason))
}
