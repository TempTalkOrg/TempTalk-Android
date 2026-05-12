package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.call.CallRole
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.ui.barrage.BarrageMessageView
import com.difft.android.call.ui.screenshare.ScreenSharingView
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import com.difft.android.call.util.ViewUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.collections.listOf


@Composable
fun SingleParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    autoHideTimeout: Long,
    callConfig: CallConfig,
    conversationId: String?,
    callRole: CallRole?,
    isDualPane: Boolean = false,
){
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val callStatus by viewModel.callStatus.collectAsState()
    val isConnected = callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED
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

    when {
        isConnected && isUserSharingScreen && remoteParticipant != null -> {
            ScreenSharingView(room = room, participant = remoteParticipant, reconnectCount = reconnectCount)
            LaunchedEffect(remoteParticipant.sid) {
                viewModel.updateScreenShareFallback(remoteParticipant)
            }
        }
        (isConnected || callRole == CallRole.CALLER) && participantUid != null -> {
            SingleParticipantItem(
                room = room,
                participant = remoteParticipant,
                uid = participantUid,
                speakingEnabled = speakingEnabled,
                reconnectCount = reconnectCount,
            )
        }
    }


    if(isConnected && !videoMuted && !isUserSharingScreen) {
        OneVOneSelfVideoView(viewModel, room = room)
    }

    val barrageConfig = remember(callConfig, autoHideTimeout) {
        BarrageMessageConfig(
            isOneVOneCall = true,
            barrageTexts = callConfig.chatPresets ?: emptyList(),
            displayDurationMillis = autoHideTimeout,
            baseSpeed = callConfig.bubbleMessage?.baseSpeed ?: 4600L,
            deltaSpeed = callConfig.bubbleMessage?.deltaSpeed ?: 400L,
            columns = callConfig.bubbleMessage?.columns ?: listOf(10, 40, 70),
            emojiPresets = callConfig.bubbleMessage?.emojiPresets ?: LCallUiConstants.DEFAULT_BUBBLE_EMOJIS,
            textPresets = callConfig.bubbleMessage?.textPresets ?: LCallUiConstants.DEFAULT_BUBBLE_TEXTS,
            textMaxLength = callConfig.chatMessage?.maxLength ?: 30,
        )
    }

    BarrageMessageView(
        viewModel,
        config = barrageConfig,
        isDualPane = isDualPane,
        isShareScreening = isUserSharingScreen,
        sendBarrageMessage = { message, type, _ ->
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
}


@Composable
fun OneVOneSelfVideoView(
    viewModel: LCallViewModel,
    modifier: Modifier = Modifier,
    room: Room,
) {
    val videoViewWith = 120.dp
    val videoViewHeight = 214.dp

    val configuration = LocalConfiguration.current // 获取当前配置
    val screenWidth = configuration.screenWidthDp.dp.value // 获取屏幕宽度（dp）
    val screenHeight = configuration.screenHeightDp.dp.value // 获取屏幕高度（dp）

    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val reconnectCount by viewModel.callUiController.reconnectCount.collectAsState()

    var dragViewOffsetX: Float by remember { mutableFloatStateOf(ViewUtil.dpToPx(screenWidth.toInt() - videoViewWith.value.toInt() - 12).toFloat()) }
    var dragViewOffsetY: Float by remember { mutableFloatStateOf(ViewUtil.dpToPx(screenHeight.toInt() - videoViewHeight.value.toInt() - 40).toFloat()) }

    if(!isInPipMode){
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
                .offset {
                    IntOffset(
                        dragViewOffsetX.toInt(),
                        dragViewOffsetY.toInt()
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newOffsetX =
                            dragViewOffsetX + dragAmount.x
                        val newOffsetY =
                            dragViewOffsetY + dragAmount.y
                        dragViewOffsetX =
                            newOffsetX.coerceIn(
                                0f,
                                ViewUtil.dpToPx(screenWidth.toInt() - videoViewWith.value.toInt())
                                    .toFloat()
                            )
                        dragViewOffsetY =
                            newOffsetY.coerceIn(
                                0f,
                                ViewUtil.dpToPx(screenHeight.toInt() - videoViewHeight.value.toInt())
                                    .toFloat()
                            )
                    }
                }
        ){
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.Center)
                    .size(videoViewWith, videoViewHeight)
                    .clip(shape = RoundedCornerShape(8.dp))
                    .background(colorResource(id = R.color.bg2_night))
            ) {
                LocalParticipantVideoView(
                    room = room,
                    participant = room.localParticipant,
                    reconnectCount = reconnectCount,
                )
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
            callToChatController = callToChatController
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
            viewType = ViewType.Surface,
            draggable = !IdUtil.isPersonalMobileDevice(identity?.value),
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
                callToChatController = callToChatController
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
) {
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
                        .height(96.dp)
                        .width(96.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                        .padding(2.dp)
                        .size(14.dp),
                    tint = tintColor
                )
            }

            val username = "${userDisplayInfo.name ?: IdUtil.convertToBase58UserName(userId)}"
            Text(
                text = StringUtil.truncateWithEllipsis(username, 14),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}