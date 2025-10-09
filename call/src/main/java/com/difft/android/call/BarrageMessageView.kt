package com.difft.android.call

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.data.BarrageMessage
import com.difft.android.call.data.BarrageMessageConfig
import com.difft.android.call.data.RTM_MESSAGE_TOPIC_CHAT
import com.difft.android.call.ui.ShowHandsUpTipView
import com.difft.android.call.ui.SimpleBarrageMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@Composable
fun BarrageMessageView(viewModel: LCallViewModel, config: BarrageMessageConfig, sendBarrageMessage: (String, String) -> Unit) {
    val coroutineScope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<BarrageMessage>() }
    val visibleMessages = remember { mutableStateListOf<BarrageMessage>() }
    val isInCycle = remember { mutableStateOf(false) }
    val showSimpleBarrageEnabled by viewModel.callUiController.showSimpleBarrageEnabled.collectAsState(false)

    val barrageMessage by viewModel.callUiController.barrageMessage.collectAsState(null)
    val isShareScreening by viewModel.callUiController.isShareScreening.collectAsState(false)
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 当点击菜单项时的回调
    fun onClickItem(item: String) {
        viewModel.callUiController.setShowSimpleBarrageEnabled(false)
        sendBarrageMessage(item, RTM_MESSAGE_TOPIC_CHAT)
    }

    LaunchedEffect(barrageMessage) {
        L.d { "[call] LCallActivity ShowBarrageMessage barrageMessage:$barrageMessage" }
        barrageMessage?.let {
            messages.add(it)
            if(!isInCycle.value){
                isInCycle.value = true
                coroutineScope.launch {
                    while (messages.isNotEmpty() || visibleMessages.isNotEmpty()){
                        delay(500L)
                        // 移除过期的消息
                        visibleMessages.removeAll { (_, _, timestamp) ->
                            System.currentTimeMillis() - timestamp > config.displayDurationMillis
                        }
                        if(messages.isNotEmpty()){
                            visibleMessages.add(messages.first())
                            messages.removeAt(0)
                        }
                        if(visibleMessages.size > config.showLimitCount){
                            visibleMessages.removeAt(0)
                        }
                        if(messages.isEmpty() && visibleMessages.isEmpty()){
                            isInCycle.value = false
                            break
                        }
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (!isLandscape) 96.dp else 20.dp, start = 16.dp),
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
                        .height(LCallUiConstants.BARRAGE_MESSAGE_ITEM_HEIGHT.dp)
                        .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
                        .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier
                            .wrapContentWidth()
                            .height(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val annotatedString = buildAnnotatedString {
                            withStyle(
                                style = SpanStyle(
                                    color = colorResource(id = com.difft.android.base.R.color.t_info_night),
                                )
                            ) {
                                append(message.userName)
                            }
                            append(" ${message.message}")
                        }

                        Text(
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(20.dp),
                            text = annotatedString,
                            // SF/P3
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = SfProFont,
                                fontWeight = FontWeight(400),
                            ),
                            maxLines = 1,
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

            ShouldShowBarrageInput(viewModel, config, showSimpleBarrageEnabled, setExpanded = { value -> viewModel.callUiController.setShowSimpleBarrageEnabled(value) } , onClickItem = { value -> onClickItem(value) })
        }

    }
}


@Composable
private fun ShouldShowBarrageInput(viewModel: LCallViewModel, config: BarrageMessageConfig, expanded: Boolean, setExpanded:(Boolean) ->Unit, onClickItem: (String) -> Unit) {

    val showBottomToolBarViewEnabled by viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current
    if((config.isOneVOneCall && !isLandscape) || showBottomToolBarViewEnabled) {
        Spacer(modifier = Modifier.height(4.dp))
        ConstraintLayout {
            val (dropdownMenu, barrageView) = createRefs()
            if (expanded) {
                SimpleBarrageMessage(
                    modifier = Modifier.constrainAs(dropdownMenu) {
                        bottom.linkTo(barrageView.top, margin = 4.dp)
                    },
                    config = config,
                    onClickItem = { message ->
                        onClickItem(message)
                    }
                )
            }

            Column(
                modifier = Modifier
                    .constrainAs(barrageView) {}
                    .wrapContentWidth()
                    .height(LCallUiConstants.SIMPLE_BARRAGE_INPUT_UI_HEIGHT.dp)
                    .clickable { setExpanded(!expanded) },
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                horizontalAlignment = Alignment.Start,
            ) {
                Row(
                    modifier = Modifier
                        .alpha(0.9f)
                        .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                        .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                        .wrapContentWidth()
                        .height(LCallUiConstants.SIMPLE_BARRAGE_INPUT_UI_HEIGHT.dp)
                        .background(color = colorResource(com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
                        .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .width(20.dp)
                            .height(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            modifier = Modifier
                                .padding(0.83333.dp)
                                .width(20.dp)
                                .height(20.dp),
                            painter = painterResource(id = R.drawable.barrage_logo),
                            contentDescription = "barrage input icon",
                            contentScale = ContentScale.Fit,
                        )
                    }

                    Text(
                        text = context.getString(R.string.call_barrage_broadcast_message_tip),
                        modifier = Modifier
                            .wrapContentWidth()
                            .height(20.dp),
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontFamily = SfProFont,
                            fontWeight = FontWeight(510),
                            color = colorResource(com.difft.android.base.R.color.gray_500),
                        )
                    )
                }
            }
        }
    }
}



