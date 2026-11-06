// Synced from call-layout bf6b84ecbe, ports/kotlin/src/main/kotlin/org/difft/calllayout/Types.kt (spec v25), by scripts/sync-apps.ts.
// Do not edit here: change call-layout and sync again.

package com.difft.android.call.layout

// Shared types for the call layout core, ported from src/core/types.ts. Units are platform layout
// units: pt on iOS, dp on Android, CSS px on Desktop. Field names match the TS types and the keys
// of the golden file; enums carry their JSON spelling in `json`.

data class Size(val w: Double, val h: Double)

data class Point(val x: Double, val y: Double)

data class Rect(val x: Double, val y: Double, val w: Double, val h: Double)

data class Insets(val top: Double = 0.0, val right: Double = 0.0, val bottom: Double = 0.0, val left: Double = 0.0) {
    companion object {
        val ZERO = Insets()
    }
}

interface JsonEnum {
    val json: String
}

inline fun <reified E> jsonEnum(value: String): E where E : Enum<E>, E : JsonEnum =
    enumValues<E>().firstOrNull { it.json == value } ?: error("no ${E::class.simpleName} '$value'")

enum class WindowClass(override val json: String) : JsonEnum { COMPACT("compact"), WIDE("wide") }

enum class Orientation(override val json: String) : JsonEnum { PORTRAIT("portrait"), LANDSCAPE("landscape") }

// What the platform should allow or request for the call window: an Orientation or 'any'.
enum class RequestOrientation(override val json: String) : JsonEnum { PORTRAIT("portrait"), LANDSCAPE("landscape"), ANY("any") }

enum class ContentMode(override val json: String) : JsonEnum { FILL("fill"), FIT("fit") }

enum class CallType(override val json: String) : JsonEnum { ONE_ON_ONE("1on1"), GROUP("group") }

// Corner of the 1on1 floating window: top or bottom, then left or right.
enum class PipCorner(override val json: String) : JsonEnum { TL("tl"), TR("tr"), BL("bl"), BR("br") }

// What happens when the group does not fit one screen. PAGES (phones and folds): the grid turns
// pages, you on every page (decision 24). MORE (Desktop): one page, cards have no minimum size, and
// from moreMaxTiles + 1 people the last cell is a "+N" tile (role OVERFLOW) for the rest.
enum class Overflow(override val json: String) : JsonEnum { PAGES("pages"), MORE("more") }

data class Participant(
    val id: String,
    val isLocal: Boolean,
    // True once the first frame has arrived; avatar shows until then.
    val videoOn: Boolean,
    // Width / height of the latest frame. Required when videoOn.
    val videoAspect: Double? = null,
    val speaking: Boolean = false,
    // The camera is published and unmuted, first frame or not: the scheduler counts it as video, so a
    // camera off the first page, which nobody renders, still comes onto it. null: videoOn.
    val cameraOn: Boolean? = null,
)

data class ShareInput(
    val sharerId: String,
    val aspect: Double,
    val zoom: ContentMode? = null,
)

data class LayoutInput(
    // Full window, edge to edge.
    val window: Size,
    // Area left for tiles after safe areas and call chrome (top bar, toolbar).
    // Computed by the platform; the core never guesses chrome sizes.
    val content: Rect,
    val callType: CallType,
    // Already ordered: local first, then scheduler order.
    val participants: List<Participant>,
    val share: ShareInput? = null,
    // 1on1: local video in the main slot. A group of two: the other person comes first.
    val swapped: Boolean = false,
    // 1on1 only: the corner the floating window was snapped to (snapPipCorner); bottom right by default.
    val pipCorner: PipCorner? = null,
    // Window safe area; only used for the half-open stage. Defaults to zero.
    val safe: Insets? = null,
    // The fold while the device is half open, in window coordinates: iOS reports it as an active
    // `.division` reserved region, Android as a HALF_OPENED FoldingFeature. null when flat or closed.
    val fold: Rect? = null,
    // The app lets this window turn with the device even though it is compact, as a foldable's outer
    // display may be set to: the plan then requests any orientation, for calls and shares alike.
    val allowRotation: Boolean = false,
    val overflow: Overflow = Overflow.PAGES,
    // PAGES only: the page shown, from 0; out of range shows the nearest page.
    val page: Int = 0,
    // PAGES only: the content area with the controls shown, which decides who is on which page.
    // While the user has hidden the controls (decision 25) `content` is the larger area the page is
    // laid out in, and this stays the area with them shown, so hiding them never moves anyone to
    // another page. null (or equal to `content`): `content`.
    val pagingContent: Rect? = null,
    // TABLETOP only: the size of the controls the app keeps below the fold (decision 39). The core then
    // places them (LayoutPlan.controls) at the window bottom and the 1on1 floating window at the top of
    // the space below the fold, above them or beside them; `content` runs down to the window bottom.
    // null: the app keeps its controls out of `content` itself and the window goes in the dock.
    val controlsSize: Size? = null,
)

// FLAT: no active fold. TABLETOP: horizontal fold, upper half stands up (laptop pose).
// BOOK: vertical fold, halves left and right.
enum class Posture(override val json: String) : JsonEnum { FLAT("flat"), TABLETOP("tabletop"), BOOK("book") }

enum class TileRole(override val json: String) : JsonEnum {
    SOLO("solo"), GRID("grid"), MAIN("main"), PIP("pip"), SHARE("share"), OVERFLOW("overflow"),
}

data class Tile(
    val id: String,
    val role: TileRole,
    // Window coordinates, inside the window. Only the TS legacy layouts the simulator compares with
    // scroll, and their grid tiles may extend below it.
    val rect: Rect,
    // null when the tile shows an avatar instead of video.
    val contentMode: ContentMode?,
    // 1 - min(src, dst) / max(src, dst): cropped share for fill, letterbox share for fit.
    val mismatch: Double,
    val z: Int,
    val overflowCount: Int? = null,
)

data class GridInfo(
    val cols: Int,
    // Rows on this page.
    val rows: Int,
    val card: Size,
    // The grid scrolls: only the TS legacy layouts the simulator compares with; the spec turns pages.
    val scroll: Boolean,
    // How many participants the first page holds, you included and a "+N" tile not; drives the
    // scheduler whichever page is shown.
    val visibleCount: Int,
    // Height of the block of cards on this page, gaps included.
    val contentHeight: Double,
    // true: cards follow their video shape in rows (cols = most cards in a row, card = first card),
    // on one screen or as paged rows (decisions 21, 24).
    val adaptive: Boolean,
    // The page shown (from 0), how many there are, and how many people are on the pages after it.
    // The grid functions leave the defaults; resolveLayout fills them in.
    val page: Int = 0,
    val pages: Int = 1,
    val later: Int = 0,
)

// DUO: a group call with two people, two cards of the same size, you first (decisions 29 and 30).
enum class LayoutMode(override val json: String) : JsonEnum { SOLO("solo"), GRID("grid"), PIP("pip"), DUO("duo"), SHARE("share") }

data class LayoutPlan(
    val windowClass: WindowClass,
    val orientation: Orientation,
    val requestOrientation: RequestOrientation,
    val mode: LayoutMode,
    val posture: Posture,
    val tiles: List<Tile>,
    val grid: GridInfo?,
    // TABLETOP only: free area below the fold for the member list and chat;
    // the toolbar stays at the bottom of the window.
    val dock: Rect?,
    // 1on1 only: where the floating window may go. A dragged window snaps to a corner of it
    // (snapPipCorner) and the platform passes that corner back as pipCorner.
    val pipArea: Rect?,
    // TABLETOP with controlsSize only: where the app puts its controls below the fold (decision 39).
    val controls: Rect? = null,
)
