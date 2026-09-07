package com.difft.android.call.ui.barrage

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
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
import com.difft.android.base.utils.globalServices
import com.difft.android.call.LCallManager
import com.difft.android.call.data.BubbleMessageType
import com.difft.android.call.data.RTM_MESSAGE_TYPE_BUBBLE
import com.difft.android.call.data.RTM_MESSAGE_TYPE_DEFAULT
import com.difft.android.call.data.TextBubbleMessage
import com.difft.android.call.ui.actionbar.EmojiReactionSheet
import com.difft.android.call.ui.actionbar.rememberCallActionBarPlan
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

    // The stack floats just above whatever the bar plan occupies (bar, outside Emoji pill, or
    // the two-row backplate). Pure window-size geometry: no posture or device special cases.
    val plan = rememberCallActionBarPlan(isGroup = !config.isOneVOneCall)
    val bottomPadding = (plan.chromeBottomReserveDp + LCallUiConstants.CHROME_CONTENT_GAP_DP).dp

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

            }
        }

        // The Emoji entry now lives in the action bar; this sheet is what it opens.
        EmojiReactionSheet(
            visible = showSimpleBarrageEnabled && !isInPipMode,
            emojis = config.emojiPresets,
            phrases = config.textPresets,
            isLandscape = isLandscape,
            onEmoji = { onClickItem(it, BubbleMessageType.EMOJI) },
            onPhrase = { onClickItem(it, BubbleMessageType.TEXT) },
            onDismiss = { callUiController.setShowSimpleBarrageEnabled(false) },
        )

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
