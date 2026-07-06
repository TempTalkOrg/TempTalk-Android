package com.difft.android.call.ui.screenshare

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.call.LCallManager
import com.difft.android.call.data.AvatarData
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.LCallViewModel
import dagger.hilt.android.EntryPointAccessors
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.difft.android.base.R as BaseR

internal val BANNER_WIDTH = 160.dp
internal val BANNER_MARGIN_END = 15.dp
internal val BANNER_MARGIN_TOP = 17.dp
internal val BANNER_CORNER = 8.dp
internal val BANNER_PADDING = 8.dp
internal val ICON_SIZE = 16.dp
internal val AVATAR_SIZE = 16.dp
internal const val MIC_QUEUE_NAME_MAX_LENGTH = 12
internal const val MIC_QUEUE_MAX_COUNT = 3
internal const val MIC_STATE_POLL_MS = 1_000L
internal const val AVATAR_LETTER_TEXT_SIZE_DP = 8f

internal data class WaitingSpeakerDisplay(
    val sid: String,
    val displayName: String,
    val avatarModel: Any?,
)

@Composable
fun ScreenShareSpeakerBanner(
    viewModel: LCallViewModel,
    modifier: Modifier = Modifier,
) {
    val isInPipMode by viewModel.callUiController.isInPipMode
        .collectAsStateWithLifecycle(false)
    val speakerUiState by viewModel.screenShareFloatingSpeaker.uiState
        .collectAsStateWithLifecycle()

    var waitingSpeakers by remember { mutableStateOf(emptyList<Participant>()) }
    var hasMultiSpeakerOccurred by remember { mutableStateOf(false) }
    var userChoice by remember { mutableStateOf<Boolean?>(null) }
    var dragDeltaX by remember { mutableFloatStateOf(0f) }
    var dragDeltaY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val ticker = flow {
            while (true) {
                emit(Unit)
                delay(MIC_STATE_POLL_MS)
            }
        }
        combine(
            viewModel.participants,
            viewModel.screenShareFloatingSpeaker.uiState,
            ticker,
        ) { participantList, speaker, _ ->
            val speakerSid = speaker.videoParticipant.sid.value
            participantList
                .filter {
                    it.isMicrophoneEnabled &&
                        it.sid.value != speakerSid &&
                        it !is LocalParticipant
                }
                .take(MIC_QUEUE_MAX_COUNT)
        }.collect { waitingSpeakers = it }
    }

    val context = LocalContext.current
    val entryPoint = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(
            ApplicationHelper.instance,
        )
    }
    val contactorCacheManager = entryPoint.contactorCacheManager
    val callToChatController = entryPoint.callToChatController
    val displayInfoMap = remember { mutableStateMapOf<String, CallUserDisplayInfo>() }
    val avatarViewMap = remember { mutableStateMapOf<String, Any?>() }

    LaunchedEffect(waitingSpeakers.map { it.sid.value }) {
        waitingSpeakers.forEach { participant ->
            val sid = participant.sid.value
            val uid = participant.identity?.value ?: return@forEach
            if (!displayInfoMap.containsKey(sid)) {
                launch {
                    val info = try {
                        contactorCacheManager.getParticipantDisplayInfo(uid)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.e { "[ScreenShareSpeakerBanner] getParticipantDisplayInfo failed uid=$uid: ${e.stackTraceToString()}" }
                        CallUserDisplayInfo(uid, null, null)
                    }
                    displayInfoMap[sid] = info
                    val avatarView = withContext(Dispatchers.Main) {
                        when (val data = info.avatarData) {
                            is AvatarData.FromContactor ->
                                callToChatController.getAvatarByContactor(context, data.contactor)
                            is AvatarData.FromNameOrUid ->
                                callToChatController.createAvatarByNameOrUid(context, data.name, data.userId)
                            null -> null
                        }
                    }
                    if (waitingSpeakers.any { it.sid.value == sid }) {
                        avatarViewMap[sid] = avatarView
                    }
                }
            }
        }
        val validKeys = waitingSpeakers.map { it.sid.value }.toSet()
        displayInfoMap.keys.removeAll { it !in validKeys }
        avatarViewMap.keys.removeAll { it !in validKeys }
    }

    val hasWaiting = waitingSpeakers.isNotEmpty()

    if (!hasWaiting) {
        hasMultiSpeakerOccurred = false
        userChoice = null
    } else if (!hasMultiSpeakerOccurred) {
        hasMultiSpeakerOccurred = true
        userChoice = true
    }

    val expanded = resolveExpanded(hasWaiting, userChoice)

    val onBannerClick = {
        if (hasWaiting) {
            userChoice = !(userChoice ?: true)
        }
    }

    if (isInPipMode) return

    val waitingDisplays = waitingSpeakers.map { p ->
        val info = displayInfoMap[p.sid.value]
        WaitingSpeakerDisplay(
            sid = p.sid.value,
            displayName = info?.name ?: p.identity?.value ?: p.sid.value,
            avatarModel = avatarViewMap[p.sid.value],
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val parentMaxWPx = constraints.maxWidth.toFloat()
        val parentMaxHPx = constraints.maxHeight.toFloat()
        val marginEndPx = with(density) { BANNER_MARGIN_END.toPx() }
        val marginTopPx = with(density) { BANNER_MARGIN_TOP.toPx() }
        val bannerWidthPx = with(density) { BANNER_WIDTH.toPx() }

        BannerContainer(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    IntOffset(
                        (-marginEndPx + dragDeltaX).roundToInt(),
                        (marginTopPx + dragDeltaY).roundToInt(),
                    )
                },
            onClick = onBannerClick,
            dragDeltaX = dragDeltaX,
            dragDeltaY = dragDeltaY,
            parentMaxWPx = parentMaxWPx,
            parentMaxHPx = parentMaxHPx,
            marginEndPx = marginEndPx,
            marginTopPx = marginTopPx,
            bannerWidthPx = bannerWidthPx,
            onDragDeltaChange = { newDX, newDY ->
                dragDeltaX = newDX
                dragDeltaY = newDY
            },
        ) {
            ScreenShareSpeakerBannerContent(
                speakerUiState = speakerUiState,
                waitingSpeakers = waitingDisplays,
                expanded = expanded,
            )
        }
    }
}

@Composable
internal fun ScreenShareSpeakerBannerContent(
    speakerUiState: ScreenShareFloatingSpeakerUiState,
    waitingSpeakers: List<WaitingSpeakerDisplay>,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(BANNER_CORNER)
    val queueVisibleState = remember { MutableTransitionState(expanded) }
    queueVisibleState.targetState = expanded
    val queueFullyCollapsed = !queueVisibleState.targetState && queueVisibleState.isIdle
    Column(
        modifier = modifier
            .width(BANNER_WIDTH)
            .shadow(
                elevation = 6.dp,
                spotColor = Color(0x14000000),
                ambientColor = Color(0x14000000),
                shape = shape,
            )
            .shadow(
                elevation = 14.dp,
                spotColor = Color(0x14000000),
                ambientColor = Color(0x14000000),
                shape = shape,
            )
            .clip(shape)
            .background(DifftTheme.colors.backgroundSecondary)
            .padding(BANNER_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BannerRow(
            speakerUiState = speakerUiState,
            showInlineAvatars = waitingSpeakers.isNotEmpty() && queueFullyCollapsed,
            waitingSpeakers = waitingSpeakers,
        )
        AnimatedVisibility(
            visibleState = queueVisibleState,
            enter = fadeIn(
                androidx.compose.animation.core.tween(200),
            ) + expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(200),
            ),
            exit = fadeOut(
                androidx.compose.animation.core.tween(150),
            ) + shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = androidx.compose.animation.core.tween(150),
            ),
        ) {
            MicQueueList(waitingSpeakers)
        }
    }
}

internal fun resolveExpanded(hasWaiting: Boolean, userChoice: Boolean?): Boolean {
    if (!hasWaiting) return false
    return userChoice ?: true
}
