package com.difft.android.call.ui

import android.Manifest
import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallEndType
import com.difft.android.call.util.openAppSettings
import com.difft.android.call.util.rememberPermissionChecker
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.audio.AudioSwitchHandler


@OptIn(ExperimentalFoundationApi::class, ExperimentalPermissionsApi::class)
@Composable
fun MainPageWithBottomControlView(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean = false,
    audioSwitchHandler: AudioSwitchHandler? = null,
    endCallAction: (callType: String, callEndType: CallEndType) -> Unit
){
    // 直接 collectAsState；可见性变化会让本 composable 重组一次，按钮 Row 等
    // 子树参数稳定（Compose 自动 skip），实际成本 <1ms。换来的是可见态完全
    // 不挂 pointerInput，命中测试中本节点透明，详见 isBottomVisible 顶部注释。
    val showBottomState = viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val micEnabled by viewModel.micEnabled.collectAsState(false)
    val videoEnabled by viewModel.cameraEnabled.collectAsState(false)
    val currentAudioDevice by viewModel.currentAudioDevice.collectAsState()
    val currentCallType by viewModel.callType.collectAsState()
    val voicePreset by viewModel.voicePreset.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val context = LocalContext.current

    // 预加载并缓存 painter，避免首次展示时触发资源解析卡顿
    val micOpenPainter = painterResource(id = R.drawable.call_btn_microphone_open)
    val micClosePainter = painterResource(id = R.drawable.call_btn_microphone_close)
    val cameraOpenPainter = painterResource(id = R.drawable.call_btn_camera_open)
    val cameraClosePainter = painterResource(id = R.drawable.call_btn_camera_close)
    val volumePhonePainter = painterResource(id = R.drawable.call_btn_volume_phone)
    val volumeSpeakerPainter = painterResource(id = R.drawable.call_btn_volume_speaker)
    val volumeHeadphonesPainter = painterResource(id = R.drawable.call_btn_volume_headphones)
    val volumeAirpodPainter = painterResource(id = R.drawable.call_btn_volume_airpod)
    val usersPainter = painterResource(id = R.drawable.users)
    val dotsPainter = painterResource(id = R.drawable.call_btn_tabler_dots)

    val requestMicPermission = rememberPermissionChecker(
        viewModel = viewModel,
        permission = Manifest.permission.RECORD_AUDIO,
        onGranted = {
            // Update foreground service type when microphone permission is granted
            // This must be done before enabling microphone to ensure service type is correct
            if (context is LCallActivity) {
                context.updateForegroundServiceType()
            }
            // Enable microphone after updating service type
            viewModel.setMicEnabled(!viewModel.micEnabled.value)
        },
        onDenied =  {
            ComposeDialogManager.showMessageDialog(
                context = context,
                cancelable = true,
                title = ResUtils.getString(R.string.call_microphone_permission_deny_title),
                message = ResUtils.getString(R.string.call_microphone_permission_deny_content),
                confirmText = ResUtils.getString(R.string.call_permission_button_setting_go),
                cancelText = ResUtils.getString(R.string.call_permission_button_setting_cancel),
                onConfirm = {
                    openAppSettings(context)
                    viewModel.callUiController.setRequestPermissionStatus(true)
                }
            )
        }
    )

    val requestCameraPermission = rememberPermissionChecker(
        viewModel = viewModel,
        permission = Manifest.permission.CAMERA,
        onGranted = {
            // Update foreground service type when camera permission is granted
            // This must be done before enabling camera to ensure service type is correct
            if (context is LCallActivity) {
                context.updateForegroundServiceType()
            }
            // Enable camera after updating service type
            viewModel.setCameraEnabled(!viewModel.cameraEnabled.value)
        },
        onDenied = {
            ComposeDialogManager.showMessageDialog(
                context = context,
                cancelable = true,
                title = ResUtils.getString(R.string.call_camera_permission_deny_title),
                message = ResUtils.getString(R.string.call_camera_permission_deny_content),
                confirmText = ResUtils.getString(R.string.call_permission_button_setting_go),
                cancelText = ResUtils.getString(R.string.call_permission_button_setting_cancel),
                onConfirm = {
                    openAppSettings(context)
                    viewModel.callUiController.setRequestPermissionStatus(true)
                }
            )
        }
    )

    val isBottomVisible = isOneVOneCall && !isUserSharingScreen || showBottomState.value

    Column(
        modifier = Modifier
            .then(
                if (!isLandscape) {
                    Modifier.padding(bottom = 32.dp)
                } else {
                    Modifier.padding(bottom = 16.dp)
                }
            )
            .fillMaxWidth(),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Transparent)
                .alpha(if (isBottomVisible) 1f else 0f)
                .tapInterceptor(enabled = !isBottomVisible) {
                    viewModel.callUiController.toggleOverlays()
                }
        ){
                val controlSize = 48.dp
                val controlPadding = if (isLandscape) 16.dp else 12.dp
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .wrapContentSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                                Box(modifier = Modifier.size(controlSize)) {
                                    Surface(
                                        modifier = Modifier.size(controlSize),
                                        color = Color.Transparent,
                                        shape = CircleShape,
                                        border = if (voicePreset.isEnabled) BorderStroke(
                                            width = 2.dp,
                                            color = colorResource(id = com.difft.android.base.R.color.blue_400),
                                        ) else null
                                    ) {
                                        val painter = if (micEnabled) micOpenPainter else micClosePainter
                                        Image(
                                            painter = painter,
                                            contentDescription = "Mic",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .clickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = {
                                                        L.i { "[call] LCallActivity onClick Mic" }
                                                        if (viewModel.isControlButtonClickEnabled()) requestMicPermission()
                                                    }
                                                )
                                        )
                                    }
                                    if (voicePreset.isEnabled) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(18.dp)
                                                .background(
                                                    brush = Brush.linearGradient(
                                                        colors = listOf(
                                                            Color(0xFF4DA0FF),
                                                            Color(0xFF82C1FC),
                                                            Color(0xFF328AFD)
                                                        ),
                                                        start = Offset(0f, 0f),
                                                        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                                                    ),
                                                    shape = CircleShape
                                                )
                                        ) {
                                            Text(
                                                text = voicePreset.emoji,
                                                fontSize = 12.sp,
                                                lineHeight = 16.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(controlPadding))

                                Surface(
                                    modifier = Modifier.size(controlSize),
                                    color = Color.Transparent
                                ) {
                                    val painter = if (videoEnabled) cameraOpenPainter else cameraClosePainter
                                    Image(
                                        painter = painter,
                                        contentDescription = "Camera",
                                        contentScale = ContentScale.Fit, // 根据需要调整
                                        modifier = Modifier.clickable( interactionSource = remember { MutableInteractionSource() }, indication = null)
                                        {
                                            L.i { "[call] LCallActivity onClick Camera" }
                                            if (viewModel.isControlButtonClickEnabled()) requestCameraPermission()
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(controlPadding))

                                var showAudioDeviceDialog by remember { mutableStateOf(false) }

                                Surface(
                                    modifier = Modifier.size(controlSize),
                                    color = Color.Transparent
                                ) {
                                    val painter = when (currentAudioDevice) {
                                        is AudioDevice.Earpiece -> volumePhonePainter
                                        is AudioDevice.Speakerphone -> volumeSpeakerPainter
                                        is AudioDevice.WiredHeadset -> volumeHeadphonesPainter
                                        is AudioDevice.BluetoothHeadset -> volumeAirpodPainter
                                        else -> volumeSpeakerPainter
                                    }
                                    Image(
                                        painter = painter,
                                        contentDescription = "Horn",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null)
                                            {
                                                L.i { "[call] LCallActivity onClick Horn" }
                                                if(audioSwitchHandler != null){
                                                    if(audioSwitchHandler.availableAudioDevices.size>2){
                                                        showAudioDeviceDialog = !showAudioDeviceDialog
                                                    }else{
                                                        viewModel.audioDeviceManager.switchToNext()
                                                    }
                                                }
                                            }
                                    )

                                    audioSwitchHandler?.availableAudioDevices?.let { availableAudioDevices->
                                        ShowAudioDeviceOnClickView(
                                            audioDevices = availableAudioDevices,
                                            currentDevice = currentAudioDevice ?: audioSwitchHandler.selectedAudioDevice,
                                            expanded = showAudioDeviceDialog,
                                            setExpanded = { value -> showAudioDeviceDialog = value},
                                            onClickItem = { item ->
                                                item.let {
                                                    viewModel.audioDeviceManager.select(item)
                                                    showAudioDeviceDialog = false
                                                }
                                            }
                                        )
                                    }
                                }

                                if(isUserSharingScreen){
                                    Spacer(modifier = Modifier.width(controlPadding))
                                    Surface(
                                        modifier = Modifier.size(controlSize),
                                        color = Color.Transparent
                                    ){
                                        Box {
                                            Image(
                                                painter = usersPainter,
                                                contentDescription = "Users",
                                                contentScale = ContentScale.Fit, // 根据需要调整
                                                modifier = Modifier.clickable( interactionSource = remember { MutableInteractionSource() }, indication = null)
                                                {
                                                    //展开参会人列表
                                                    L.i { "[call] LCallActivity onClick Users" }
                                                    viewModel.callUiController.setShowUsersEnabled(!viewModel.callUiController.showUsersEnabled.value)
                                                }
                                            )
                                            if(participants.isNotEmpty()){
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .align(Alignment.BottomEnd)
                                                        .size(20.dp)
                                                        .background(
                                                            color = colorResource(id = com.difft.android.base.R.color.bg_tooltip),
                                                            shape = CircleShape
                                                        )
                                                ){
                                                    Text(
                                                        text = "${participants.size}",
                                                        color = Color.White,
                                                        fontSize = 10.sp,
                                                        lineHeight = 16.sp,
                                                        fontFamily = FontFamily.Default,
                                                        fontWeight = FontWeight(590),
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier
                                                            .wrapContentSize(Alignment.Center)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(controlPadding))

                                Row(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .height(48.dp)
                                        .background(
                                            color = colorResource(id = com.difft.android.base.R.color.bg2_night),
                                            shape = RoundedCornerShape(size = 100.00001.dp)
                                        )
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            viewModel.callUiController.setShowToolBarBottomViewEnable(true)
                                        }
                                        .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        10.000000953674316.dp,
                                        Alignment.Start
                                    ),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Image(
                                        modifier = Modifier
                                            .padding(1.dp)
                                            .width(24.dp)
                                            .height(24.dp),
                                        painter = dotsPainter,
                                        contentDescription = "more options menu",
                                        contentScale = ContentScale.None
                                    )
                                }

                                Spacer(modifier = Modifier.width(controlPadding))

                                if(currentCallType == CallType.ONE_ON_ONE.type) {
                                    OneOnOneHangupButton(
                                        onHangup = { endCallAction(currentCallType, CallEndType.END) }
                                    )
                                } else if(!isLandscape) {
                                    GroupCallLeaveButton(
                                        onLeave = { endCallAction(currentCallType, CallEndType.LEAVE) },
                                        onShowEndMenu = { viewModel.callUiController.setShowBottomCallEndViewEnable(true) }
                                    )
                                }
                            }

                    if (currentCallType != CallType.ONE_ON_ONE.type && isLandscape) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 19.dp)
                        ) {
                            GroupCallLeaveButton(
                                onLeave = { endCallAction(currentCallType, CallEndType.LEAVE) },
                                onShowEndMenu = { viewModel.callUiController.setShowBottomCallEndViewEnable(true) }
                            )
                        }
                    }
        }
    }
}
