package com.difft.android.call.ui.actionbar

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.core.CallUiController
import com.difft.android.call.ui.tabletop.LocalCallTabletop
import com.difft.android.call.ui.tabletop.TabletopGeometry
import kotlin.math.ceil

/**
 * Vertical control rail on the window's end edge (#1451). The single rule for when it replaces
 * the bottom action bar, and its geometry.
 */
object CallSideRailSpec {
    const val MIN_WINDOW_HEIGHT_DP = 480
    const val START_INSET_DP = 12
    const val END_INSET_DP = 24
    const val BOTTOM_INSET_DP = 24
    const val BUTTON_DP = CallActionBarPlanner.BUTTON_DP  // 48
    const val GAP_DP = CallActionBarPlanner.GAP_DP  // 12
    const val SLOT_COUNT = 6
    const val STACK_HEIGHT_DP = SLOT_COUNT * BUTTON_DP + (SLOT_COUNT - 1) * GAP_DP  // 348

    /** Least space between the minimize control at the rail's top and the control stack. */
    const val MIN_MINIMIZE_GAP_DP = 8

    /**
     * Rail height below its top inset (status band or cutout clearance): minimize, gap, stack,
     * bottom inset. A window [MIN_WINDOW_HEIGHT_DP] tall leaves 480 - 428 = 52dp for that inset.
     */
    const val HEIGHT_BELOW_TOP_INSET_DP = BUTTON_DP + MIN_MINIMIZE_GAP_DP + STACK_HEIGHT_DP + BOTTOM_INSET_DP  // 428

    /** Controls when a group screen share has room for both People and Emoji. */
    const val ROOMY_SLOT_COUNT = SLOT_COUNT + 1
    const val ROOMY_STACK_HEIGHT_DP = ROOMY_SLOT_COUNT * BUTTON_DP + (ROOMY_SLOT_COUNT - 1) * GAP_DP  // 408

    /**
     * Rail iff the window is landscape and at least [MIN_WINDOW_HEIGHT_DP] tall. Sizes are dp as
     * Float (px / density, not truncated), so `width > height` keeps the px ordering the wide-grid
     * rule uses: rail implies wide whenever not in PiP.
     */
    fun showsRail(widthDp: Float, heightDp: Float): Boolean =
        widthDp > heightDp && heightDp >= MIN_WINDOW_HEIGHT_DP
}

/**
 * A shown rail (#1451). Its column never moves sideways: the end inset is fixed and a display
 * cutout only lowers [topInset] or raises [bottomInsetDp] where it overlaps the rail's band.
 * [fitsSevenControls]: the window is tall enough for [CallSideRailSpec.ROOMY_SLOT_COUNT] controls.
 */
data class SideRail(
    val topInset: Dp = 0.dp,
    val bottomInsetDp: Int = CallSideRailSpec.BOTTOM_INSET_DP,
    val fitsSevenControls: Boolean = false,
) {
    val endInsetDp: Int get() = CallSideRailSpec.END_INSET_DP

    /** Horizontal band the rail takes from the window's end edge: 12 + 48 + 24 = 84. */
    val widthDp: Int get() = CallSideRailSpec.START_INSET_DP + CallSideRailSpec.BUTTON_DP + endInsetDp
}

/**
 * The rail for a window, or null when the bottom bar is used. [statusBandDp] is the status bar's
 * height whether or not it is shown (see [rememberCallSideRail]); [cutouts] are the display
 * cutout's bounding rects in window dp. The minimize control sits below both (level with the title
 * bar outside a screen share); at the minimum height the controls win over clearing a tall band.
 */
fun resolveSideRail(
    widthDp: Float,
    heightDp: Float,
    statusBandDp: Float = 0f,
    cutouts: List<Rect> = emptyList(),
    isRtl: Boolean = false,
): SideRail? {
    if (!CallSideRailSpec.showsRail(widthDp, heightDp)) return null
    val railWidthDp = (CallSideRailSpec.START_INSET_DP + CallSideRailSpec.BUTTON_DP + CallSideRailSpec.END_INSET_DP).toFloat()
    val clearance = railCutoutClearance(cutouts, widthDp, heightDp, railWidthDp, isRtl)
    val bottomInsetDp = maxOf(CallSideRailSpec.BOTTOM_INSET_DP, ceil(clearance.bottomDp).toInt())
    val minimizeAndBelow = CallSideRailSpec.BUTTON_DP + CallSideRailSpec.MIN_MINIMIZE_GAP_DP + bottomInsetDp
    val wantedTop = maxOf(statusBandDp, clearance.topDp) + LCallUiConstants.TOP_BAR_MARGIN_TOP_DP
    val topDp = wantedTop.coerceAtMost(heightDp - minimizeAndBelow - CallSideRailSpec.STACK_HEIGHT_DP).coerceAtLeast(0f)
    return SideRail(
        topInset = topDp.dp,
        bottomInsetDp = bottomInsetDp,
        fitsSevenControls = heightDp - topDp >= minimizeAndBelow + CallSideRailSpec.ROOMY_STACK_HEIGHT_DP,
    )
}

/**
 * Where the call controls are: the bottom bar's plan, plus the rail or the tabletop dock when one
 * replaces the bar. Never both: [dock] != null implies [rail] == null.
 */
data class CallChromeLayout(val barPlan: ActionBarPlan, val rail: SideRail?, val dock: TabletopGeometry? = null)

/** Call window size in dp, untruncated. */
data class CallWindowSizeDp(val width: Float, val height: Float)

/**
 * The activity window, read like [rememberCallActionBarPlan]: the real container size, with
 * [android.content.res.Configuration] only before the first layout pass has populated it.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberCallWindowSizeDp(): CallWindowSizeDp {
    val containerSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    return if (containerSize.width > 0 && containerSize.height > 0) {
        CallWindowSizeDp(containerSize.width / density.density, containerSize.height / density.density)
    } else {
        CallWindowSizeDp(configuration.screenWidthDp.toFloat(), configuration.screenHeightDp.toFloat())
    }
}

/**
 * The rail for the current window, or null when the bottom bar is used.
 *
 * The top reserves the status bar's band even while the bar is hidden: screen share hides it,
 * yet an open sheet (its own window) brings it back until it closes, and the rail stays visible
 * under sheets, so its top and the seven-control fit must not follow that visibility. Outside a
 * share the bar is always shown, so this equals the visible inset there. The side cutout insets
 * are not used for geometry, since they flip sides for a moment while the bars toggle; only cutout
 * rects that overlap the rail move it (#1451).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberCallSideRail(): SideRail? {
    // The tabletop dock takes precedence over the rail, so every rail consumer falls back with it.
    if (LocalCallTabletop.current != null) return null
    val window = rememberCallWindowSizeDp()
    val density = LocalDensity.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val statusBandDp = WindowInsets.statusBarsIgnoringVisibility.getTop(density) / density.density
    val cutouts = rememberDisplayCutoutRectsDp()
    return remember(window, statusBandDp, cutouts, isRtl) {
        resolveSideRail(window.width, window.height, statusBandDp, cutouts, isRtl)
    }
}

/** Bar plan, rail and dock together, for consumers that reserve space from whichever is shown. */
@Composable
fun rememberCallChromeLayout(isGroup: Boolean): CallChromeLayout =
    CallChromeLayout(rememberCallActionBarPlan(isGroup), rememberCallSideRail(), LocalCallTabletop.current)

/**
 * Whether the chrome stays shown under open sheets and panels: beside the rail (#1451) and on the
 * tabletop dock, where the controls never cover the content.
 */
@Composable
fun rememberKeepsChromeUnderPanels(): Boolean = rememberCallSideRail() != null || LocalCallTabletop.current != null

/**
 * Keeps the screen-share speaker banner beside the rail. Static, like the share stage under it: the
 * stage does not resize when the rail hides, so the banner stays put too.
 */
fun Modifier.sideRailEndPadding(rail: SideRail?): Modifier =
    if (rail == null) this else this.padding(end = rail.widthDp.dp)

/** Controls on the rail, top to bottom. */
enum class RailSlot { MIC, VIDEO, SPEAKER, EMOJI, PEOPLE, MORE, END }

/**
 * Rail order. A group screen share adds People so the participants panel stays one tap away, before
 * Emoji when [fitsSevenControls], otherwise in Emoji's slot (Emoji then moves to More). A 1v1 call
 * has no panel, so it keeps Emoji (Invite stays in More).
 */
fun railSlots(isGroup: Boolean, isScreenShare: Boolean, fitsSevenControls: Boolean): List<RailSlot> {
    val groupShare = isGroup && isScreenShare
    return listOfNotNull(
        RailSlot.MIC,
        RailSlot.VIDEO,
        RailSlot.SPEAKER,
        RailSlot.PEOPLE.takeIf { groupShare },
        RailSlot.EMOJI.takeIf { !groupShare || fitsSevenControls },
        RailSlot.MORE,
        RailSlot.END,
    )
}

/** More-sheet quick actions while the rail is shown: what [railSlots] leaves off; Invite is never on the rail. */
fun railMoreQuickActions(isGroup: Boolean, isScreenShare: Boolean, fitsSevenControls: Boolean): List<ActionBarQuickAction> =
    when {
        !isGroup -> listOf(ActionBarQuickAction.INVITE)
        !isScreenShare -> listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.PEOPLE)
        fitsSevenControls -> listOf(ActionBarQuickAction.INVITE)
        else -> listOf(ActionBarQuickAction.INVITE, ActionBarQuickAction.EMOJI)
    }

/**
 * Whether the rail's band is reserved now: a rail is shown and the chrome is visible, the rail's own
 * fade target. Hiding the chrome (tap, screen-share auto-hide, an open sheet) frees it (#1451).
 * Without a rail nothing is collected, so the bottom bar's layouts do not recompose on a toggle.
 */
@Composable
fun rememberRailBandReserved(controller: CallUiController, rail: SideRail?): Boolean {
    if (rail == null) return false
    val visible by rememberChromeVisible(controller, controller.showBottomToolBarViewEnabled)
    return visible
}

/**
 * End padding read in the layout phase, so an animated value re-lays out the content without
 * recomposing the caller. Mirrored in RTL like `padding(end = ...)`.
 */
fun Modifier.endPadding(end: () -> Dp): Modifier = layout { measurable, constraints ->
    val endPx = end().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(constraints.offset(horizontal = -endPx))
    layout(constraints.constrainWidth(placeable.width + endPx), constraints.constrainHeight(placeable.height)) {
        placeable.placeRelative(0, 0)
    }
}
