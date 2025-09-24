package com.difft.android.call

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.user.CallConfig
import com.difft.android.base.utils.globalServices
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.MUTE_ACTION_INDEX
import com.difft.android.call.ui.ScreenShareSpeakerView
import com.difft.android.call.ui.ScreenSharingView
import com.difft.android.call.ui.ShowHandsUpTipView
import com.difft.android.call.ui.ShowItemOnClickView
import com.difft.android.call.ui.ShowParticipantsListView
import com.difft.android.call.ui.ShowSpeakerStatusView
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MultiParticipantCallPage(
    viewModel: LCallViewModel,
    room: Room,
    muteOtherEnabled: Boolean = false,
    autoHideTimeout: Long,
    callConfig: CallConfig,
    handleInviteUsersClick: () -> Unit = {},
) {
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isShowUsersEnabled by viewModel.showUsersEnabled.collectAsState()
    val isUserSharingScreen by viewModel.isParticipantSharedScreen.collectAsState()
    val whoSharedScreen by viewModel.whoSharedScreen.collectAsState()

    val coroutineScope = rememberCoroutineScope()

    if (!isUserSharingScreen){
        CompositionLocalProvider(
            LocalOverscrollConfiguration provides null
        ){
            // 显示参与者列表
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 44.dp,
                    end = 16.dp,
                    bottom = 4.dp),
            ){
                items(
                    count =  participants.size,
                    key = { index ->  participants[index].sid.value }
                )
                { index ->
                    val participant =  participants[index]
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
    } else {
        whoSharedScreen?.let { sharedParticipant ->
            // 显示屏幕分享画面
            ScreenSharingView( room = room, participant = sharedParticipant)
            // 显示屏幕分享时speaker悬浮窗
            ScreenShareSpeakerView(viewModel = viewModel, shareScreenUser = sharedParticipant, callConfig = callConfig)
        }
    }

    // 显示弹幕
    BarrageMessageView(viewModel, config = BarrageMessageConfig(false, callConfig.chatPresets, displayDurationMillis = autoHideTimeout), { message, topic ->
        viewModel.sendBarrageData(message, topic)
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

    val shouldShowParticipantsList = isUserSharingScreen && isShowUsersEnabled
    if(shouldShowParticipantsList) {
        // 显示参与者小列表
        ShowParticipantsListView(viewModel, participants, muteOtherEnabled, handleInviteUsersClick)
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
    val contactsUpdate by LCallManager.getContactsUpdateListener().map { Pair(System.currentTimeMillis(), it) }.asFlow().collectAsState(Pair(0L, emptyList()))

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }

    // Find the camera video stream to show
    val videoPub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.CAMERA }

    var videoMuted by remember { mutableStateOf(true) }

    val context = LocalContext.current

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    var expanded by remember { mutableStateOf(false) }

    val showControlBarEnabled by viewModel.showControlBarEnabled.collectAsState(true)


    fun onClickItem(index: Int, setExpanded: (Boolean) -> Unit, onClickMute: () -> Unit) {
        setExpanded(false) // 关闭菜单
        when(index){
            MUTE_ACTION_INDEX -> onClickMute()
            else -> {}
        }
    }

    suspend fun updateNameAndAvatar(userId: String) {
        userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, userId)
    }

    fun handleClickScreen() {
        viewModel.showControlBarEnabled.value = !showControlBarEnabled
    }

    // monitor video muted state
    LaunchedEffect(videoPub) {
        if (videoPub != null) {
            videoPub::muted.flow.collect { muted -> videoMuted = muted }
        }else{
            videoMuted = true
        }
    }

    LaunchedEffect(uid) {
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
                .background(color = colorResource(id = com.difft.android.base.R.color.bg1_night))

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
                            modifier = Modifier.background(Color.Transparent),
                            room = room,
                            participant = participant,
                            // Specifies this view should display camera content
                            sourceType = Track.Source.CAMERA,
                            viewType = ViewType.Surface,
                            draggable = false,
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
                    .background(color = colorResource(id = com.difft.android.base.R.color.gray_1000), shape = RoundedCornerShape(size = 4.dp))
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                verticalAlignment = Alignment.Bottom,
            ) {
                ShowSpeakerStatusView(participant, userDisplayInfo.name)
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

