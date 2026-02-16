package com.difft.android.call.ui

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.EmojiBubbleMessage
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.util.IdUtil
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.data.BubbleMessageType
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
fun BarrageMessageView(viewModel: LCallViewModel, config: BarrageMessageConfig, sendBarrageMessage: (String, Int, String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val visibleMessages = remember { mutableStateListOf<BarrageMessage>() }
    val removalJobs = remember { mutableStateMapOf<BarrageMessage, Job>() }
    val showSimpleBarrageEnabled by viewModel.callUiController.showSimpleBarrageEnabled.collectAsState(false)
    val emojiBubbles = remember { mutableStateListOf<EmojiBubbleMessage>() }
    val textBubbles = remember { mutableStateListOf<TextBubbleMessage>() }

    val barrageMessage by viewModel.callUiController.barrageMessage.collectAsState(null)
    val emojiBubbleMessageFromController by viewModel.callUiController.emojiBubbleMessage.collectAsState(null)
    val textBubbleMessageFromController by viewModel.callUiController.textBubbleMessage.collectAsState(null)

    val isShareScreening by viewModel.callUiController.isShareScreening.collectAsState(false)
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Input overlay state
    var showInputOverlay by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var imeWasVisible by remember { mutableStateOf(false) }
    val maxInputChars = config.textMaxLength
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

    fun limitInputLength(value: String): String {
        val count = value.codePointCount(0, value.length)
        if (count <= maxInputChars) return value
        val endIndex = value.offsetByCodePoints(0, maxInputChars)
        return value.substring(0, endIndex)
    }

    fun closeInputOverlay(clearText: Boolean) {
        showInputOverlay = false
        imeWasVisible = false
        if (clearText) {
            inputText = ""
        }
        keyboardController?.hide()
    }

    fun submitInputText() {
        val message = inputText.trim()
        if (message.isNotEmpty()) {
            viewModel.callUiController.setShowSimpleBarrageEnabled(false)
            sendBarrageMessage(message, RTM_MESSAGE_TYPE_DEFAULT, RTM_MESSAGE_TOPIC_CHAT)
        }
        closeInputOverlay(clearText = true)
    }

    // Request focus and show keyboard when input overlay is shown
    LaunchedEffect(showInputOverlay) {
        if (showInputOverlay) {
            imeWasVisible = false
            inputFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Close input overlay when keyboard is dismissed
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    LaunchedEffect(imeVisible, showInputOverlay) {
        if (imeVisible) {
            imeWasVisible = true
        }
        if (showInputOverlay && imeWasVisible && !imeVisible) {
            closeInputOverlay(clearText = true)
        }
    }

    // 获取 ContactorCacheManager 和当前用户名称
    val contactorCacheManager = remember {
        EntryPointAccessors.fromApplication<LCallManager.EntryPoint>(ApplicationHelper.instance).contactorCacheManager
    }
    val mySelfId = remember { globalServices.myId }
    var currentUserName by remember { mutableStateOf<String?>(null) }

    // 获取当前用户名称
    LaunchedEffect(Unit) {
        currentUserName = contactorCacheManager.getDisplayNameById(mySelfId)
            ?: IdUtil.convertToBase58UserName(mySelfId)
                    ?: mySelfId
    }

    // 当点击菜单项时的回调
    fun onClickItem(item: String, type: BubbleMessageType) {
        viewModel.callUiController.setShowSimpleBarrageEnabled(false)
        sendBarrageMessage(item, RTM_MESSAGE_TYPE_BUBBLE, RTM_MESSAGE_TOPIC_CHAT)

        when(type) {
            BubbleMessageType.EMOJI -> {
                // 如果是 emoji，创建气泡消息
                if (config.emojiPresets.contains(item) && currentUserName != null) {
                    val startOffsetPercent = config.columns.random()
                    val durationMillis = (Random.nextDouble() * config.deltaSpeed + config.baseSpeed).toLong()
                    val bubbleMessage = EmojiBubbleMessage(
                        emoji = item,
                        userName = currentUserName!!,
                        startOffsetPercent = startOffsetPercent,
                        durationMillis = durationMillis
                    )
                    emojiBubbles.add(bubbleMessage)
                }
            }
            BubbleMessageType.TEXT -> {
                // 如果是 text，创建气泡消息
                if (config.textPresets.contains(item) && currentUserName != null) {
                    val startOffsetPercent = config.columns.random()
                    val durationMillis = (Random.nextDouble() * config.deltaSpeed + config.baseSpeed).toLong()
                    val (text, emoji) = StringUtil.splitTextAndTrailingEmoji(item)
                    val bubbleMessage = TextBubbleMessage(
                        emoji = emoji,
                        text = text,
                        userName = currentUserName!!,
                        startOffsetPercent = startOffsetPercent,
                        durationMillis = durationMillis
                    )
                    textBubbles.add(bubbleMessage)
                }
            }
        }
    }

    // 监听来自 ViewModel 的气泡消息
    LaunchedEffect(emojiBubbleMessageFromController) {
        emojiBubbleMessageFromController?.let { bubble ->
            if (!isInPipMode) {
                emojiBubbles.add(bubble)
            }
            // 清除状态，避免重复触发
            viewModel.callUiController.setEmojiBubbleMessage(null)
        }
    }

    LaunchedEffect(textBubbleMessageFromController) {
        textBubbleMessageFromController?.let { bubble ->
            if (!isInPipMode) {
                textBubbles.add(bubble)
            }
            // 清除状态，避免重复触发
            viewModel.callUiController.setTextBubbleMessage(null)
        }
    }

    LaunchedEffect(isInPipMode) {
        if (isInPipMode) {
            // 进入画中画时清理队列与气泡，避免状态积压
            visibleMessages.clear()
            removalJobs.values.forEach { it.cancel() }
            removalJobs.clear()
            emojiBubbles.clear()
            textBubbles.clear()
        }
    }

    fun removeVisibleMessage(message: BarrageMessage) {
        visibleMessages.remove(message)
        removalJobs.remove(message)?.cancel()
    }

    fun scheduleRemoval(message: BarrageMessage) {
        val displayDurationMillis = config.displayDurationMillis
        removalJobs.remove(message)?.cancel()
        removalJobs[message] = coroutineScope.launch {
            delay(displayDurationMillis)
            removeVisibleMessage(message)
        }
    }

    fun enqueueBarrageMessage(message: BarrageMessage) {
        if (config.showLimitCount <= 0) return
        if (visibleMessages.size >= config.showLimitCount) {
            removeVisibleMessage(visibleMessages.first())
        }
        visibleMessages.add(message)
        scheduleRemoval(message)
    }

    LaunchedEffect(barrageMessage) {
        barrageMessage?.let {
            if (isInPipMode) return@let
            enqueueBarrageMessage(it)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 原有的 Column 布局保持不变
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding, start = 16.dp, end = 16.dp)
                .align(Alignment.BottomStart),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Bottom
        ){
            if(!isInPipMode) {
                visibleMessages.forEachIndexed { index, message ->
                    Column(
                        modifier = Modifier
                            .alpha(0.9f)
                            .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                            .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                            .wrapContentWidth()
                            .heightIn(min = LCallUiConstants.BARRAGE_MESSAGE_ITEM_HEIGHT.dp)
                            .background(color = DifftTheme.colors.backgroundSettingItem, shape = RoundedCornerShape(size = 8.dp))
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
                            val annotatedString = buildAnnotatedString {
                                withStyle(
                                    style = SpanStyle(
                                        color = ColorTokens.InfoLight
                                    )
                                ) {
                                    append(message.userName)
                                }
                                withStyle(
                                    style = SpanStyle(
                                        color = DifftTheme.colors.textPrimary
                                    )
                                ) {
                                    append(" ${StringUtil.truncateWithEllipsis(message.message, config.textMaxLength)}")
                                }
                            }

                            Text(
                                modifier = Modifier
                                    .widthIn(max = 360.dp)
                                    .heightIn(min = 20.dp),
                                text = annotatedString,
                                // SF/P3
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

                    if (index < visibleMessages.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp)) // 设置Box件的垂直间距为8dp
                    }
                }

                if(!isShareScreening) {
                    ShowHandsUpTipView(viewModel)
                }

                ShouldShowBarrageInput(
                    viewModel,
                    config,
                    showSimpleBarrageEnabled,
                    setExpanded = { value -> viewModel.callUiController.setShowSimpleBarrageEnabled(value) },
                    onClickItem = { value, type -> onClickItem(value, type) },
                    onShowInputOverlay = { showInputOverlay = true }
                )
            }
        }

        if(!isInPipMode) {
            // 显示所有气泡消息（作为覆盖层，在 Column 之上）
            emojiBubbles.forEach { bubble ->
                key(bubble.id) {
                    EmojiBubbleView(
                        bubbleMessage = bubble,
                        onAnimationEnd = {
                            coroutineScope.launch {
                                // 等当前帧结束，避免组合中修改 State
                                kotlinx.coroutines.yield()
                                emojiBubbles.remove(bubble)
                            }
                        }
                    )
                }
            }

            // 显示所有气泡消息（作为覆盖层，在 Column 之上）
            textBubbles.forEach { bubble ->
                key(bubble.id) {
                    TextBubbleView(
                        bubbleMessage = bubble,
                        onAnimationEnd = {
                            coroutineScope.launch {
                                // 等当前帧结束，避免组合中修改 State
                                kotlinx.coroutines.yield()
                                textBubbles.remove(bubble)
                            }
                        }
                    )
                }
            }
        }

        // Input overlay - uses Activity's window insets instead of Dialog
        if (showInputOverlay) {
            // Calculate IME bottom padding manually to avoid double application
            val imeBottomPadding = WindowInsets.ime.getBottom(density)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { closeInputOverlay(clearText = true) },
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .displayCutoutPadding()
                        .padding(bottom = with(density) { imeBottomPadding.toDp() })
                        .background(DifftTheme.colors.backgroundSettingItem)
                        .clickable(enabled = false) {}
                ) {
                    TextField(
                        value = inputText,
                        onValueChange = { inputText = limitInputLength(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(inputFocusRequester),
                        singleLine = false,
                        minLines = 1,
                        maxLines = 4,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = FontFamily.Default,
                            fontWeight = FontWeight(510),
                            color = DifftTheme.colors.textPrimary
                        ),
                        placeholder = {
                            Text(
                                text = ResUtils.getString(com.difft.android.call.R.string.call_barrage_message_input_tip),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontFamily = FontFamily.Default,
                                    fontWeight = FontWeight(510),
                                    color = DifftTheme.colors.textDisabled
                                )
                            )
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitInputText() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = DifftTheme.colors.textPrimary
                        )
                    )
                }
            }
        }
    }
}


@Composable
private fun ShouldShowBarrageInput(
    viewModel: LCallViewModel,
    config: BarrageMessageConfig,
    expanded: Boolean,
    setExpanded:(Boolean) ->Unit,
    onClickItem: (String, BubbleMessageType) -> Unit,
    onShowInputOverlay: () -> Unit
) {

    val showBottomToolBarViewEnabled by viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current

    if((config.isOneVOneCall && !isLandscape) || showBottomToolBarViewEnabled) {
        Spacer(modifier = Modifier.height(4.dp))
        ConstraintLayout {
            val (dropdownMenu, barrageView) = createRefs()
            if (expanded) {
                BubbleBarrageMessage(
                    modifier = Modifier.constrainAs(dropdownMenu) {
                        bottom.linkTo(barrageView.top, margin = 4.dp)
                    },
                    config = config,
                    onClickItem = { message, type ->
                        onClickItem(message, type)
                    }
                )
            }

            Row(
                modifier = Modifier
                    .constrainAs(barrageView) {}
                    .alpha(0.9f)
                    .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                    .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                    .wrapContentSize()
                    .background(color = DifftTheme.colors.backgroundSettingItem, shape = RoundedCornerShape(size = 8.dp))
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                    .clickable { setExpanded(!expanded) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    modifier = Modifier
                        .padding(0.83333.dp)
                        .width(20.dp)
                        .height(20.dp),
                    painter = painterResource(id = com.difft.android.call.R.drawable.tabler_mood_smile),
                    contentDescription = "barrage input icon",
                    contentScale = ContentScale.Fit,
                )
            }

//            Column(
//                modifier = Modifier
//                    .constrainAs(barrageView) {}
//                    .wrapContentWidth()
//                    .height(LCallUiConstants.SIMPLE_BARRAGE_INPUT_UI_HEIGHT.dp),
//                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
//                horizontalAlignment = Alignment.Start,
//            ) {
//                Row(
//                    modifier = Modifier
//                        .alpha(0.9f)
//                        .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
//                        .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
//                        .wrapContentWidth()
//                        .height(LCallUiConstants.SIMPLE_BARRAGE_INPUT_UI_HEIGHT.dp)
//                        .background(color = DifftTheme.colors.backgroundSettingItem, shape = RoundedCornerShape(size = 8.dp))
//                        .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
//                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
//                    verticalAlignment = Alignment.CenterVertically,
//                ) {
//                    Row(
//                        modifier = Modifier
//                            .width(20.dp)
//                            .height(20.dp)
//                            .clickable { setExpanded(!expanded) },
//                        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
//                        verticalAlignment = Alignment.CenterVertically,
//                    ) {
//                        Image(
//                            modifier = Modifier
//                                .padding(0.83333.dp)
//                                .width(20.dp)
//                                .height(20.dp),
//                            painter = painterResource(id = com.difft.android.call.R.drawable.tabler_mood_smile),
//                            contentDescription = "barrage input icon",
//                            contentScale = ContentScale.Fit,
//                        )
//                    }
//
//                    Text(
//                        text = context.getString(com.difft.android.call.R.string.call_barrage_message_text),
//                        modifier = Modifier
//                            .wrapContentWidth()
//                            .height(20.dp)
//                            .clickable {
//                                setExpanded(false)
//                                onShowInputOverlay()
//                            },
//                        style = TextStyle(
//                            fontSize = 14.sp,
//                            lineHeight = 20.sp,
//                            fontFamily = SfProFont,
//                            fontWeight = FontWeight(510),
//                            color = DifftTheme.colors.textDisabled,
//                        )
//                    )
//                }
//            }
        }
    }
}



