package com.difft.android.call.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.LocalImageLoaderProvider
import com.difft.android.call.R
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.MUTE_ACTION_INDEX
import com.difft.android.call.util.StringUtil
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowParticipantsListView(
    viewModel: LCallViewModel,
    participants: List<Participant> = emptyList(),
    muteOtherEnabled: Boolean = false,
    handleInviteUsersClick: () -> Unit = {}
) {
    val handsUpUserInfo by viewModel.handsUpUserInfo.collectAsState(emptyList())
    var raiseHandExpanded by remember { mutableStateOf(false) }
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val lazyGridState = rememberLazyGridState()

    LaunchedEffect(lazyGridState, handsUpUserInfo.size) {
        if(handsUpUserInfo.isNotEmpty()){
            lazyGridState.scrollToItem(0, 0)
        }
    }

    if(!isInPipMode) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopEnd
        ){
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(216.dp)
                    .background(colorResource(id = com.difft.android.base.R.color.bg3_night)),
                contentAlignment = Alignment.TopEnd
            ){
                ConstraintLayout (
                    modifier = Modifier.fillMaxSize().padding(
                        top = 24.dp,
                        bottom = 18.dp,
                    )
                ) {
                    val (topControlView, listView) = createRefs()
                    Row(
                        modifier = Modifier.constrainAs(topControlView){
                            top.linkTo(parent.top, 10.dp)
                            bottom.linkTo(listView.top)
                        }.fillMaxWidth().height(34.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ){
                        ConstraintLayout(
                            modifier = Modifier.fillMaxWidth()
                        ){
                            val (closeView, textView, userPlus) = createRefs()
                            Surface(
                                onClick = { handleInviteUsersClick() },
                                modifier = Modifier
                                    .constrainAs(userPlus) {
                                        start.linkTo(parent.start, margin = 10.dp)
                                        width = Dimension.fillToConstraints
                                        height = Dimension.wrapContent
                                    }
                                    .size(20.dp),
                                color = Color.Transparent
                            ) {
                                val resource = R.drawable.tabler_user_plus
                                Icon(
                                    painterResource(id = resource),
                                    contentDescription = "ADD_USER",
                                    tint = Color.White,
                                )
                            }

                            Text(
                                modifier = Modifier.constrainAs(textView) {
                                    centerHorizontallyTo(parent)
                                    centerVerticallyTo(parent)
                                },
                                text = "${getString(R.string.call_attendees)} (${participants.size})",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                color = colorResource(id = com.difft.android.base.R.color.t_white),
                                maxLines = 1
                            )

                            Surface(
                                onClick = { viewModel.callUiController.setShowUsersEnabled(false) },
                                modifier = Modifier
                                    .constrainAs(closeView) {
                                        end.linkTo(parent.end, margin = 10.dp)
                                        width = Dimension.fillToConstraints
                                        height = Dimension.wrapContent
                                    }
                                    .size(20.dp),
                                color = Color.Transparent
                            ) {
                                val resource = R.drawable.close
                                Icon(
                                    painterResource(id = resource),
                                    contentDescription = "Close_View",
                                    tint = Color.White,
                                )
                            }
                        }
                    }

                    CompositionLocalProvider(
                        LocalOverscrollConfiguration provides null
                    ){
                        LazyVerticalGrid(
                            modifier = Modifier.constrainAs(listView){
                                top.linkTo(topControlView.bottom)
                                bottom.linkTo(parent.bottom, 10.dp)
                            }.padding(10.dp).fillMaxHeight(),
                            columns = GridCells.Fixed(1),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            state = lazyGridState
                        ){
                            items(
                                count = participants.size + (if (handsUpUserInfo.isNotEmpty()) 1 else 0),
                                key = { index ->
                                    if (index == 0 && handsUpUserInfo.isNotEmpty()) "header" else participants[index - (if (handsUpUserInfo.isNotEmpty()) 1 else 0)].sid.value
                                }
                            )
                            { index ->
                                if (index == 0 && handsUpUserInfo.isNotEmpty()) {
                                    Column(
                                        modifier = Modifier
                                            .wrapContentHeight()
                                            .width(184.dp)
                                            .background(color = colorResource(id = com.difft.android.base.R.color.gray_600), shape = RoundedCornerShape(size = 8.dp))
                                            .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Top),
                                        horizontalAlignment = Alignment.Start,
                                    ){
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(20.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // Child views.
                                            Image(
                                                modifier = Modifier
                                                    .padding(0.66667.dp)
                                                    .width(16.dp)
                                                    .height(16.dp),
                                                painter = painterResource(id = R.drawable.call_tabler_hand_stop),
                                                contentDescription = "image description",
                                                contentScale = ContentScale.None
                                            )

                                            Text(
                                                text = "Raise hand (${handsUpUserInfo.size}) ",
                                                // SF/H5
                                                style = TextStyle(
                                                    fontSize = 14.sp,
                                                    lineHeight = 20.sp,
                                                    fontFamily = SfProFont,
                                                    fontWeight = FontWeight(510),
                                                    color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                                                )
                                            )
                                        }
                                        handsUpUserInfo.forEachIndexed { index, handUpUserInfo ->
                                            if(index > 4 && !raiseHandExpanded){
                                                return@forEachIndexed
                                            }
                                            ShowHandsUpBottomListView(viewModel, handUpUserInfo, viewHeight = 32.dp, fontSize = 14, lineHeight = 20)
                                        }

                                        if(handsUpUserInfo.size > 5) {
                                            val expandedIcon = if(raiseHandExpanded){
                                                R.drawable.call_handup_chevron_up
                                            } else {
                                                R.drawable.call_handup_chevron_down
                                            }
                                            Column(
                                                modifier = Modifier.fillMaxWidth().height(16.dp),
                                                verticalArrangement = Arrangement.Center,
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ){
                                                Image(
                                                    modifier = Modifier
                                                        .pointerInput(Unit) {
                                                            detectTapGestures (
                                                                onTap = {
                                                                    raiseHandExpanded = !raiseHandExpanded
                                                                }
                                                            )
                                                        }
                                                        .padding(0.66667.dp)
                                                        .width(16.dp)
                                                        .height(16.dp)
                                                    ,
                                                    painter = painterResource(expandedIcon),
                                                    contentDescription = "collapse expand icon",
                                                    contentScale = ContentScale.None
                                                )
                                            }
                                        }
                                    }
                                }else {
                                    val participant = participants[index - (if (handsUpUserInfo.isNotEmpty()) 1 else 0)]

                                    SmallParticipantViewItem(
                                        participant = participant,
                                        muteOtherEnabled = muteOtherEnabled,
                                        onClickMute = {
                                            L.d { "Mute toggled for participant ${participant.identity?.value}" }
                                            viewModel.toggleMute(participant)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }


}


@Composable
fun SmallParticipantViewItem(
    modifier: Modifier = Modifier,
    participant: Participant,
    muteOtherEnabled: Boolean,
    onClickMute: () -> Unit
){
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    val imageLoader = LocalImageLoaderProvider.localImageLoader()
    var expanded by remember { mutableStateOf(false) }

    val contactsUpdate by LCallManager.getContactsUpdateListener().map {
        Pair(System.currentTimeMillis(), it)
    }.asFlow().collectAsState(Pair(0L, emptyList()))

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    val participantId = participant.identity?.value

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs = audioTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }
    val audioPub = audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE }

    var audioMuted by remember { mutableStateOf(true) }

    var isScreenSharing by remember { mutableStateOf(false) }

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs = videoTrackMap.filter { (pub) -> pub.subscribed }
        .map { (pub) -> pub }
    val screenSharePub = videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            participantId?.let { id ->
                userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, id)
            }
        }
    }

    // monitor screen share state
    LaunchedEffect(screenSharePub) {
        if (screenSharePub != null) {
            isScreenSharing = true
        }else {
            isScreenSharing = false
        }
    }

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        if (audioPub != null) {
            audioPub::muted.flow.collect { muted -> audioMuted = muted }
        }
    }

    LaunchedEffect(contactsUpdate) {
        if(contactsUpdate.second.contains(LCallManager.getUidByIdentity(participantId))){
            coroutineScope.launch {
                participantId?.let {
                    userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, participantId)
                }
            }
        }
    }

    fun onClickItem(index: Int, setExpanded: (Boolean) -> Unit, onClickMute: () -> Unit) {
        setExpanded(false)
        if (index == MUTE_ACTION_INDEX) onClickMute()
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(Unit) {
                detectTapGestures (
                    onTap = {
                        if(participant.isMicrophoneEnabled && muteOtherEnabled){
                            expanded = true
                        }
                    }
                )
            }
    ){
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ){
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 7.dp, end = 7.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ){
                ConstraintLayout(
                    modifier = Modifier.fillMaxWidth()
                ){
                    val (avatarView, userNameView, shareStatusView, speakStatusView) = createRefs()
                    userDisplayInfo.avatar?.let { avatarImage ->
                        AndroidView(
                            factory = { avatarImage },
                            modifier = Modifier
                                .constrainAs(avatarView){
                                    start.linkTo(parent.start)
                                }
                                .height(32.dp)
                                .width(32.dp)
                        )
                    }

                    Text(
                        modifier = Modifier
                            .constrainAs(userNameView){
                                start.linkTo(avatarView.end, 5.dp)
                                centerVerticallyTo(parent)
                            },
                        text = StringUtil.getShowUserName(userDisplayInfo.name ?: "", 14),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = SfProFont,
                            fontWeight = FontWeight(400),
                            color = Color.White,
                        )
                    )

                    if(isScreenSharing){
                        val shareIconPainter = painterResource(id = R.drawable.tabler_aspect_ratio)
                        Icon(
                            painter = shareIconPainter,
                            contentDescription = "",
                            modifier = Modifier
                                .constrainAs(shareStatusView){
                                    end.linkTo(speakStatusView.start, 5.dp)
                                    centerVerticallyTo(parent)
                                }
                                .padding(2.dp)
                                .size(16.dp),
                        )
                    }

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
                            .constrainAs(speakStatusView){
                                end.linkTo(parent.end)
                                centerVerticallyTo(parent)
                            }
                            .padding(2.dp)
                            .size(14.dp),
                        tint = tintColor
                    )
                }
            }
        }

        ShowItemOnClickView(listOf("Mute"), expanded, setExpanded = { value -> expanded = value} ,
            onClickItem = {
                    index ->
                onClickItem(index,
                    setExpanded = {value -> expanded = value},
                    onClickMute= { onClickMute()}
                )
            }
        )
    }
}