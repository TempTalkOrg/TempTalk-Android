package com.difft.android.call.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
import com.difft.android.call.ui.screenshare.ScreenSharingView
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import androidx.compose.ui.platform.LocalDensity
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.launch


@Composable
fun SingleParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    autoHideTimeout: Long,
    callConfig: CallConfig,
    conversationId: String?,
    callRole: CallRole?,
){
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val callStatus by viewModel.callStatus.collectAsState()
    // RECONNECTING 与已连接同等对待：重连期保持视频/共享挂载，避免被叫整块被移出树（黑屏）。
    val isConnected = callStatus == CallStatus.CONNECTED ||
        callStatus == CallStatus.RECONNECTED ||
        callStatus == CallStatus.RECONNECTING
    val remoteParticipant = participants.filterIsInstance<RemoteParticipant>().firstOrNull()
    val participantUid = remoteParticipant?.identity?.value ?: conversationId

    val videoTrackMap by room.localParticipant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }

    // Find the most appropriate video stream to show
    // Prioritize screen share, then camera, then any video stream.
    val videoPub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA } ?: videoPubs.firstOrNull() } }

    var videoMuted by remember { mutableStateOf(true) }

    // monitor video muted state
    LaunchedEffect(videoPub) {
        val pub = videoPub ?: return@LaunchedEffect
        pub::muted.flow.collect { muted -> videoMuted = muted }
    }

    // 点击小悬浮窗时，本端画面与主画面内容互相交换的状态。
    var isSelfInMain by remember { mutableStateOf(false) }
    // 仅当本端摄像头开启、出现小悬浮窗时才支持交换。
    val floatingVisible = isConnected && !videoMuted && !isUserSharingScreen

    // 悬浮窗消失（如关闭本端摄像头）时持久复位，保证下次开摄像头从对端主画面开始。
    LaunchedEffect(floatingVisible) {
        if (!floatingVisible) isSelfInMain = false
    }
    // 渲染统一使用派生值：LaunchedEffect 的复位会晚一帧，若仅依赖 isSelfInMain，
    // 在「已交换 + 关摄像头」的那一帧会错显本端，故用派生值与 floatingVisible 强同步。
    val effectiveIsSelfInMain = isSelfInMain && floatingVisible

    when {
        isConnected && isUserSharingScreen && remoteParticipant != null -> {
            ScreenSharingView(room = room, participant = remoteParticipant, reconnectCount = reconnectCount)
            LaunchedEffect(remoteParticipant.sid) {
                viewModel.updateScreenShareFallback(remoteParticipant)
            }
        }
        (isConnected || callRole == CallRole.CALLER) && participantUid != null -> {
            if (effectiveIsSelfInMain) {
                LocalParticipantVideoView(
                    room = room,
                    participant = room.localParticipant,
                    modifier = Modifier.fillMaxSize(),
                    reconnectCount = reconnectCount,
                )
            } else {
                SingleParticipantItem(
                    room = room,
                    participant = remoteParticipant,
                    uid = participantUid,
                    speakingEnabled = speakingEnabled,
                    reconnectCount = reconnectCount,
                    viewType = ViewType.Surface,
                )
            }
        }
    }


    if (floatingVisible) {
        // Default resting spot clears whatever the bar plan occupies (two rows + backplate,
        // a single row, or a row with the outside Emoji pill) plus the breathing gap. Seeded
        // once — the window stays draggable and its position must survive PiP round-trips.
        val plan = rememberCallActionBarPlan(isGroup = false)
        OneVOneSelfVideoView(
            viewModel = viewModel,
            onTap = { isSelfInMain = !isSelfInMain },
            defaultBottomPadding = (plan.chromeBottomReserveDp + LCallUiConstants.CHROME_CONTENT_GAP_DP).dp,
        ) {
            if (effectiveIsSelfInMain && participantUid != null) {
                // 已交换：悬浮窗内显示对端，必须用 Texture 以正确叠加在主画面之上。
                SingleParticipantItem(
                    room = room,
                    participant = remoteParticipant,
                    uid = participantUid,
                    speakingEnabled = speakingEnabled,
                    reconnectCount = reconnectCount,
                    viewType = ViewType.Texture,
                    draggable = false,
                    compact = true,
                )
            } else {
                LocalParticipantVideoView(
                    room = room,
                    participant = room.localParticipant,
                    reconnectCount = reconnectCount,
                )
            }
        }
    }

    CallBarrageMessageSection(
        viewModel = viewModel,
        callConfig = callConfig,
        autoHideTimeout = autoHideTimeout,
        isOneVOneCall = true,
        room = room,
    )
}


@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun OneVOneSelfVideoView(
    viewModel: LCallViewModel,
    onTap: () -> Unit = {},
    defaultBottomPadding: Dp = 120.dp,
    content: @Composable () -> Unit,
) {
    val videoViewWidth = 120.dp
    val videoViewHeight = 214.dp
    val paddingEnd = 12.dp
    val paddingBottom = defaultBottomPadding

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val containerSize = LocalWindowInfo.current.containerSize

    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    val maxOffsetX: Float
    val maxOffsetY: Float
    val paddingEndPx: Float
    val paddingBottomPx: Float
    with(density) {
        // Fall back to Configuration on the first composition (before first layout pass).
        // Without this, drag bounds collapse to 0 and the dragView snaps to (0,0).
        val screenWidthPx = if (containerSize.width > 0) {
            containerSize.width.toFloat()
        } else {
            configuration.screenWidthDp.dp.toPx()
        }
        val screenHeightPx = if (containerSize.height > 0) {
            containerSize.height.toFloat()
        } else {
            configuration.screenHeightDp.dp.toPx()
        }
        val videoWidthPx = videoViewWidth.toPx()
        val videoHeightPx = videoViewHeight.toPx()
        paddingEndPx = paddingEnd.toPx()
        paddingBottomPx = paddingBottom.toPx()
        maxOffsetX = (screenWidthPx - videoWidthPx).coerceAtLeast(0f)
        maxOffsetY = (screenHeightPx - videoHeightPx).coerceAtLeast(0f)
    }
    val currentMaxOffsetX by rememberUpdatedState(maxOffsetX)
    val currentMaxOffsetY by rememberUpdatedState(maxOffsetY)
    // pointerInput(Unit) 的协程仅首帧启动，捕获的 onTap 不会刷新；用 rememberUpdatedState
    // 保证始终调用最新回调（与上方拖拽用 currentMaxOffsetX/Y 的范式保持一致）。
    val currentOnTap by rememberUpdatedState(onTap)

    // 存储的是「逻辑位置」（全屏坐标系）。不要在屏幕尺寸变化时改写它，
    // 否则进入 PiP（LocalConfiguration 变成小窗尺寸）会把它夹到小窗范围内，
    // 退出 PiP 后无法还原，导致悬浮窗位置与进 PiP 前不一致。
    // 改为仅在渲染时按当前可视边界裁剪显示。
    var dragViewOffsetX by remember { mutableFloatStateOf(maxOffsetX - paddingEndPx) }
    var dragViewOffsetY by remember { mutableFloatStateOf(maxOffsetY - paddingBottomPx) }

    if (!isInPipMode) {
        // 全屏容器仅用于建立坐标系；悬浮窗以 TopStart 为锚点 + offset 定位
        // （offset 即窗口左上角位置）。拖拽与点击手势只挂在 120x214 的窗口本体上，
        // 保证「只有悬浮窗区域可拖拽」，窗口下方/周围的空白区域不响应拖拽。
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            dragViewOffsetX.coerceIn(0f, maxOffsetX).toInt(),
                            dragViewOffsetY.coerceIn(0f, maxOffsetY).toInt(),
                        )
                    }
                    .size(videoViewWidth, videoViewHeight)
                    .clip(shape = RoundedCornerShape(8.dp))
                    .background(DifftTheme.colors.backgroundSecondary)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragViewOffsetX = (dragViewOffsetX + dragAmount.x)
                                .coerceIn(0f, currentMaxOffsetX)
                            dragViewOffsetY = (dragViewOffsetY + dragAmount.y)
                                .coerceIn(0f, currentMaxOffsetY)
                        }
                    }
                    // 点击悬浮窗 → 交换主画面与悬浮窗内容。detectTapGestures 会消费抬起
                    // 事件，避免冒泡到 CallSurface 的 clickable；纯点击不触发拖拽 slop，两者共存。
                    .pointerInput(Unit) {
                        detectTapGestures { currentOnTap() }
                    }
            ) {
                content()
            }
        }
    }
}


@Composable
fun LocalParticipantVideoView(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    reconnectCount: Int = 0,
){
    val coroutineScope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clip(shape = RoundedCornerShape(8.dp))
    ) {
        VideoItemTrackSelector(
            coroutineScope = coroutineScope,
            room = room,
            participant = participant,
            sourceType = Track.Source.CAMERA,
            modifier = Modifier.fillMaxSize(),
            viewType = ViewType.Texture,
            draggable = false,
            reconnectCount = reconnectCount,
        )
    }
}


@Composable
fun SingleParticipantItem(
    room: Room,
    participant: Participant?,
    modifier: Modifier = Modifier,
    uid: String,
    speakingEnabled: Boolean = true,
    reconnectCount: Int = 0,
    viewType: ViewType = ViewType.Surface,
    draggable: Boolean? = null,
    compact: Boolean = false,
){
    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val contactorCacheManager = entryPoint.contactorCacheManager
    val callToChatController = entryPoint.callToChatController

    val coroutineScope = rememberCoroutineScope()

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    suspend fun updateNameAndAvatar(userId: String) {
        userDisplayInfo = contactorCacheManager.getParticipantDisplayInfo(userId)
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

    if (participant == null) {
        ParticipantAvatarInfo(
            modifier = modifier,
            userDisplayInfo = userDisplayInfo,
            userId = uid,
            callToChatController = callToChatController,
            compact = compact,
        )
        return
    }

    val identity by participant::identity.flow.collectAsState()
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    val effectiveIsSpeaking = isSpeaking && speakingEnabled
    val imageLoader = LocalImageLoaderProvider.localImageLoader()

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs by remember { derivedStateOf { audioTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val audioPub by remember { derivedStateOf { audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE } } }

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val videoPub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA } } }

    var videoMuted by remember { mutableStateOf(true) }
    var audioMuted by remember { mutableStateOf(true) }

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        val pub = audioPub ?: return@LaunchedEffect
        pub::muted.flow.collect { muted -> audioMuted = muted }
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

    Box(
        modifier = modifier.fillMaxSize()
            .testTag("call_render_single")
            .clip(shape = RoundedCornerShape(8.dp))
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        VideoItemTrackSelector(
            coroutineScope = coroutineScope,
            room = room,
            participant = participant,
            sourceType = Track.Source.CAMERA,
            scaleType = if (IdUtil.isPersonalMobileDevice(identity?.value)) ScaleType.Fill else ScaleType.FitInside,
            viewType = viewType,
            draggable = draggable ?: !IdUtil.isPersonalMobileDevice(identity?.value),
            reconnectCount = reconnectCount,
        )
        if (videoMuted) {
            ParticipantAvatarInfo(
                modifier = Modifier.background(DifftTheme.colors.background),
                userDisplayInfo = userDisplayInfo,
                userId = identity?.value ?: uid,
                audioMuted = audioMuted,
                isSpeaking = effectiveIsSpeaking,
                showAudioStatus = true,
                imageLoader = imageLoader,
                callToChatController = callToChatController,
                compact = compact,
            )
        }
    }
}

@Composable
private fun ParticipantAvatarInfo(
    modifier: Modifier = Modifier,
    userDisplayInfo: CallUserDisplayInfo,
    userId: String,
    audioMuted: Boolean = true,
    isSpeaking: Boolean = false,
    showAudioStatus: Boolean = false,
    imageLoader: coil3.ImageLoader? = null,
    callToChatController: com.difft.android.call.LCallToChatController,
    compact: Boolean = false,
) {
    // compact 用于 1v1 小悬浮窗：窗口仅 120x214dp，需按比例缩小头像/图标/字号。
    val avatarSize = if (compact) 48.dp else 96.dp
    val spacerHeight = if (compact) 4.dp else 8.dp
    val iconSize = if (compact) 10.dp else 14.dp
    val iconPadding = if (compact) 1.dp else 2.dp
    val nameFontSize = if (compact) 11.sp else TextUnit.Unspecified
    val nameMaxChars = if (compact) 8 else PARTICIPANT_NAME_MAX_LENGTH

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        userDisplayInfo.avatarData?.let { avatarData ->
            key(avatarData) {
                AndroidView(
                    factory = { ctx ->
                        when (avatarData) {
                            is AvatarData.FromContactor ->
                                callToChatController.getAvatarByContactor(ctx, avatarData.contactor)
                            is AvatarData.FromNameOrUid ->
                                callToChatController.createAvatarByNameOrUid(
                                    ctx,
                                    avatarData.name,
                                    avatarData.userId
                                )
                        }
                    },
                    modifier = Modifier
                        .height(avatarSize)
                        .width(avatarSize)
                )
            }
        }

        Spacer(modifier = Modifier.height(spacerHeight))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAudioStatus && imageLoader != null) {
                val painter = when {
                    audioMuted -> painterResource(id = com.difft.android.call.R.drawable.microphone_off)
                    !isSpeaking -> painterResource(id = com.difft.android.call.R.drawable.ic_silent)
                    else -> rememberAsyncImagePainter(model = com.difft.android.call.R.drawable.speaking, imageLoader = imageLoader)
                }

                val tintColor = when {
                    audioMuted -> Color.Unspecified
                    else -> Color(0xFF82C1FC)
                }

                Icon(
                    painter = painter,
                    contentDescription = "",
                    modifier = Modifier
                        .padding(iconPadding)
                        .size(iconSize),
                    tint = tintColor
                )
            }

            val username = rememberParticipantDisplayName(userId, userDisplayInfo.name)
            Text(
                text = StringUtil.truncateWithEllipsis(username, nameMaxChars),
                color = Color.White,
                fontSize = nameFontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}