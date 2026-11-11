


package com.difft.android.call.layout





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


enum class RequestOrientation(override val json: String) : JsonEnum { PORTRAIT("portrait"), LANDSCAPE("landscape"), ANY("any") }

enum class ContentMode(override val json: String) : JsonEnum { FILL("fill"), FIT("fit") }

enum class CallType(override val json: String) : JsonEnum { ONE_ON_ONE("1on1"), GROUP("group") }


enum class PipCorner(override val json: String) : JsonEnum { TL("tl"), TR("tr"), BL("bl"), BR("br") }




enum class Overflow(override val json: String) : JsonEnum { PAGES("pages"), MORE("more") }

data class Participant(
    val id: String,
    val isLocal: Boolean,
    
    val videoOn: Boolean,
    
    val videoAspect: Double? = null,
    val speaking: Boolean = false,
    
    
    val cameraOn: Boolean? = null,
)

data class ShareInput(
    val sharerId: String,
    val aspect: Double,
    val zoom: ContentMode? = null,
)

data class LayoutInput(
    
    val window: Size,
    
    
    val content: Rect,
    val callType: CallType,
    
    val participants: List<Participant>,
    val share: ShareInput? = null,
    
    val swapped: Boolean = false,
    
    val pipCorner: PipCorner? = null,
    
    val safe: Insets? = null,
    
    
    val fold: Rect? = null,
    
    
    val allowRotation: Boolean = false,
    val overflow: Overflow = Overflow.PAGES,
    
    val page: Int = 0,
    
    
    
    
    val pagingContent: Rect? = null,
    
    
    
    
    val controlsSize: Size? = null,
)



enum class Posture(override val json: String) : JsonEnum { FLAT("flat"), TABLETOP("tabletop"), BOOK("book") }

enum class TileRole(override val json: String) : JsonEnum {
    SOLO("solo"), GRID("grid"), MAIN("main"), PIP("pip"), SHARE("share"), OVERFLOW("overflow"),
}

data class Tile(
    val id: String,
    val role: TileRole,
    
    
    val rect: Rect,
    
    val contentMode: ContentMode?,
    
    val mismatch: Double,
    val z: Int,
    val overflowCount: Int? = null,
)

data class GridInfo(
    val cols: Int,
    
    val rows: Int,
    val card: Size,
    
    val scroll: Boolean,
    
    
    val visibleCount: Int,
    
    val contentHeight: Double,
    
    
    val adaptive: Boolean,
    
    
    val page: Int = 0,
    val pages: Int = 1,
    val later: Int = 0,
)


enum class LayoutMode(override val json: String) : JsonEnum { SOLO("solo"), GRID("grid"), PIP("pip"), DUO("duo"), SHARE("share") }

data class LayoutPlan(
    val windowClass: WindowClass,
    val orientation: Orientation,
    val requestOrientation: RequestOrientation,
    val mode: LayoutMode,
    val posture: Posture,
    val tiles: List<Tile>,
    val grid: GridInfo?,
    
    
    val dock: Rect?,
    
    
    val pipArea: Rect?,
    
    val controls: Rect? = null,
)
