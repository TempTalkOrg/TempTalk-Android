package com.difft.android.call.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.CallUiController
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.actionbar.ActionBarPlan
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest

/** 竖屏（窄屏）模式下，参会人数达到该值起改用可滚动的 2 列方形画廊。 */
private const val PORTRAIT_SCROLL_FROM = 7

/** Portrait cell gap (dp). Unrelated to chrome spacing — do not merge with [PORTRAIT_CONTENT_GAP]. */
private const val PORTRAIT_CELL_GAP_DP = 8

/** 竖屏网格左右外边距。 */
private val PORTRAIT_HORIZONTAL_PADDING = 16.dp

/** Extra gap between the grid and the top/bottom chrome bars; see [LCallUiConstants.CHROME_CONTENT_GAP_DP]. */
private val PORTRAIT_CONTENT_GAP = LCallUiConstants.CHROME_CONTENT_GAP_DP.dp

/**
 * Portrait top title-bar reserved height (excluding the status-bar inset); see [LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP].
 * 用于给网格顶部预留空间，避免被标题栏覆盖。见 [MainPageWithTopStatusView]。
 */
private val PORTRAIT_TOP_BAR_HEIGHT = LCallUiConstants.TOP_BAR_TOTAL_HEIGHT_DP.dp

/**
 * 竖屏参会人数超过该值时改用紧凑顶部间距，见 [portraitTopReserved]。
 * 多人时格子已被压缩得较小，首排离标题栏过远会明显浪费竖向空间。
 */
private const val PORTRAIT_COMPACT_TOP_ABOVE = 4

/**
 * 紧凑模式下的顶部标题栏占位高度（不含状态栏 inset）：只让出 TopStatusBar 的视觉高度
 * (top margin + bar height), deliberately omitting [LCallUiConstants.TOP_BAR_MARGIN_BOTTOM_DP]
 * and [PORTRAIT_CONTENT_GAP]. The title-bar text's baseline sits roughly at the bar-height edge,
 * so the first row still never overlaps it.
 */
private val PORTRAIT_TOP_BAR_HEIGHT_COMPACT =
    (LCallUiConstants.TOP_BAR_MARGIN_TOP_DP + LCallUiConstants.TOP_BAR_HEIGHT_DP).dp

/**
 * Portrait grid bottom reserve for one action-bar plan: the whole chrome stack the plan
 * occupies (bar, its bottom margin, and the outside Emoji pill when the plan has one) plus the
 * breathing gap, so the last row always rests above the floating controls. The plan is a pure
 * function of the window size, so this follows fold / unfold and rotation.
 */
internal fun portraitBottomReserved(plan: ActionBarPlan): Dp =
    plan.chromeBottomReserveDp.dp + PORTRAIT_CONTENT_GAP

/**
 * Bottom inset the scrolling gallery keeps below its last row once the chrome is hidden — enough
 * that the content is not flush with the screen edge.
 *
 * Mirrors iOS `RoomContextView`'s hidden-chrome inset `safeAreaInsets.bottom + 24`. The safe-area
 * term is 0 here: the call window hides the navigation bar for its whole lifetime (see
 * `LCallActivity.configureWindow`) and the call content consumes only `WindowInsets.statusBars`,
 * so no bottom system inset ever reaches this layout and only the 24 dp breathing gap survives.
 *
 * Unlike iOS the Emoji entry earns no reserve in this state: it is part of the action bar and fades
 * out with it, so reserving its band would hold space for an invisible control.
 */
internal val PORTRAIT_BOTTOM_RESIDUAL = 24.dp

/**
 * Height the portrait grid leaves free above its first row, excluding the status-bar inset.
 * With the title bar hidden the grid keeps only that inset, so the first row aligns directly
 * under the status bar (matches iOS `RoomContextView`'s `safeArea + (showControls ? 44 : 0)`).
 *
 * Only the scrolling gallery ever passes `topVisible = false`, because only it is top-aligned and
 * therefore has a blank band to reclaim. The fixed ≤6 layout always passes `true`: it is centred,
 * and it uses this value to bound its tile size rather than to place its first row.
 */
internal fun portraitTopReserved(count: Int, topVisible: Boolean): Dp = when {
    !topVisible -> 0.dp
    count > PORTRAIT_COMPACT_TOP_ABOVE -> PORTRAIT_TOP_BAR_HEIGHT_COMPACT
    else -> PORTRAIT_TOP_BAR_HEIGHT + PORTRAIT_CONTENT_GAP
}

/**
 * Whether the portrait grid's reserves follow the chrome's show/hide. Only the top-aligned
 * scrolling gallery responds: the centred fixed ≤6 layout absorbs the change by construction, and
 * PiP renders no chrome at all. The count term is what excludes a PiP-sized gallery.
 */
internal fun portraitTopFollowsTitleBar(count: Int, forceScrollGrid: Boolean): Boolean =
    !forceScrollGrid && count > PORTRAIT_COMPACT_TOP_ABOVE

/**
 * Top offset that centres a [contentHeight]-tall block vertically on the whole [availableHeight],
 * clamped into the band the chrome leaves free: never above [minTop], and never so low that the
 * block's bottom edge crosses into [bottomReserve].
 *
 * Mirrors iOS `RoomView`'s `screenCenteredTop = max(topInset, (height - contentHeight) / 2)`: the
 * block is centred on the **full** screen height rather than on the band between the chrome bars,
 * so a tall bottom reserve does not push the tiles visually high. [minTop] carries the top chrome
 * (status-bar inset + title-bar reserve), which keeps the block clear of the title bar on screens
 * short enough for the centred position to reach it.
 *
 * [bottomReserve] carries the opposite clamp, which full-height centring makes necessary: centring
 * pushes the block down by `(bottomReserve - minTop) / 2` relative to the between-the-bars
 * position, and a block that already fills that band — unconditional at 2 participants, and at 3–6
 * on short windows — has no slack to absorb it, so its last row would render under the floating
 * control bar and barrage entry. Clamping degrades the placement to between-the-bars exactly when
 * the slack runs out, and is inert whenever there is slack.
 *
 * Both clamps are simultaneously satisfiable for the production caller: it sizes the block against
 * `availableHeight - minTop - bottomReserve`, so [contentHeight] fits between the bars. [maxOf]
 * still applies [minTop] last, so a degenerate window (reserves taller than the screen itself)
 * degrades to the top chrome rather than to a negative offset.
 */
internal fun portraitCenteredTop(
    availableHeight: Dp,
    contentHeight: Dp,
    minTop: Dp,
    bottomReserve: Dp,
): Dp = maxOf(
    minTop,
    minOf((availableHeight - contentHeight) / 2, availableHeight - bottomReserve - contentHeight),
)

/** Title-bar reveal / hide animation duration. Mirrors iOS' 0.25s ease-in-out. */
internal const val TOP_REVEAL_ANIM_MS = 250

/**
 * Reveal progress of the top status bar: `1f` = shown, `0f` = hidden.
 *
 * Subscribes with `collectLatest` inside a [LaunchedEffect] rather than `collectAsState` on purpose:
 * a toggle must not recompose this subtree. The [LazyVerticalGrid] content lambda is re-created
 * on every recomposition of its enclosing scope, and compose-foundation rebuilds the whole item
 * provider from a referentially-new content lambda — so one recomposition here recomposes every
 * participant cell (ConstraintLayout + AndroidView renderer). Same rationale as the
 * zero-recomposition chain documented in `CommonCallOverlays`.
 *
 * Callers MUST read [Animatable.value] only from a measure or placement lambda.
 *
 * Only the scrolling gallery subscribes: the centred fixed layout and PiP never construct this at
 * all, so there is no "disabled" mode to pin the progress for.
 */
@Composable
internal fun rememberTopBarRevealProgress(
    controller: CallUiController,
): Animatable<Float, AnimationVector1D> {
    // StateFlow.value is a plain read (not snapshot state) — it registers no observation.
    val progress = remember { Animatable(if (controller.showTopStatusViewEnabled.value) 1f else 0f) }
    LaunchedEffect(controller) {
        var isFirst = true
        // collectLatest, never collect: animateTo suspends for the whole TOP_REVEAL_ANIM_MS, so a
        // serialized collector would queue a reversal and play wrong-direction movement first.
        controller.showTopStatusViewEnabled.collectLatest { visible ->
            val target = if (visible) 1f else 0f
            if (isFirst) {
                isFirst = false
                // Entering the branch must not animate.
                progress.snapTo(target)
            } else {
                progress.animateTo(target, tween(TOP_REVEAL_ANIM_MS, easing = FastOutSlowInEasing))
            }
        }
    }
    return progress
}

/**
 * [PaddingValues] whose **both vertical edges** are resolved lazily, from [top] and [bottom].
 * LazyGrid reads content padding inside its measure lambda — `calculateTopPadding()` and
 * `calculateBottomPadding()` on adjacent lines of the same `LazyLayoutMeasurePolicy` block — so an
 * animated value read here re-measures the grid **without recomposing it**, and therefore without
 * recomposing any participant cell. Identity equality is intentional: the instance is remembered,
 * so LazyGrid's measure-policy `remember` key stays stable across the animation.
 *
 * The horizontal edges stay plain [Dp]: nothing animates them.
 */
@Stable
internal class DeferredTopPaddingValues(
    private val horizontal: Dp,
    private val bottom: () -> Dp,
    private val top: () -> Dp,
) : PaddingValues {
    override fun calculateTopPadding(): Dp = top()
    override fun calculateBottomPadding(): Dp = bottom()
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = horizontal
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = horizontal
}

/** Static content padding for the PiP gallery — hoisted so its identity never changes. */
private val PORTRAIT_PIP_ZERO_PADDING = PaddingValues(0.dp)

/**
 * Content padding for the non-PiP scrolling gallery: both vertical edges follow [reveal], so the
 * grid reclaims the top title-bar band and the bottom control band as the chrome hides.
 *
 * The bottom edge is a scroll **content inset**, not an outer padding: it grows the scrollable
 * extent, so the last row comes to rest above the floating control bar and barrage entry instead of
 * sliding underneath them. iOS does the same thing in `RoomContextView` /`RoomView`, applying
 * `visibleToolbarInset + bulletReservedInset` inside the scroll content.
 *
 * Owning this here rather than at the call site keeps the whole reserve decision in production,
 * where the tests can exercise it directly.
 */
@Composable
internal fun rememberGalleryContentPadding(
    topInset: Dp,
    count: Int,
    reveal: Animatable<Float, AnimationVector1D>,
    bottomReserve: Dp,
): PaddingValues {
    val shownTop = topInset + portraitTopReserved(count, topVisible = true)
    val hiddenTop = topInset + portraitTopReserved(count, topVisible = false)
    return remember(shownTop, hiddenTop, reveal, bottomReserve) {
        // Both lambdas read reveal.value at measure time only, never from a composable scope.
        DeferredTopPaddingValues(
            horizontal = PORTRAIT_HORIZONTAL_PADDING,
            bottom = { lerp(PORTRAIT_BOTTOM_RESIDUAL, bottomReserve, reveal.value) },
        ) { lerp(hiddenTop, shownTop, reveal.value) }
    }
}

/**
 * 竖屏（窄屏）多人会议布局。按参会人数分三类：
 * - PiP 或 人数 ≥ [PORTRAIT_SCROLL_FROM]：2 列方形可滚动画廊（全部参会人往下平铺）。
 * - 1 人：全屏铺满（无边距/圆角）。
 * - 2 ~ 6 人：固定布局（不滚动），格子尺寸由 [computePortraitCells] 计算，整体水平居中；
 *   vertically always centered (content is guaranteed to fit one screen);
 *   only the 7+ scroll gallery is top-aligned.
 *
 * 设计稿规则（390px 竖屏手机）：
 * - 1 人：撑满整格；2 人：单栏上下对半（满宽半高）；
 * - 3 人：单栏堆叠锁 1:1；4–6 人：双栏锁 1:1、奇数末格居中；7+：双栏滚动画廊。
 */
@Composable
fun PortraitParticipantLayout(
    participants: List<Participant>,
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean,
    topInset: Dp,
    coroutineScope: CoroutineScope,
    displayInfoMap: Map<String, CallUserDisplayInfo>,
    forceScrollGrid: Boolean,
    bottomReserve: Dp,
) {
    val useScrollGrid = forceScrollGrid || participants.size >= PORTRAIT_SCROLL_FROM
    when {
        useScrollGrid -> {
            if (!portraitTopFollowsTitleBar(participants.size, forceScrollGrid)) {
                // PiP does not show the top/bottom chrome bars; fill with minimal padding and
                // skip subscribing to the title-bar state entirely.
                PortraitScrollGallery(
                    participants = participants,
                    viewModel = viewModel,
                    room = room,
                    muteOtherEnabled = muteOtherEnabled,
                    coroutineScope = coroutineScope,
                    displayInfoMap = displayInfoMap,
                    gridModifier = Modifier.padding(
                        start = PORTRAIT_HORIZONTAL_PADDING,
                        top = topInset + PORTRAIT_HORIZONTAL_PADDING,
                        end = PORTRAIT_HORIZONTAL_PADDING,
                        bottom = 4.dp
                    ),
                    contentPadding = PORTRAIT_PIP_ZERO_PADDING
                )
            } else {
                // 7+ participants use a scrollable gallery: both vertical reserves stretch and
                // shrink with the chrome's visibility, which the toggles flip in lockstep. The
                // bottom edge is a scroll content inset rather than an outer padding, so once
                // scrolled to the end the last row rests above the floating controls instead of
                // hiding under them — matching iOS `RoomContextView`, which applies its own
                // toolbar + bullet-entry inset inside the scroll content.
                val reveal = rememberTopBarRevealProgress(viewModel.callUiController)
                val contentPadding =
                    rememberGalleryContentPadding(topInset, participants.size, reveal, bottomReserve)
                PortraitScrollGallery(
                    participants = participants,
                    viewModel = viewModel,
                    room = room,
                    muteOtherEnabled = muteOtherEnabled,
                    coroutineScope = coroutineScope,
                    displayInfoMap = displayInfoMap,
                    gridModifier = Modifier,
                    contentPadding = contentPadding
                )
            }
        }
        // 单人：全屏铺满（无边距、无圆角、边到边），符合设计稿「1 人撑满整格」。
        participants.size == 1 -> {
            val participant = participants[0]
            val uid = when (participant) {
                is LocalParticipant -> globalServices.myId
                else -> participant.identity?.value ?: ""
            }
            // Keyed like every other tile site so per-tile state (mute menu) never survives a
            // participant swap in the single-tile slot.
            key(participant.sid.value) {
                MultiParticipantItem(
                    viewModel = viewModel,
                    room = room,
                    participant = participant,
                    modifier = Modifier.fillMaxSize(),
                    uid = uid,
                    userDisplayInfo = displayInfoMap[uid] ?: CallUserDisplayInfo(null, null, null),
                    participantIndex = 0,
                    participantCount = participants.size,
                    muteOtherEnabled = muteOtherEnabled,
                    onClickMute = { name -> viewModel.toggleMute(participant, name) },
                    coroutineScope = coroutineScope,
                    cornerRadius = 0.dp
                )
            }
        }
        // 2~6 人：固定布局，夹在顶部标题栏与底部控制栏之间居中展示。
        else -> {
            FixedPortraitGrid(
                participants = participants,
                viewModel = viewModel,
                room = room,
                muteOtherEnabled = muteOtherEnabled,
                topInset = topInset,
                coroutineScope = coroutineScope,
                displayInfoMap = displayInfoMap,
                bottomReserve = bottomReserve,
            )
        }
    }
}

/**
 * A 2-column scrollable gallery. Both call sites (PiP and 7+ participants) share this same
 * content, differing only in [gridModifier] and [contentPadding]. The extraction is load-bearing:
 * neither call site reads the reveal progress inside this composable's scope, so the `items { }`
 * lambda is never re-created by the animation and compose-foundation keeps the existing item
 * provider (no cell recomposes).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortraitScrollGallery(
    participants: List<Participant>,
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean,
    coroutineScope: CoroutineScope,
    displayInfoMap: Map<String, CallUserDisplayInfo>,
    gridModifier: Modifier,
    contentPadding: PaddingValues,
) {
    CompositionLocalProvider(
        LocalOverscrollConfiguration provides null
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            verticalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp),
            horizontalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp),
            contentPadding = contentPadding,
            modifier = Modifier
                .testTag("call_render_multi_grid")
                .then(gridModifier),
        ) {
            items(
                count = participants.size,
                key = { index -> participants[index].sid.value }
            ) { index ->
                val participant = participants[index]
                val uid = when (participant) {
                    is LocalParticipant -> globalServices.myId
                    else -> participant.identity?.value ?: ""
                }
                MultiParticipantItem(
                    viewModel = viewModel,
                    room = room,
                    participant = participant,
                    modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                    uid = uid,
                    userDisplayInfo = displayInfoMap[uid] ?: CallUserDisplayInfo(null, null, null),
                    participantIndex = index,
                    participantCount = participants.size,
                    muteOtherEnabled = muteOtherEnabled,
                    onClickMute = { name -> viewModel.toggleMute(participant, name) },
                    coroutineScope = coroutineScope
                )
            }
        }
    }
}

/**
 * ≤6 人时的固定布局。用 [BoxWithConstraints] 拿到可用宽高后由 [computePortraitCells] 算出
 * 列数、行数、格子尺寸；再以水平居中的 [Column] + [Row] 铺格子，末行落单的格子天然居中。
 *
 * The block always fits one screen by construction ([computePortraitCells] shrinks the tiles to
 * fit), so it is centred vertically for every supported count — only the scrollable 7+ gallery is
 * top-aligned. Because neither the tile size nor the block position depends on the title bar's
 * visibility, this layout needs no reveal animation at all: it is inert across a chrome toggle.
 */
@Composable
private fun FixedPortraitGrid(
    participants: List<Participant>,
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean,
    topInset: Dp,
    coroutineScope: CoroutineScope,
    displayInfoMap: Map<String, CallUserDisplayInfo>,
    bottomReserve: Dp,
) {
    if (participants.isEmpty()) return

    // The chrome reserve bounds the TILE SIZE so no tile can slide under a bar, and it is always
    // the "title shown" value: cell sizes stay frame-stable, so no live video Surface ever
    // rescales. The BLOCK POSITION is independent of it — see the centring below.
    val topChrome = topInset + portraitTopReserved(participants.size, topVisible = true)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_render_multi_grid")
            .padding(horizontal = PORTRAIT_HORIZONTAL_PADDING),
        contentAlignment = Alignment.TopCenter
    ) {
        val layout = computePortraitCells(
            count = participants.size,
            availableWidthDp = maxWidth.value,
            availableHeightDp = (maxHeight - topChrome - bottomReserve).value,
            gapDp = PORTRAIT_CELL_GAP_DP.toFloat()
        )
        val cellWidth = layout.cellWidthDp.dp
        val cellHeight = layout.cellHeightDp.dp
        val rows = participants.chunked(layout.columns)
        val contentHeight =
            cellHeight * layout.rows + PORTRAIT_CELL_GAP_DP.dp * (layout.rows - 1)

        Column(
            // Centred on the FULL height, not on the band between the chrome bars: the block sits
            // at the visual centre instead of being pushed up by the tall bottom reserve, and the
            // same reserve clamps the centring so a height-bound block never slides under the
            // floating controls. Nothing here reads the title bar's state, so the layout is inert
            // across a chrome toggle.
            modifier = Modifier.padding(
                top = portraitCenteredTop(
                    availableHeight = maxHeight,
                    contentHeight = contentHeight,
                    minTop = topChrome,
                    bottomReserve = bottomReserve,
                )
            ),
            verticalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            var participantIndex = 0
            rows.forEach { rowParticipants ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp)
                ) {
                    rowParticipants.forEach { participant ->
                        val index = participantIndex
                        participantIndex++
                        key(participant.sid.value) {
                            val uid = when (participant) {
                                is LocalParticipant -> globalServices.myId
                                else -> participant.identity?.value ?: ""
                            }
                            MultiParticipantItem(
                                viewModel = viewModel,
                                room = room,
                                participant = participant,
                                modifier = Modifier.width(cellWidth).height(cellHeight),
                                uid = uid,
                                userDisplayInfo = displayInfoMap[uid] ?: CallUserDisplayInfo(null, null, null),
                                participantIndex = index,
                                participantCount = participants.size,
                                muteOtherEnabled = muteOtherEnabled,
                                onClickMute = { name -> viewModel.toggleMute(participant, name) },
                                coroutineScope = coroutineScope
                            )
                        }
                    }
                }
            }
        }
    }
}

/** [computePortraitCells] 的计算结果：列数、行数、格子宽高（dp 数值）。 */
data class PortraitCellLayout(
    val columns: Int,
    val rows: Int,
    val cellWidthDp: Float,
    val cellHeightDp: Float,
)

/**
 * 竖屏 ≤6 人固定布局的格子尺寸计算（纯函数，便于单测）。
 *
 * 规则（对齐设计稿 packLayout）：
 * - 1 人：1 列 1 行，撑满可用区（非正方形）。
 * - 2 人：1 列 2 行，满宽、按高度均分（非正方形，沉浸式上下对半）。
 * - 3 人：1 列 3 行，锁 1:1 正方形（边长受宽/高较小者约束）。
 * - 4–6 人：2 列、ceil(n/2) 行，锁 1:1 正方形。
 *
 * @param count 参会人数，必须在 1..6。7+ 由调用方走滚动画廊。
 * @param availableWidthDp 可用宽度（dp，已扣除 padding）。
 * @param availableHeightDp 可用高度（dp，已扣除 padding）。
 * @param gapDp 格子间距（dp）。
 */
fun computePortraitCells(
    count: Int,
    availableWidthDp: Float,
    availableHeightDp: Float,
    gapDp: Float = PORTRAIT_CELL_GAP_DP.toFloat(),
): PortraitCellLayout {
    require(count in 1..6) { "computePortraitCells only supports 1..6, got $count" }

    val columns = if (count <= 3) 1 else 2
    val rows = kotlin.math.ceil(count.toDouble() / columns).toInt()

    val cellByWidth = (availableWidthDp - gapDp * (columns - 1)) / columns
    val cellByHeight = (availableHeightDp - gapDp * (rows - 1)) / rows

    return if (count <= 2) {
        // 1~2 人：不锁正方形，满宽 + 按高度均分，画面更沉浸。
        PortraitCellLayout(
            columns = columns,
            rows = rows,
            cellWidthDp = availableWidthDp.coerceAtLeast(0f),
            cellHeightDp = cellByHeight.coerceAtLeast(0f)
        )
    } else {
        // 3~6 人：锁 1:1，边长取宽/高较小者，多余空间由居中容器 letterbox。
        val side = minOf(cellByWidth, cellByHeight).coerceAtLeast(0f)
        PortraitCellLayout(
            columns = columns,
            rows = rows,
            cellWidthDp = side,
            cellHeightDp = side
        )
    }
}
