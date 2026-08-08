package com.difft.android.call.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallUserDisplayInfo
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope

/** 竖屏（窄屏）模式下，参会人数达到该值起改用可滚动的 2 列方形画廊。 */
private const val PORTRAIT_SCROLL_FROM = 7

/** 竖屏格子间距（dp）。 */
private const val PORTRAIT_CELL_GAP_DP = 8

/** 竖屏网格左右外边距。 */
private val PORTRAIT_HORIZONTAL_PADDING = 16.dp

/**
 * 竖屏顶部标题栏占位高度（不含状态栏 inset）：TopStatusBar 62dp + 顶部 8dp + 底部 4dp。
 * 用于给网格顶部预留空间，避免被标题栏覆盖。见 [MainPageWithTopStatusView]。
 */
private val PORTRAIT_TOP_BAR_HEIGHT = 74.dp

/**
 * 竖屏参会人数超过该值时改用紧凑顶部间距，见 [portraitTopReserved]。
 * 多人时格子已被压缩得较小，首排离标题栏过远会明显浪费竖向空间。
 */
private const val PORTRAIT_COMPACT_TOP_ABOVE = 4

/**
 * 紧凑模式下的顶部标题栏占位高度（不含状态栏 inset）：只让出 TopStatusBar 的视觉高度
 * （顶部 8dp + 62dp），省去标题栏底部 4dp 呼吸与 [PORTRAIT_CONTENT_GAP]。
 * 标题栏内的文字底边约在 62dp 处，因此首排格子仍不会被文字压到。
 */
private val PORTRAIT_TOP_BAR_HEIGHT_COMPACT = 70.dp

/**
 * 竖屏底部控制栏占位高度：控制按钮 48dp + 底部 32dp。
 * 用于给网格底部预留空间，避免被控制栏覆盖。见 [MainPageWithBottomControlView]。
 */
private val PORTRAIT_BOTTOM_BAR_HEIGHT = 80.dp

/**
 * 竖屏底部 emoji 弹幕按钮占位高度：图标 20dp + 上下 padding 各 12dp ≈ 44dp，外加 8dp 呼吸间距。
 * 该按钮浮在控制栏之上（见 [BarrageMessageView] 的 bottomPadding=88dp，即 [PORTRAIT_BOTTOM_BAR_HEIGHT]
 * + [PORTRAIT_CONTENT_GAP]），因此网格底部需在控制栏之外再让出这段高度，避免底部格子被按钮遮挡。
 */
private val PORTRAIT_EMOJI_BUTTON_HEIGHT = 52.dp

/** 网格与顶部/底部控制栏之间的额外间距。 */
private val PORTRAIT_CONTENT_GAP = 8.dp

/**
 * 竖屏网格底部总预留高度：控制栏 + emoji 弹幕按钮 + 间距，
 * 保证底部格子始终位于 emoji 按钮上方。
 */
private val PORTRAIT_BOTTOM_RESERVED =
    PORTRAIT_BOTTOM_BAR_HEIGHT + PORTRAIT_EMOJI_BUTTON_HEIGHT + PORTRAIT_CONTENT_GAP

/**
 * 竖屏网格顶部需让出的高度（不含状态栏 inset）。人数超过 [PORTRAIT_COMPACT_TOP_ABOVE]
 * 时收紧 12dp，让首排贴近顶部标题栏。
 */
private fun portraitTopReserved(count: Int): Dp =
    if (count > PORTRAIT_COMPACT_TOP_ABOVE) {
        PORTRAIT_TOP_BAR_HEIGHT_COMPACT
    } else {
        PORTRAIT_TOP_BAR_HEIGHT + PORTRAIT_CONTENT_GAP
    }

/**
 * 竖屏（窄屏）多人会议布局。按参会人数分三类：
 * - PiP 或 人数 ≥ [PORTRAIT_SCROLL_FROM]：2 列方形可滚动画廊（全部参会人往下平铺）。
 * - 1 人：全屏铺满（无边距/圆角）。
 * - 2 ~ 6 人：固定布局（不滚动），格子尺寸由 [computePortraitCells] 计算，整体水平居中；
 *   垂直方向 ≤[PORTRAIT_COMPACT_TOP_ABOVE] 人时居中，更多人时顶部对齐以收紧首排间距。
 *
 * 设计稿规则（390px 竖屏手机）：
 * - 1 人：撑满整格；2 人：单栏上下对半（满宽半高）；
 * - 3 人：单栏堆叠锁 1:1；4–6 人：双栏锁 1:1、奇数末格居中；7+：双栏滚动画廊。
 */
@OptIn(ExperimentalFoundationApi::class)
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
) {
    val useScrollGrid = forceScrollGrid || participants.size >= PORTRAIT_SCROLL_FROM
    when {
        useScrollGrid -> {
            // PiP 小窗口不显示顶部/底部控制栏，用最小内边距铺满；普通 7+ 才预留两栏空间。
            val gridTopPadding = if (forceScrollGrid) {
                topInset + PORTRAIT_HORIZONTAL_PADDING
            } else {
                topInset + portraitTopReserved(participants.size)
            }
            // 7+ 人为可滚动画廊：底部不预留 toolbar 间距，卡片一直平铺到底部（可滚动查看）。
            // PiP 小窗口维持最小内边距。
            val gridBottomPadding = if (forceScrollGrid) {
                4.dp
            } else {
                0.dp
            }
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp),
                    horizontalArrangement = Arrangement.spacedBy(PORTRAIT_CELL_GAP_DP.dp),
                    modifier = Modifier
                        .testTag("call_render_multi_grid")
                        .padding(
                            start = PORTRAIT_HORIZONTAL_PADDING,
                            top = gridTopPadding,
                            end = PORTRAIT_HORIZONTAL_PADDING,
                            bottom = gridBottomPadding
                        ),
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
                            muteOtherEnabled = muteOtherEnabled,
                            onClickMute = { viewModel.toggleMute(participant) },
                            coroutineScope = coroutineScope
                        )
                    }
                }
            }
        }
        // 单人：全屏铺满（无边距、无圆角、边到边），符合设计稿「1 人撑满整格」。
        participants.size == 1 -> {
            val participant = participants[0]
            val uid = when (participant) {
                is LocalParticipant -> globalServices.myId
                else -> participant.identity?.value ?: ""
            }
            MultiParticipantItem(
                viewModel = viewModel,
                room = room,
                participant = participant,
                modifier = Modifier.fillMaxSize(),
                uid = uid,
                userDisplayInfo = displayInfoMap[uid] ?: CallUserDisplayInfo(null, null, null),
                participantIndex = 0,
                muteOtherEnabled = muteOtherEnabled,
                onClickMute = { viewModel.toggleMute(participant) },
                coroutineScope = coroutineScope,
                cornerRadius = 0.dp
            )
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
                displayInfoMap = displayInfoMap
            )
        }
    }
}

/**
 * ≤6 人时的固定布局。用 [BoxWithConstraints] 拿到可用宽高后由 [computePortraitCells] 算出
 * 列数、行数、格子尺寸；再以水平居中的 [Column] + [Row] 铺格子，末行落单的格子天然居中。
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
) {
    if (participants.isEmpty()) return

    // 格子锁 1:1 后边长常被宽度卡住，可用高度会剩下一段 letterbox 余量。人少时余量居中最稳；
    // 人多时若仍居中，余量会被平分回顶部，抵消掉 portraitTopReserved 收紧的间距，故改为顶部对齐。
    val compactTop = participants.size > PORTRAIT_COMPACT_TOP_ABOVE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .testTag("call_render_multi_grid")
            .padding(
                start = PORTRAIT_HORIZONTAL_PADDING,
                top = topInset + portraitTopReserved(participants.size),
                end = PORTRAIT_HORIZONTAL_PADDING,
                bottom = PORTRAIT_BOTTOM_RESERVED
            ),
        contentAlignment = if (compactTop) Alignment.TopCenter else Alignment.Center
    ) {
        val layout = computePortraitCells(
            count = participants.size,
            availableWidthDp = maxWidth.value,
            availableHeightDp = maxHeight.value,
            gapDp = PORTRAIT_CELL_GAP_DP.toFloat()
        )
        val cellWidth = layout.cellWidthDp.dp
        val cellHeight = layout.cellHeightDp.dp
        val rows = participants.chunked(layout.columns)

        Column(
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
                                muteOtherEnabled = muteOtherEnabled,
                                onClickMute = { viewModel.toggleMute(participant) },
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
