package com.difft.android.call.ui.barrage

import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.ui.tapInterceptor
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.util.IdUtil
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.data.BubbleMessageType
import com.difft.android.call.ui.screenshare.getActivity
import com.difft.android.call.data.RTM_MESSAGE_TYPE_BUBBLE
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.TextBubbleMessage
import com.difft.android.call.util.StringUtil
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random


@Composable
fun BarrageMessageView(
    viewModel: LCallViewModel,
    config: BarrageMessageConfig,
    isDualPane: Boolean = false,
    isShareScreening: Boolean = false,
    sendBarrageMessage: (String, Int, String) -> Unit,
) {
    val callUiController = viewModel.callUiController
    val coroutineScope = rememberCoroutineScope()
    val visibleMessages = remember { mutableStateListOf<BarrageMessage>() }
    val removalJobs = remember { mutableStateMapOf<Long, Job>() }
    val showSimpleBarrageEnabled by callUiController.showSimpleBarrageEnabled.collectAsState(false)

    val isInPipMode by callUiController.isInPipMode.collectAsState(false)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var showInputOverlay by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val context = LocalContext.current

    val activity = context.getActivity() ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    var foldingState by remember { mutableStateOf<FoldingFeature.State?>(null) }

    LaunchedEffect(activity, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { layoutInfo ->
                    val foldingFeature = layoutInfo.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .firstOrNull()
                    foldingState = foldingFeature?.state
                }
        }
    }

    val windowWidthClass = WindowSizeClassUtil.computeWindowWidthSizeClass(activity)
    val isFoldableOpen =
        foldingState == FoldingFeature.State.FLAT &&
                windowWidthClass != WindowSizeClassUtil.WindowWidthSizeClass.COMPACT
    val isFoldableClosed =
        foldingState == null &&
                windowWidthClass == WindowSizeClassUtil.WindowWidthSizeClass.COMPACT

    val bottomPadding = when {
        isLandscape -> 24.dp
        isFoldableOpen -> 36.dp
        isFoldableClosed -> 88.dp
        else -> 88.dp
    }

    val maxInputChars = config.textMaxLength

    fun limitInputLength(value: String): String {
        val count = value.codePointCount(0, value.length)
        if (count <= maxInputChars) return value
        val endIndex = value.offsetByCodePoints(0, maxInputChars)
        return value.take(endIndex)
    }

    val contactorCacheManager = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }
    val mySelfId = remember { globalServices.myId }
    var currentUserName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        currentUserName = contactorCacheManager.getDisplayNameById(mySelfId)
            ?: IdUtil.convertToBase58UserName(mySelfId)
                    ?: mySelfId
    }

    val currentConfig by rememberUpdatedState(config)

    fun onClickItem(item: String, type: BubbleMessageType) {
        callUiController.setShowSimpleBarrageEnabled(false)
        sendBarrageMessage(item, RTM_MESSAGE_TYPE_BUBBLE, RTM_MESSAGE_TOPIC_CHAT)

        when(type) {
            BubbleMessageType.EMOJI -> {
                if (currentConfig.emojiPresets.contains(item) && currentUserName != null) {
                    val startOffsetPercent = currentConfig.columns.random()
                    val durationMillis = (Random.nextDouble() * currentConfig.deltaSpeed + currentConfig.baseSpeed).toLong()
                    callUiController.setEmojiBubbleMessage(
                        EmojiBubbleMessage(
                            emoji = item,
                            userName = currentUserName!!,
                            startOffsetPercent = startOffsetPercent,
                            durationMillis = durationMillis
                        )
                    )
                }
            }
            BubbleMessageType.TEXT -> {
                if (currentConfig.textPresets.contains(item) && currentUserName != null) {
                    val startOffsetPercent = currentConfig.columns.random()
                    val durationMillis = (Random.nextDouble() * currentConfig.deltaSpeed + currentConfig.baseSpeed).toLong()
                    val (text, emoji) = StringUtil.splitTextAndTrailingEmoji(item)
                    callUiController.setTextBubbleMessage(
                        TextBubbleMessage(
                            emoji = emoji,
                            text = text,
                            userName = currentUserName!!,
                            startOffsetPercent = startOffsetPercent,
                            durationMillis = durationMillis
                        )
                    )
                }
            }
        }
    }

    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            visibleMessages.clear()
            removalJobs.values.forEach { it.cancel() }
            removalJobs.clear()
        }
    }

    fun removeVisibleMessage(messageId: Long) {
        visibleMessages.removeAll { it.id == messageId }
        removalJobs.remove(messageId)?.cancel()
    }

    fun scheduleRemoval(message: BarrageMessage) {
        removalJobs.remove(message.id)?.cancel()
        removalJobs[message.id] = coroutineScope.launch {
            delay(currentConfig.displayDurationMillis)
            removeVisibleMessage(message.id)
        }
    }

    fun enqueueBarrageMessage(message: BarrageMessage) {
        if (currentConfig.showLimitCount <= 0) return
        while (visibleMessages.size >= currentConfig.showLimitCount) {
            val oldest = visibleMessages.firstOrNull() ?: break
            removeVisibleMessage(oldest.id)
        }
        visibleMessages.add(message)
        scheduleRemoval(message)
    }

    LaunchedEffect(Unit) {
        callUiController.barrageMessage.collect { message ->
            if (!callUiController.isInPipMode.value) {
                enqueueBarrageMessage(message)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding, start = 16.dp, end = 16.dp)
                .align(Alignment.BottomStart),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Bottom
        ){
            if(!isInPipMode) {
                BarrageDisplay(
                    visibleMessages = visibleMessages,
                    textMaxLength = config.textMaxLength
                )

                if (visibleMessages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.padding(bottom = if (isDualPane) 48.dp else 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShouldShowBarrageInput(
                        viewModel,
                        config,
                        showSimpleBarrageEnabled,
                        isDualPane = isDualPane,
                        isShareScreening = isShareScreening,
                        setExpanded = { value -> callUiController.setShowSimpleBarrageEnabled(value) },
                        onClickItem = { value, type -> onClickItem(value, type) },
                        onShowInputOverlay = { showInputOverlay = true }
                    )
                }
            }
        }

        if (showInputOverlay) {
            BarrageInputOverlay(
                inputText = inputText,
                onInputTextChange = { inputText = limitInputLength(it) },
                onSubmit = {
                    val message = inputText.trim()
                    if (message.isNotEmpty()) {
                        callUiController.setShowSimpleBarrageEnabled(false)
                        sendBarrageMessage(message, RTM_MESSAGE_TYPE_DEFAULT, RTM_MESSAGE_TOPIC_CHAT)
                    }
                    showInputOverlay = false
                    inputText = ""
                },
                onDismiss = {
                    showInputOverlay = false
                    inputText = ""
                }
            )
        }
    }
}


/**
 * Scrolling barrage message cards displayed at the bottom of the call screen.
 */
@Composable
private fun BarrageDisplay(
    visibleMessages: List<BarrageMessage>,
    textMaxLength: Int
) {
    visibleMessages.forEachIndexed { index, message ->
        key(message.id) {
            BarrageMessageCard(message = message, textMaxLength = textMaxLength)

            if (index < visibleMessages.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun BarrageMessageCard(message: BarrageMessage, textMaxLength: Int) {
    val textPrimary = DifftTheme.colors.textPrimary
    val annotatedString = remember(message.userName, message.message, textMaxLength, textPrimary) {
        buildAnnotatedString {
            withStyle(style = SpanStyle(color = ColorTokens.InfoLight)) {
                append(message.userName)
            }
            withStyle(style = SpanStyle(color = textPrimary)) {
                append(" ${StringUtil.truncateWithEllipsis(message.message, textMaxLength)}")
            }
        }
    }

    Column(
        modifier = Modifier
            .alpha(0.9f)
            .shadow(elevation = 10.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
            .wrapContentWidth()
            .heightIn(min = LCallUiConstants.BARRAGE_MESSAGE_ITEM_HEIGHT.dp)
            .background(color = DifftTheme.colors.bgElevated, shape = RoundedCornerShape(size = 8.dp))
            .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth()
                .wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .heightIn(min = 20.dp),
                text = annotatedString,
                style = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight(400),
                ),
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}


@Composable
internal fun ShouldShowBarrageInput(
    viewModel: LCallViewModel,
    config: BarrageMessageConfig,
    expanded: Boolean,
    isDualPane: Boolean = false,
    isShareScreening: Boolean = false,
    setExpanded: (Boolean) -> Unit,
    onClickItem: (String, BubbleMessageType) -> Unit,
    onShowInputOverlay: () -> Unit
) {
    val bottomEnabledState = viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val alwaysShow = (config.isOneVOneCall && !isLandscape) || (isDualPane && isShareScreening)
    val shouldShow = alwaysShow || bottomEnabledState.value

    val bubbleWidthDp = if (isShareScreening) {
        LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH_SCREEN_SHARE
    } else {
        LCallUiConstants.SIMPLE_BARRAGE_UI_WIDTH
    }

    val everExpandedRef = remember { booleanArrayOf(false) }
    if (expanded) everExpandedRef[0] = true
    val contentComposed = everExpandedRef[0]

    val pickerAlpha = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        pickerAlpha.animateTo(
            if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 80)
        )
    }

    val expandedState by rememberUpdatedState(expanded)

    Box(
        modifier = Modifier
            .alpha(if (shouldShow) 1f else 0f)
            .tapInterceptor(enabled = !shouldShow) {
                viewModel.callUiController.toggleOverlays()
            }
    ) {
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, 0) {
                        if (expandedState) {
                            placeable.placeRelative(0, -(placeable.height + 4.dp.roundToPx()))
                        }
                    }
                }
                .testTag("barrage-outer-box")
                .defaultMinSize(
                    minWidth = bubbleWidthDp.dp,
                    minHeight = LCallUiConstants.SIMPLE_BARRAGE_PICKER_MIN_HEIGHT.dp
                )
        ) {
            if (contentComposed) {
                BubbleBarrageMessage(
                    modifier = Modifier
                        .graphicsLayer { alpha = pickerAlpha.value }
                        .testTag("barrage-picker-content"),
                    config = config,
                    widthDp = bubbleWidthDp,
                    enabled = expanded,
                    onClickItem = onClickItem
                )
            }
        }

        val clickGate = remember { LongArray(1) }
        Row(
            modifier = Modifier
                .alpha(0.9f)
                .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                .wrapContentSize()
                .background(color = DifftTheme.colors.bgElevated, shape = RoundedCornerShape(size = 8.dp))
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                .clickable {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - clickGate[0] >= 100L) {
                        clickGate[0] = now
                        setExpanded(!expanded)
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                modifier = Modifier
                    .width(20.dp)
                    .height(20.dp),
                painter = painterResource(id = com.difft.android.call.R.drawable.tabler_mood_smile),
                contentDescription = "barrage input icon",
                contentScale = ContentScale.Fit,
            )
        }
    }
}

