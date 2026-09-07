package com.difft.android.call.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
import com.difft.android.call.ui.screenshare.ScreenSharingView
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch


@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean = false,
    autoHideTimeout: Long,
    callConfig: CallConfig,
) {
    val participantsFlow = remember(viewModel) {
        viewModel.participants.distinctUntilChanged { old, new ->
            old.size == new.size && old.zip(new).all { (a, b) -> a.sid == b.sid }
        }
    }
    val participants by participantsFlow.collectAsState(initial = emptyList())
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val whoSharedScreen by viewModel.screenSharingUser.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val coroutineScope = rememberCoroutineScope()

    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val contactorCacheManager = entryPoint.contactorCacheManager
    val displayInfoMap by contactorCacheManager.participantDisplayMap.collectAsState()

    LaunchedEffect(participants) {
        val uidsToLoad = participants.mapNotNull { p ->
            val uid = when (p) {
                is LocalParticipant -> globalServices.myId
                else -> p.identity?.value ?: ""
            }
            uid.takeIf { it.isNotEmpty() && it !in displayInfoMap }
        }
        uidsToLoad.forEach { uid ->
            launch { contactorCacheManager.loadParticipantDisplay(uid) }
        }
    }

    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val containerSize = windowInfo.containerSize
    // Fall back to Configuration on the first composition, before the first layout pass populates
    // containerSize. Otherwise width would be 0 and isWideScreen would be wrong for the first frame.
    val widthDp = if (containerSize.width > 0) {
        with(density) { containerSize.width.toDp() }
    } else {
        configuration.screenWidthDp.dp
    }
    val isWideScreen = if (containerSize.width > 0 && containerSize.height > 0) {
        widthDp >= 600.dp || containerSize.width > containerSize.height
    } else {
        widthDp >= 600.dp || configuration.screenWidthDp > configuration.screenHeightDp
    }

    if (!isUserSharingScreen) {
        if (isWideScreen && !isInPipMode) {
            WideScreenParticipantLayout(
                participants = participants,
                viewModel = viewModel,
                room = room,
                muteOtherEnabled = muteOtherEnabled,
                topInset = topInset,
                coroutineScope = coroutineScope,
                displayInfoMap = displayInfoMap
            )
        } else {
            PortraitParticipantLayout(
                participants = participants,
                viewModel = viewModel,
                room = room,
                muteOtherEnabled = muteOtherEnabled,
                topInset = topInset,
                coroutineScope = coroutineScope,
                displayInfoMap = displayInfoMap,
                // PiP 小窗口空间有限，无论人数多少都用 2 列方形滚动网格，避免固定布局挤压。
                forceScrollGrid = isInPipMode,
                bottomReserve = portraitBottomReserved(rememberCallActionBarPlan(isGroup = true)),
            )
        }
    } else {
        whoSharedScreen?.let { sharedParticipant ->
            ScreenSharingView(room = room, participant = sharedParticipant, reconnectCount = reconnectCount)
            LaunchedEffect(sharedParticipant.sid) {
                viewModel.updateScreenShareFallback(sharedParticipant)
            }
        }
    }

    CallBarrageMessageSection(
        viewModel = viewModel,
        callConfig = callConfig,
        autoHideTimeout = autoHideTimeout,
        isOneVOneCall = false,
        room = room,
    )
}


private const val MIN_CELL_WIDTH_DP = 170
private const val CELL_GAP_DP = 8
private const val GRID_HORIZONTAL_PADDING_DP = 32 // must match start=16.dp + end=16.dp in WideScreenParticipantLayout

/**
 * 根据屏幕宽度自适应计算每行最多放几个格子。
 * 确保每格宽度不小于 [MIN_CELL_WIDTH_DP]dp，结果限制在 [2, 5]。
 */
private fun calculateMaxPerRow(screenWidthDp: Int): Int {
    val available = screenWidthDp - GRID_HORIZONTAL_PADDING_DP
    return ((available + CELL_GAP_DP) / (MIN_CELL_WIDTH_DP + CELL_GAP_DP)).coerceIn(2, 5)
}

/**
 * 宽屏模式下参会人行分配算法（自适应列数）：
 * - ≤2 人：1 行
 * - 3 ~ maxPerRow*2 人：2 行（均分）
 * - maxPerRow*2+1 ~ maxPerRow*3 人：3 行（尽量均分）
 * - > maxPerRow*3 人：截取前 maxPerRow*3 人，按均匀 3 行排列
 */
private fun <T> splitToRows(items: List<T>, maxPerRow: Int): List<List<T>> {
    val count = items.size
    if (count <= 0) return emptyList()
    if (count <= 2) return listOf(items)

    val maxTwoRows = maxPerRow * 2
    if (count <= maxTwoRows) {
        val firstCount = count / 2
        return listOf(
            items.subList(0, firstCount),
            items.subList(firstCount, count)
        )
    }

    val maxThreeRows = maxPerRow * 3
    if (count <= maxThreeRows) {
        val base = count / 3
        val remainder = count % 3
        val row1 = base
        val row2 = base + if (remainder == 2) 1 else 0
        val row3 = base + if (remainder >= 1) 1 else 0
        return listOf(
            items.subList(0, row1),
            items.subList(row1, row1 + row2),
            items.subList(row1 + row2, row1 + row2 + row3)
        )
    }

    return listOf(
        items.subList(0, maxPerRow),
        items.subList(maxPerRow, maxPerRow * 2),
        items.subList(maxPerRow * 2, maxPerRow * 3)
    )
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun WideScreenParticipantLayout(
    participants: List<Participant>,
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean,
    topInset: Dp,
    coroutineScope: CoroutineScope,
    displayInfoMap: Map<String, CallUserDisplayInfo>
) {
    val containerWidth = LocalWindowInfo.current.containerSize.width
    val screenWidthDp = if (containerWidth > 0) {
        with(LocalDensity.current) { containerWidth.toDp().value.toInt() }
    } else {
        LocalConfiguration.current.screenWidthDp
    }
    val maxPerRow = calculateMaxPerRow(screenWidthDp)
    val maxVisible = maxPerRow * 3

    val hasOverflow = participants.size > maxVisible
    val overflowCount = if (hasOverflow) participants.size - (maxVisible - 1) else 0
    val displayParticipants = if (hasOverflow) participants.take(maxVisible - 1) else participants

    val rows = if (hasOverflow) {
        listOf(
            displayParticipants.subList(0, maxPerRow),
            displayParticipants.subList(maxPerRow, maxPerRow * 2),
            displayParticipants.subList(maxPerRow * 2, maxVisible - 1)
        )
    } else {
        splitToRows(displayParticipants, maxPerRow)
    }

    if (rows.isEmpty()) return

    val maxItemsInRow = if (hasOverflow) {
        maxPerRow
    } else {
        rows.maxOf { it.size }
    }

    var participantOffset = 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = topInset + 16.dp, end = 16.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEachIndexed { rowIndex, row ->
            val isLastRow = rowIndex == rows.lastIndex
            val itemsInThisRow = row.size + if (hasOverflow && isLastRow) 1 else 0
            val needsCentering = itemsInThisRow == 1
            val emptySlots = if (needsCentering) maxItemsInRow - 1 else 0
            val rowOffset = participantOffset

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (emptySlots > 0) {
                    Box(Modifier.weight(emptySlots / 2f))
                }
                row.forEachIndexed { colIndex, participant ->
                    key(participant.sid.value) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
                                participantIndex = rowOffset + colIndex,
                                // The full room headcount, not displayParticipants.size: the
                                // widescreen grid truncates its tiles behind an overflow cell, but
                                // the badge's two-person rule is about the room.
                                participantCount = participants.size,
                                muteOtherEnabled = muteOtherEnabled,
                                onClickMute = { name -> viewModel.toggleMute(participant, name) },
                                coroutineScope = coroutineScope
                            )
                        }
                    }
                }
                if (hasOverflow && isLastRow) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        OverflowParticipantCell(
                            overflowCount = overflowCount,
                            modifier = Modifier.fillMaxSize(),
                            onClick = {
                                viewModel.callUiController.setShowUsersEnabled(
                                    !viewModel.callUiController.showUsersEnabled.value
                                )
                            }
                        )
                    }
                }
                if (emptySlots > 0) {
                    Box(Modifier.weight(emptySlots / 2f))
                }
            }
            participantOffset += row.size
        }
    }
}

@Composable
private fun OverflowParticipantCell(
    overflowCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(shape = RoundedCornerShape(8.dp))
            .background(color = DifftTheme.colors.background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(
                    color = Color.White.copy(alpha = 0.12f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+$overflowCount",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
