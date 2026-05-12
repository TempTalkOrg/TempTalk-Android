package com.difft.android.call.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.data.MUTE_ACTION_INDEX
import com.difft.android.call.util.IdUtil
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlinx.coroutines.launch


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowParticipantsListView(
    viewModel: LCallViewModel,
    muteOtherEnabled: Boolean = false,
    handleInviteUsersClick: () -> Unit = {}
) {
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val lazyGridState = rememberLazyGridState()
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val isShowUsersEnabled by viewModel.callUiController.showUsersEnabled.collectAsState()
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()

    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600 ||
        configuration.screenWidthDp > configuration.screenHeightDp
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val panelTopPadding = if (!isUserSharingScreen && isWideScreen) statusBarTop + 16.dp else 24.dp

    if(!isInPipMode && isShowUsersEnabled && (isUserSharingScreen || isWideScreen)) {
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
                        top = panelTopPadding,
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
                                onClick = {
                                    handleInviteUsersClick()
                                    viewModel.callUiController.setShowUsersEnabled(false)
                                },
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
                                count = participants.size,
                                key = { index -> participants[index].sid.value }
                            )
                            { index ->
                                val participant = participants[index]

                                SmallParticipantViewItem(
                                    participant = participant,
                                    muteOtherEnabled = muteOtherEnabled,
                                    speakingEnabled = speakingEnabled,
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


@Composable
fun SmallParticipantViewItem(
    participant: Participant,
    muteOtherEnabled: Boolean,
    speakingEnabled: Boolean = true,
    onClickMute: () -> Unit
){
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    val effectiveIsSpeaking = isSpeaking && speakingEnabled
    val imageLoader = LocalImageLoaderProvider.localImageLoader()
    var expanded by remember { mutableStateOf(false) }

    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val contactorCacheManager = entryPoint.contactorCacheManager
    val callToChatController = entryPoint.callToChatController

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    val participantId = participant.identity?.value

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs by remember { derivedStateOf { audioTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val audioPub by remember { derivedStateOf { audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE } } }

    var audioMuted by remember { mutableStateOf(true) }

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val screenSharePub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE } } }
    val isScreenSharing by remember { derivedStateOf { screenSharePub != null } }

    LaunchedEffect(participantId) {
        participantId?.let { id ->
            userDisplayInfo = contactorCacheManager.getParticipantDisplayInfo(id)
        }
    }

    // monitor audio muted state
    LaunchedEffect(audioPub) {
        val pub = audioPub ?: return@LaunchedEffect
        pub::muted.flow.collect { muted -> audioMuted = muted }
    }

    LaunchedEffect(participantId) {
        LCallManager.getContactsUpdateListener().collect { updatedIds ->
            if (updatedIds.contains(IdUtil.getUidByIdentity(participantId))) {
                launch {
                    participantId?.let {
                        userDisplayInfo = contactorCacheManager.getParticipantDisplayInfo(participantId)
                    }
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
                    userDisplayInfo.avatarData?.let { avatarData ->
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
                        text = StringUtil.truncateWithEllipsis(userDisplayInfo.name ?: "", 14),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
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
                            tint = Color.White
                        )
                    }

                    // 使用when表达式简化条件判断
                    val painter = when {
                        audioMuted -> painterResource(id = R.drawable.microphone_off)
                        !effectiveIsSpeaking -> painterResource(id = R.drawable.ic_silent)
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