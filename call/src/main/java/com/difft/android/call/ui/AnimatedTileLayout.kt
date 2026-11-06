package com.difft.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** How long a tile takes to reach its new place and size, and to fade in or out (guide §5.1). */
internal const val TILE_MOVE_ANIM_MS = 280

/** CSS `ease-in-out`, the curve the three platforms share for tile moves (guide §5.1). */
internal val TileMoveEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/** Scale a tile enters from (guide §5.1). */
private const val TILE_ENTER_SCALE = 0.96f

/**
 * One tile to draw: where (px, relative to the layout), at which depth, and what it shows ([data],
 * kept for a tile fading out after it left). With [fill] the tile takes the layout's whole size from
 * the measure pass and [rect] is ignored, so it follows a window resize in the same frame.
 */
internal data class TilePlacement(val id: String, val rect: IntRect, val z: Float, val data: Any? = null, val fill: Boolean = false)

/** Moves a tile's drawn rect from outside the layout, e.g. a floating window let go mid-drag. */
@Stable
internal class TileMotionState internal constructor(internal val motion: TileMotion) {
    /** The tile [id] is drawn at [rect] now and eases from there to its target. */
    fun releaseAt(id: String, rect: IntRect) = motion.releaseAt(id, rect)

    /** Where the tile [id] is drawn now, mid-animation included; null before its first placement. */
    fun currentRect(id: String): IntRect? = motion.currentRect(id)
}

@Composable
internal fun rememberTileMotionState(): TileMotionState {
    val scope = rememberCoroutineScope()
    return remember(scope) { TileMotionState(TileMotion(scope)) }
}

/**
 * Draws [tiles] keyed by id, so a tile that moves, resizes or changes role keeps its node (and its
 * video renderer). Tiles ease to new rects over [TILE_MOVE_ANIM_MS] unless [snap]; a new tile fades
 * in from [TILE_ENTER_SCALE]; a tile that leaves fades out and stays composed until then, so an id
 * that comes back inside that time reuses the same node. Depth comes from [TilePlacement.z]; rects
 * are absolute (no RTL mirroring).
 */
@Composable
internal fun AnimatedTileLayout(
    tiles: List<TilePlacement>,
    snap: Boolean,
    modifier: Modifier = Modifier,
    motion: TileMotionState = rememberTileMotionState(),
    tile: @Composable (TilePlacement) -> Unit,
) {
    val retention = remember { TileRetention() }
    var expired by remember { mutableIntStateOf(0) }
    // Read so an expiry recomposes; the retention itself is plain state, written only here.
    val drawn = retention.update(tiles, snap, expired)
    val byId = drawn.associate { it.placement.id to it.placement }
    Layout(
        modifier = modifier,
        content = {
            drawn.forEach { entry ->
                key(entry.placement.id) {
                    RetainedTile(
                        id = entry.placement.id,
                        departing = entry.departing,
                        enter = entry.entering,
                        onGone = { id ->
                            if (retention.expire(id)) expired++
                        },
                    ) { tile(entry.placement) }
                }
            }
        },
    ) { measurables, constraints ->
        motion.motion.observe()
        val eased = !snap
        val placed = measurables.mapNotNull { measurable ->
            val id = measurable.layoutId as? String ?: return@mapNotNull null
            val placement = byId[id] ?: return@mapNotNull null
            val target = if (placement.fill) IntRect(0, 0, constraints.maxWidth, constraints.maxHeight) else placement.rect
            val rect = motion.motion.rectOf(id, target, eased)
            Triple(
                measurable.measure(Constraints.fixed(rect.width.coerceAtLeast(0), rect.height.coerceAtLeast(0))),
                rect,
                placement.z,
            )
        }
        motion.motion.retain(byId.keys)
        layout(constraints.maxWidth, constraints.maxHeight) {
            placed.forEach { (placeable, rect, z) -> placeable.place(rect.left, rect.top, zIndex = z) }
        }
    }
}

/** One keyed tile node with its enter and exit fade. */
@Composable
private fun RetainedTile(
    id: String,
    departing: Boolean,
    enter: Boolean,
    onGone: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val alpha = remember { Animatable(if (enter) 0f else 1f) }
    val scale = remember { Animatable(if (enter) TILE_ENTER_SCALE else 1f) }
    val currentOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(departing) {
        if (departing) {
            alpha.animateTo(0f, tween(TILE_MOVE_ANIM_MS, easing = TileMoveEasing))
            currentOnGone(id)
        } else {
            launch { scale.animateTo(1f, tween(TILE_MOVE_ANIM_MS, easing = TileMoveEasing)) }
            alpha.animateTo(1f, tween(TILE_MOVE_ANIM_MS, easing = TileMoveEasing))
        }
    }
    Box(
        Modifier
            .layoutId(id)
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
            },
        propagateMinConstraints = true,
    ) { content() }
}

/** A tile as drawn: still shown, entering, or fading out after it left the plan. */
internal data class RetainedPlacement(val placement: TilePlacement, val departing: Boolean, val entering: Boolean)

/**
 * The keyed list [AnimatedTileLayout] draws: the current tiles, then those that left and are still
 * fading out. Main thread only; [update] is idempotent for the same input.
 */
internal class TileRetention {
    private var current: Map<String, TilePlacement> = emptyMap()
    private val departed = LinkedHashMap<String, TilePlacement>()
    private val seen = HashSet<String>()
    private var lastTiles: List<TilePlacement>? = null
    private var lastSnap = false
    private var lastExpired = -1
    private var lastDrawn: List<RetainedPlacement> = emptyList()
    private var entering: Set<String> = emptySet()

    /**
     * The tiles to draw for [tiles]. With [snap] a tile that left goes at once and a new one appears
     * without fading in; the first list never fades in. [expired] changes after each [expire].
     */
    fun update(tiles: List<TilePlacement>, snap: Boolean, expired: Int = 0): List<RetainedPlacement> {
        if (tiles == lastTiles && snap == lastSnap && expired == lastExpired) return lastDrawn
        if (tiles != lastTiles) {
            val next = tiles.associateBy { it.id }
            val first = lastTiles == null
            for ((id, placement) in current) {
                if (id !in next) departed[id] = placement
            }
            if (snap) {
                // Gone at once: forgotten, so a return later enters with its fade.
                seen.removeAll(departed.keys - next.keys)
                departed.clear()
            }
            next.keys.forEach { departed.remove(it) }
            entering = if (first || snap) emptySet() else next.keys.filterTo(HashSet()) { it !in seen }
            seen.addAll(next.keys)
            current = next
        }
        lastTiles = tiles
        lastSnap = snap
        lastExpired = expired
        lastDrawn = tiles.map { RetainedPlacement(it, departing = false, entering = it.id in entering) } +
            departed.values.map { RetainedPlacement(it, departing = true, entering = false) }
        return lastDrawn
    }

    /** The fade of [id] ended: drop it if it is still departed. Returns whether the list changed. */
    fun expire(id: String): Boolean {
        if (departed.remove(id) == null) return false
        seen.remove(id)
        return true
    }
}

/** Per-tile bounds animation state, driven from the layout phase. */
internal class TileMotion(private val scope: CoroutineScope) {
    private class Tile(var target: IntRect) {
        var from: IntRect = target
        var progress: Animatable<Float, AnimationVector1D>? = null
        var job: Job? = null

        fun current(): IntRect = progress?.let { lerp(from, target, it.value) } ?: target
    }

    private val tiles = HashMap<Any, Tile>()

    // Bumped when a tile is moved from outside a layout pass, so the layout measures again.
    private var version by mutableIntStateOf(0)

    /** Read by a layout pass so a [releaseAt] measures it again. */
    fun observe(): Int = version

    fun rectOf(id: Any, target: IntRect, eased: Boolean): IntRect {
        val tile = tiles[id] ?: return target.also { tiles[id] = Tile(it) }
        if (tile.target != target) {
            val from = tile.current()
            tile.job?.cancel()
            tile.target = target
            if (eased && from != target) {
                ease(tile, from)
            } else {
                tile.progress = null
                tile.job = null
            }
        }
        return tile.current()
    }

    fun currentRect(id: Any): IntRect? = tiles[id]?.current()

    fun releaseAt(id: Any, rect: IntRect) {
        val tile = tiles[id] ?: return
        tile.job?.cancel()
        ease(tile, rect)
        version++
    }

    private fun ease(tile: Tile, from: IntRect) {
        val progress = Animatable(0f)
        tile.from = from
        tile.progress = progress
        // Undispatched, so the tile holds its current rect until the first animation frame.
        tile.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            progress.animateTo(1f, tween(TILE_MOVE_ANIM_MS, easing = TileMoveEasing))
        }
    }

    fun retain(ids: Set<Any>) {
        val gone = tiles.keys - ids
        gone.forEach { tiles.remove(it)?.job?.cancel() }
    }
}
