package com.difft.android.call.ui

import com.difft.android.base.ui.compose.safeTopInsets
import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import coil3.compose.rememberAsyncImagePainter
import com.difft.android.base.ui.avatar.AvatarLetterSize
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.Palette
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils.getString
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.ui.actionbar.CHROME_RESERVE_ANIMATION_MS
import com.difft.android.call.ui.actionbar.endPadding
import com.difft.android.call.ui.actionbar.rememberCallSideRail
import com.difft.android.call.ui.actionbar.rememberRailBandReserved
import com.difft.android.call.ui.tabletop.LocalCallTabletop
import com.difft.android.call.ui.tabletop.tabletopDockRegion
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import io.livekit.android.util.flow
import kotlin.math.roundToInt



private val TABLETOP_PANEL_TOP_PADDING = 24.dp





@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
fun rememberParticipantsPanelWide(): Boolean {
    val containerSize = LocalWindowInfo.current.containerSize
    val configuration = LocalConfiguration.current
    val widthDp = if (containerSize.width > 0) {
        with(LocalDensity.current) { containerSize.width.toDp() }
    } else {
        configuration.screenWidthDp.dp
    }
    return if (containerSize.width > 0 && containerSize.height > 0) {
        widthDp >= 600.dp || containerSize.width > containerSize.height
    } else {
        widthDp >= 600.dp || configuration.screenWidthDp > configuration.screenHeightDp
    }
}






fun participantsPanelAvailable(isUserSharingScreen: Boolean, isWideScreen: Boolean): Boolean =
    isUserSharingScreen || isWideScreen

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowParticipantsListView(
    viewModel: LCallViewModel,
    muteOtherEnabled: Boolean = false,
    handleInviteUsersClick: () -> Unit = {}
) {
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val lazyGridState = rememberLazyGridState()
    val joined by viewModel.participants.collectAsState(initial = emptyList())
    val isShowUsersEnabled by viewModel.callUiController.showUsersEnabled.collectAsState()
    
    val participants = rememberParticipantsListOrder(joined, open = isShowUsersEnabled, menuOpen = viewModel.callUiController.participantMenuOpen)
    val isUserSharingScreen by viewModel.callUiController.isShareScreening.collectAsState()
    val speakingEnabled by viewModel.callUiController.speakingEnabled.collectAsState()

    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val contactorCacheManager = entryPoint.contactorCacheManager
    val displayInfoMap by contactorCacheManager.participantDisplayMap.collectAsState()

    val isWideScreen = rememberParticipantsPanelWide()
    
    val statusBarTop = safeTopInsets().asPaddingValues().calculateTopPadding()
    
    val tabletop = LocalCallTabletop.current
    val panelTopPadding = when {
        tabletop != null -> TABLETOP_PANEL_TOP_PADDING
        !isUserSharingScreen && isWideScreen -> statusBarTop + 16.dp
        else -> 24.dp
    }

    
    
    val rail = rememberCallSideRail()
    val railBandReserved = rememberRailBandReserved(viewModel.callUiController, rail)
    val panelEndReserve = animateDpAsState(
        targetValue = if (railBandReserved && rail != null) rail.widthDp.dp else 0.dp,
        animationSpec = tween(CHROME_RESERVE_ANIMATION_MS),
        label = "participantsPanelEndReserve",
    )

    val panelRegion = if (tabletop != null) Modifier.tabletopDockRegion(tabletop) else Modifier.fillMaxSize()

    if (!isInPipMode && isShowUsersEnabled && participantsPanelAvailable(isUserSharingScreen, isWideScreen)) {
        Box(
            modifier = panelRegion.endPadding { panelEndReserve.value },
            contentAlignment = Alignment.TopEnd
        ){
            Box(
                modifier = Modifier
                    .testTag("call_participants_panel")
                    .fillMaxHeight()
                    .width(216.dp)
                    .background(DifftTheme.colors.backgroundTertiary)
                    
                    
                    .pointerInput(Unit) { detectTapGestures {} },
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
                                    .testTag("call_participants_btn_add")
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
                                modifier = Modifier
                                    .testTag("call_participants_title")
                                    .constrainAs(textView) {
                                        centerHorizontallyTo(parent)
                                        centerVerticallyTo(parent)
                                    },
                                text = "${getString(R.string.call_attendees)} (${participants.size})",
                                style = DifftTheme.typography.p3,
                                color = Color.White,
                                maxLines = 1
                            )

                            Surface(
                                onClick = { viewModel.callUiController.setShowUsersEnabled(false) },
                                modifier = Modifier
                                    .testTag("call_participants_btn_close")
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

                                val uid = when (participant) {
                                    is LocalParticipant -> globalServices.myId
                                    else -> participant.identity?.value ?: ""
                                }
                                SmallParticipantViewItem(
                                    participant = participant,
                                    participantIndex = index,
                                    userDisplayInfo = displayInfoMap[uid] ?: CallUserDisplayInfo(null, null, null),
                                    muteOtherEnabled = muteOtherEnabled,
                                    speakingEnabled = speakingEnabled,
                                    onClickMute = { name -> viewModel.toggleMute(participant, name) },
                                    onMenuOpenChanged = viewModel.callUiController::setParticipantMenuOpen,
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
    participantIndex: Int,
    userDisplayInfo: CallUserDisplayInfo,
    muteOtherEnabled: Boolean,
    speakingEnabled: Boolean = true,
    onClickMute: (displayName: String) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit = {},
){
    val isSpeaking by participant::isSpeaking.flow.collectAsState()
    val effectiveIsSpeaking = isSpeaking && speakingEnabled
    val imageLoader = LocalImageLoaderProvider.localImageLoader()
    var muteMenuVisible by remember { mutableStateOf(false) }
    var muteMenuTouch by remember { mutableStateOf(Offset.Zero) }
    val displayName = rememberParticipantDisplayName(participant, userDisplayInfo.name)
    val avatarSize = DifftTheme.spacing.avatarSmall

    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance)
    }
    val callToChatController = entryPoint.callToChatController

    val audioTrackMap by participant::audioTrackPublications.flow.collectAsState(initial = emptyList())
    val audioPubs by remember { derivedStateOf { audioTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val audioPub by remember { derivedStateOf { audioPubs.firstOrNull { pub -> pub.source == Track.Source.MICROPHONE } } }

    var audioMuted by remember { mutableStateOf(true) }

    val videoTrackMap by participant::videoTrackPublications.flow.collectAsState(initial = emptyList())
    val videoPubs by remember { derivedStateOf { videoTrackMap.filter { (pub) -> pub.subscribed }.map { (pub) -> pub } } }
    val screenSharePub by remember { derivedStateOf { videoPubs.firstOrNull { pub -> pub.source == Track.Source.SCREEN_SHARE } } }
    val isScreenSharing by remember { derivedStateOf { screenSharePub != null } }

    LaunchedEffect(audioPub) {
        val pub = audioPub ?: return@LaunchedEffect
        pub::muted.flow.collect { muted -> audioMuted = muted }
    }

    Box(
        modifier = Modifier
            .testTag("call_participants_item_$participantIndex")
            .fillMaxWidth()
            .height(PANEL_ROW_HEIGHT)
    ){
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(PANEL_ROW_HEIGHT),
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
                        callToChatController.Avatar(
                            data = avatarData,
                            size = avatarSize,
                            modifier = Modifier.constrainAs(avatarView) {
                                start.linkTo(parent.start)
                            },
                            letterTextSizeDp = AvatarLetterSize.forDiameterDp(avatarSize.value.roundToInt()),
                        )
                    }

                    Text(
                        modifier = Modifier
                            .constrainAs(userNameView){
                                start.linkTo(avatarView.end, 5.dp)
                                end.linkTo(if (isScreenSharing) shareStatusView.start else speakStatusView.start, 5.dp)
                                width = Dimension.fillToConstraints
                                centerVerticallyTo(parent)
                            },
                        text = StringUtil.truncateWithEllipsis(displayName, NAME_MEASURE_CAP),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = DifftTheme.typography.p3,
                        color = Color.White,
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

                    val painter = when {
                        audioMuted -> painterResource(id = R.drawable.microphone_off)
                        !effectiveIsSpeaking -> painterResource(id = R.drawable.ic_silent)
                        else -> rememberAsyncImagePainter(model = R.drawable.speaking, imageLoader = imageLoader)
                    }

                    val tintColor = when {
                        audioMuted -> Color.Unspecified
                        else -> Palette.Blue200
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

        
        
        
        
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(DifftTheme.spacing.minTouchTarget)
                .fillMaxHeight()
                .testTag("call_participants_item_mic_$participantIndex")
                .muteMenuTapTarget(
                    participant = participant,
                    muteOtherEnabled = muteOtherEnabled,
                    onTap = { touch ->
                        muteMenuTouch = touch
                        muteMenuVisible = true
                    },
                )
        ) {
            ParticipantMuteMenu(
                visible = muteMenuVisible,
                touchInAnchor = muteMenuTouch,
                targetName = displayName,
                onDismissRequest = { muteMenuVisible = false },
                onMute = {
                    muteMenuVisible = false
                    onClickMute(displayName)
                },
                onOpenChanged = onMenuOpenChanged,
            )
        }
    }
}

private val PANEL_ROW_HEIGHT = 34.dp
