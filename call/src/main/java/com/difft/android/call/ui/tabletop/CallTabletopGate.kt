package com.difft.android.call.ui.tabletop


internal sealed interface GateState {
    



    data object Initial : GateState

    data object Off : GateState

    
    data class Pending(val g: TabletopGeometry) : GateState

    data class On(val g: TabletopGeometry) : GateState

    
    data class Leaving(val g: TabletopGeometry) : GateState
}

internal sealed interface GateEvent {
    data class Entered(val g: TabletopGeometry) : GateEvent

    data class Exited(val reason: String) : GateEvent
}


internal data class GateStep(val state: GateState, val timerMs: Long?, val event: GateEvent?)

internal val GateState.geometry: TabletopGeometry?
    get() = when (this) {
        is GateState.On -> g
        is GateState.Leaving -> g
        else -> null
    }






internal object TabletopGate {
    
    const val ENTER_STABLE_MS = 600L

    
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

    
    private fun fromShown(g: TabletopGeometry, r: TabletopResolution): GateStep = when (r) {
        
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
