package com.difft.android.call.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.MUTE_ACTION_INDEX
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.util.IdUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.collections.contains
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean = false,
    autoHideTimeout: Long,
    callConfig: CallConfig
) {
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val whoSharedScreen by viewModel.screenSharingUser.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val coroutineScope = rememberCoroutineScope()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600 ||
        configuration.screenWidthDp > configuration.screenHeightDp

    if (!isUserSharingScreen) {
        if (isWideScreen) {
            WideScreenParticipantLayout(
                participants = participants,
                viewModel = viewModel,
                room = room,
                muteOtherEnabled = muteOtherEnabled,
                topInset = topInset,
                coroutineScope = coroutineScope
            )
        } else {
            CompositionLocalProvider(
                LocalOverscrollConfiguration provides null
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(
                        start = 16.dp,
                        top = topInset + 16.dp,
                        end = 16.dp,
                        bottom = 4.dp),
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
                            muteOtherEnabled = muteOtherEnabled,
                            onClickMute = { viewModel.toggleMute(participant) },
                            coroutineScope = coroutineScope
                        )
                    }
                }
            }
        }
    } else {
        whoSharedScreen?.let { sharedParticipant ->
            // 显示屏幕分享画面
            ScreenSharingView( room = room, participant = sharedParticipant, reconnectCount = reconnectCount)
            // 显示屏幕分享时speaker悬浮窗
            ScreenShareSpeakerView(viewModel = viewModel, shareScreenUser = sharedParticipant, callConfig = callConfig)
        }
    }

    // 显示弹幕
    BarrageMessageView(
        viewModel,
        config = BarrageMessageConfig(
            isOneVOneCall = false,
            barrageTexts = callConfig.chatPresets ?: emptyList(),
            displayDurationMillis = autoHideTimeout,
            baseSpeed = callConfig.bubbleMessage?.baseSpeed ?: 4600L,
            deltaSpeed = callConfig.bubbleMessage?.deltaSpeed ?: 400L,
            columns = callConfig.bubbleMessage?.columns ?: listOf(10, 40, 70),
            emojiPresets = callConfig.bubbleMessage?.emojiPresets ?: LCallUiConstants.DEFAULT_BUBBLE_EMOJIS,
            textPresets = callConfig.bubbleMessage?.textPresets ?: LCallUiConstants.DEFAULT_BUBBLE_TEXTS,
            textMaxLength = callConfig.chatMessage?.maxLength ?: 30,
        ),
        { message, type, topic ->
            viewModel.rtm.sendChatBarrage(message, type, onComplete = { status ->
                if (status) {
                    if (type == RTM_MESSAGE_TYPE_DEFAULT) {
                        viewModel.showCallBarrageMessage(room.localParticipant, message)
                    }
                } else {
                    L.e { "[Call] Failed to send barrage message status = $status." }
                }
            })
        })

    if(isUserSharingScreen) {
        // 显示举手提示
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, end = 16.dp)
            ,
            contentAlignment = Alignment.TopEnd
        ){
            ShowHandsUpTipView(viewModel)
        }
    }
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

@Composable
private fun WideScreenParticipantLayout(
    participants: List<Participant>,
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean,
    topInset: Dp,
    coroutineScope: CoroutineScope
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
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

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (emptySlots > 0) {
                    Box(Modifier.weight(emptySlots / 2f))
                }
                row.forEach { participant ->
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
                                muteOtherEnabled = muteOtherEnabled,
                                onClickMute = { viewModel.toggleMute(participant) },
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

@Composable
fun MultiParticipantItem(
    viewModel: LCallViewModel,
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    uid: String,
    muteOtherEnabled: Boolean,
    onClickMute: () -> Unit,
    coroutineScope: CoroutineScope
){
    val contactorCacheManager = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }

    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }

    // Find the camera video stream to show
    val videoPub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA } } }

    var videoMuted by remember { mutableStateOf(true) }

    val context = LocalContext.current

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    var expanded by remember { mutableStateOf(false) }

    fun onClickItem(index: Int, setExpanded: (Boolean) -> Unit, onClickMute: () -> Unit) {
        setExpanded(false)
        when(index){
            MUTE_ACTION_INDEX -> onClickMute()
            else -> {}
        }
    }

    suspend fun updateNameAndAvatar(userId: String) {
        userDisplayInfo = contactorCacheManager.getParticipantDisplayInfo(context, userId)
    }

    fun handleClickScreen() {
        viewModel.callUiController.setShowTopStatusViewEnabled(
            !viewModel.callUiController.showTopStatusViewEnabled.value
        )
        viewModel.callUiController.setShowBottomToolBarViewEnabled(
            !viewModel.callUiController.showBottomToolBarViewEnabled.value
        )
    }

    // monitor video muted state
    LaunchedEffect(videoPub) {
        val pub = videoPub
        if (pub != null) {
            pub::muted.flow.collect { muted -> videoMuted = muted }
        } else {
            videoMuted = true
        }
    }

    LaunchedEffect(uid) {
        updateNameAndAvatar(uid)
    }

    LaunchedEffect(uid) {
        LCallManager.getContactsUpdateListener().collect { updatedIds ->
            if (updatedIds.contains(IdUtil.getUidByIdentity(uid))) {
                launch { updateNameAndAvatar(uid) }
            }
        }
    }

    Box(
        modifier = Modifier
            .pointerInput(Unit) {
                detectTapGestures (
                    onLongPress = {
                        if(participant.isMicrophoneEnabled && muteOtherEnabled){
                            expanded = true
                        }
                    },
                    onPress = {
                        handleClickScreen()
                    }
                )
            }
    ) {
        ConstraintLayout(
            modifier = modifier
                .clip(shape = RoundedCornerShape(8.dp))
                .background(color = DifftTheme.colors.background)
        ) {
            val (userView, statusView) = createRefs()

            Column(
                modifier = Modifier
                    .constrainAs(userView) {
                        top.linkTo(parent.top)
                        bottom.linkTo(parent.bottom)
                        start.linkTo(parent.start)
                        end.linkTo(parent.end)
                        width = Dimension.fillToConstraints
                        height = Dimension.fillToConstraints
                    },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    userDisplayInfo.avatar?.let { avatarImage ->
                        key (userDisplayInfo.avatar){
                            AndroidView(
                                factory = {
                                    avatarImage
                                },
                                modifier = Modifier
                                    .height(96.dp)
                                    .width(96.dp)
                            )
                        }
                    }
                    if (!videoMuted) {
                        VideoItemTrackSelector(
                            coroutineScope = coroutineScope,
                            modifier = Modifier.background(Color.Transparent),
                            room = room,
                            participant = participant,
                            sourceType = Track.Source.CAMERA,
                            viewType = ViewType.Surface,
                            draggable = false,
                            reconnectCount = reconnectCount,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .constrainAs(statusView){
                        start.linkTo(parent.start, 4.dp)
                        bottom.linkTo(parent.bottom, 4.33.dp)
                    }
                    .wrapContentWidth()
                    .height(24.dp)
                    .background(color = DifftTheme.colors.backgroundElevate, shape = RoundedCornerShape(size = 4.dp))
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                verticalAlignment = Alignment.Bottom,
            ) {
                ShowSpeakerStatusView(participant, userDisplayInfo.name, speakingEnabled = speakingEnabled)
            }
        }

        ShowItemOnClickView(listOf("Mute"), expanded, setExpanded = { value -> expanded = value} ,
            onClickItem = { index ->
                onClickItem(index,
                    setExpanded = {value -> expanded = value},
                    onClickMute= { onClickMute()}
                )
            }
        )

    }


}

