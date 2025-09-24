package com.difft.android.call

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.call.CallRole
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.CallStatus
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.ScreenShareSpeakerView
import com.difft.android.call.ui.ScreenSharingView
import com.difft.android.call.ui.ShowParticipantsListView
import com.difft.android.call.util.StringUtil
import com.difft.android.call.util.ViewUtil
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow


@Composable
fun SingleParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean = false,
    autoHideTimeout: Long,
    callConfig: CallConfig,
    conversationId: String?,
    callerId: String,
    callRole: CallRole?,
    handleInviteUsersClick: () -> Unit = {}
){
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isUserSharingScreen by viewModel.isParticipantSharedScreen.collectAsState()
    val isShowUsersEnabled by viewModel.showUsersEnabled.collectAsState()
    val callStatus by viewModel.callStatus.collectAsState()

    val videoTrackMap by room.localParticipant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find the most appropriate video stream to show
    // Prioritize screen share, then camera, then any video stream.
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }
        ?: videoPubs.firstOrNull()

    var videoMuted by remember { mutableStateOf(true) }

    // monitor video muted state
    LaunchedEffect(videoPub) {
        if (videoPub != null) {
            videoPub::muted.flow.collect { muted -> videoMuted = muted }
        }
    }

    when (callStatus) {

        CallStatus.CONNECTED, CallStatus.RECONNECTED -> {
            val remoteParticipant = participants.filterIsInstance<RemoteParticipant>().firstOrNull()
            val peerId = remoteParticipant?.identity?.value
            if(remoteParticipant!=null && peerId!=null) {
                when {
                    isUserSharingScreen -> {
                        // 显示屏幕分享画面
                        ScreenSharingView(room = room, participant = remoteParticipant)
                        // 显示屏幕分享时speaker悬浮窗
                        ScreenShareSpeakerView(viewModel = viewModel, shareScreenUser = remoteParticipant, callConfig = callConfig)
                    }
                    else -> {
                        // 显示对方参会人画面
                        SingleParticipantItem(
                            room = room,
                            participant = remoteParticipant,
                            uid = peerId,
                        )
                    }
                }
            }else {
                conversationId?.let { id ->
                    CalleeParticipantItem(userId = id)
                }
            }
        }
        else -> {
            if (callRole == CallRole.CALLER ) {
                conversationId?.let {
                    val statusTip = if (callStatus == CallStatus.CALLING) ApplicationHelper.instance.getString(R.string.call_status_calling)
                    else null
                    CalleeParticipantItem(userId = it, statusTip = statusTip)
                }
            }
        }
    }


    if((callStatus == CallStatus.CONNECTED || callStatus == CallStatus.RECONNECTED) && !videoMuted && !isUserSharingScreen) {
        // 展示自己的悬浮小窗口
        OneVOneSelfVideoView(viewModel, room = room)
    }

    // 显示弹幕
    BarrageMessageView(viewModel, config = BarrageMessageConfig(true, callConfig.chatPresets, displayDurationMillis = autoHideTimeout), { message, topic ->
        viewModel.sendBarrageData(message, topic)
    })

    if(isUserSharingScreen && isShowUsersEnabled) {
        // 显示参与者小列表
        ShowParticipantsListView(viewModel, participants, muteOtherEnabled, handleInviteUsersClick)
    }

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

    val isInPipMode by viewModel.isInPipMode.collectAsState(false)

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
                    .background(colorResource(id = com.difft.android.base.R.color.bg2_night))
            ) {
                LocalParticipantVideoView(
                    room = room,
                    participant = room.localParticipant,
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
){
    ConstraintLayout(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clip(shape = RoundedCornerShape(8.dp))
    ){

        val (videoItem) = createRefs()

        VideoItemTrackSelector(
            room = room,
            participant = participant,
            sourceType = Track.Source.CAMERA,
            modifier = Modifier.constrainAs(videoItem) {
                top.linkTo(parent.top)
                bottom.linkTo(parent.bottom)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                width = Dimension.fillToConstraints
                height = Dimension.fillToConstraints
            },
            viewType = ViewType.Texture,
            draggable = false
        )

    }
}


@Composable
fun SingleParticipantItem(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    uid: String,
){
    val identity by participant::identity.flow.collectAsState()
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    val imageLoader = LocalImageLoaderProvider.localImageLoader()

    val contactsUpdate by LCallManager.getContactsUpdateListener().map { Pair(System.currentTimeMillis(), it) }.asFlow().collectAsState(Pair(0L, emptyList()))

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs = audioTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    val audioPub = audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE }

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find the camera video stream to show
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }

    var videoMuted by remember { mutableStateOf(true) }

    var audioMuted by remember { mutableStateOf(true) }

    suspend fun updateNameAndAvatar(userId: String) {
        userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, userId)
    }

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        if (audioPub != null) {
            audioPub::muted.flow.collect { muted -> audioMuted = muted }
        }
    }

    // monitor video muted state
    LaunchedEffect(videoPub) {
        if (videoPub != null) {
            videoPub::muted.flow.collect { muted -> videoMuted = muted }
        } else {
            videoMuted = true
        }
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            updateNameAndAvatar(uid)
        }
    }

    LaunchedEffect(contactsUpdate) {
        if(contactsUpdate.second.contains(LCallManager.getUidByIdentity(uid))){
            coroutineScope.launch {
                updateNameAndAvatar(uid)
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize()
            .clip(shape = RoundedCornerShape(8.dp))
            .background(colorResource(id = com.difft.android.base.R.color.bg1_night)),
        contentAlignment = Alignment.Center )
    {
        if (!videoMuted) {
            VideoItemTrackSelector(
                room = room,
                participant = participant,
                // Specifies this view should display camera content
                sourceType = Track.Source.CAMERA,
                scaleType = if (LCallManager.isPersonalMobileDevice(identity?.value)) ScaleType.Fill else ScaleType.FitInside,
                viewType = ViewType.Surface,
                draggable = !LCallManager.isPersonalMobileDevice(identity?.value),
            )
        }else {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                userDisplayInfo.avatar?.let { avatarImage ->
                    AndroidView(
                        factory = { avatarImage },
                        modifier = Modifier
                            .height(96.dp)
                            .width(96.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ){
                    // 使用when表达式简化条件判断
                    val painter = when {
                        audioMuted -> painterResource(id = R.drawable.microphone_off)
                        !isSpeaking -> painterResource(id = R.drawable.ic_silent)
                        else -> rememberAsyncImagePainter(model = R.drawable.speaking, imageLoader = imageLoader)
                    }

                    val tintColor = when {
                        audioMuted -> Color.Unspecified // 不设置颜色，或者根据需要设置
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

                    val username = "${userDisplayInfo.name ?: LCallManager.convertToBase58UserName(identity?.value)}"

                    Text(
                        text = StringUtil.getShowUserName(username, 14),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}