package com.difft.android.call.ui


import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.user.CallConfig
import com.difft.android.call.LCallManager
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.util.ViewUtil
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@SuppressLint("FlowOperatorInvokedInComposition")
@Composable
fun ScreenShareSpeakerView(
    modifier: Modifier = Modifier,
    viewModel: LCallViewModel,
    shareScreenUser: Participant,
    callConfig: CallConfig,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val activeSpeaker by viewModel.primarySpeaker.collectAsState(shareScreenUser)
    val context = LocalContext.current

    var screenSize by remember { mutableStateOf(ScreenSize(0, 0)) }

    val speakerViewWith = 120.dp
    val speakerViewHeight = 90.dp

    var dragViewOffsetX: Float by remember {
        mutableFloatStateOf(ViewUtil.dpToPx(12).toFloat())
    }
    var dragViewOffsetY: Float by remember { mutableFloatStateOf(ViewUtil.dpToPx(24).toFloat()) }

    val coroutineScope = rememberCoroutineScope()

    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    val showSpeaker = activeSpeaker?:shareScreenUser

    LaunchedEffect(showSpeaker) {
        coroutineScope.launch {
            showSpeaker.identity?.value?.let {
                userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, it)
            }
        }
    }

    val countDownEnabled by viewModel.timerManager.countDownEnabled.collectAsState(false)

    LaunchedEffect(isLandscape) {
        if(isLandscape){
            coroutineScope.launch {
                screenSize = getScreenSizeAsync(context)
            }
        }
    }

    if(!isInPipMode && isLandscape){
        Box(
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart)
                .background(color = Color.Transparent)
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
                                screenSize.width - ViewUtil.dpToPx(speakerViewWith.value.toInt())
                                    .toFloat()
                            )
                        dragViewOffsetY =
                            newOffsetY.coerceIn(
                                0f,
                                screenSize.height - ViewUtil.dpToPx(speakerViewHeight.value.toInt())
                                    .toFloat()
                            )
                    }
                }
        ){
            Box(
                modifier = Modifier
                    .wrapContentSize(Alignment.Center)
                    .size(speakerViewWith, speakerViewHeight)
                    .clip(shape = RoundedCornerShape(8.dp))
                    .background(color = Color.Transparent)
            ){
                ConstraintLayout(
                    modifier = modifier
                        .fillMaxSize()
                        .clip(shape = RoundedCornerShape(8.dp))
                ){
                    val (videoItem, countDownBar) = createRefs()

                    FloatingWindowSpeakerView(
                        modifier = Modifier.constrainAs(videoItem) {
                            top.linkTo(parent.top)
                            bottom.linkTo(parent.bottom)
                            start.linkTo(parent.start)
                            end.linkTo(parent.end)
                            width = Dimension.fillToConstraints
                            height = Dimension.fillToConstraints
                        },
                        room = viewModel.room,
                        showSpeaker = showSpeaker,
                        userDisplayInfo = userDisplayInfo
                    )

                    if(countDownEnabled) {
                        CountDownTimerView(
                            modifier = Modifier
                                .constrainAs(countDownBar) {
                                    end.linkTo(parent.end)
                                    top.linkTo(parent.top)
                                }
                            ,
                            viewModel = viewModel,
                            callConfig = callConfig
                        )
                    }
                }
            }
        }
    }
}



data class ScreenSize(val width: Int, val height: Int)

fun getScreenSize(context: android.content.Context): ScreenSize {
    val windowManager = context.getSystemService(Activity.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        val windowMetrics = windowManager.currentWindowMetrics
        metrics.widthPixels = windowMetrics.bounds.width()
        metrics.heightPixels = windowMetrics.bounds.height()
    } else {
        val display = windowManager.defaultDisplay
        display.getRealMetrics(metrics)
    }

    return ScreenSize(metrics.widthPixels, metrics.heightPixels)
}

private suspend fun getScreenSizeAsync(context: android.content.Context): ScreenSize = withContext(Dispatchers.IO) {
    getScreenSize(context)
}