package com.difft.android.call.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallUiConstants
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallEndType
import com.difft.android.call.onMediaControlTapped
import com.difft.android.call.permission.CallMediaPermission


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainPageWithBottomControlView(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    isUserSharingScreen: Boolean = false,
    endCallAction: (callType: String, callEndType: CallEndType) -> Unit
){
    // 直接 collectAsState；可见性变化会让本 composable 重组一次，按钮 Row 等
    // 子树参数稳定（Compose 自动 skip），实际成本 <1ms。换来的是可见态完全
    // 不挂 pointerInput，命中测试中本节点透明，详见 isBottomVisible 顶部注释。
    val showBottomState = viewModel.callUiController.showBottomToolBarViewEnabled.collectAsState(true)
    val participants by viewModel.participants.collectAsState(initial = emptyList())
    val micEnabled by viewModel.micEnabled.collectAsState(false)
    val videoEnabled by viewModel.cameraEnabled.collectAsState(false)
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
    val usersPainter = painterResource(id = R.drawable.users)
    val dotsPainter = painterResource(id = R.drawable.call_btn_tabler_dots)

    // Tap routing + system-request launching + Settings guide all live in
    // LCallActivityMediaPermissions (single decision point). Compose only renders
    // the mic badge from the coordinator state — camera is dialog-only, no badge.
    val micPermissionState by viewModel.mediaPermissions.micState.collectAsState()
    val showMicPermissionBadge = micPermissionState.showsBadge

    val isBottomVisible = isOneVOneCall && !isUserSharingScreen || showBottomState.value

    Column(
        modifier = Modifier
            .then(
                if (!isLandscape) {
                    Modifier.padding(bottom = LCallUiConstants.BOTTOM_BAR_MARGIN_BOTTOM_DP.dp)
                } else {
                    // Landscape-only margin, not mirrored anywhere — stays a literal.
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
        ) {
            val controlSize = LCallUiConstants.BOTTOM_BAR_CONTROL_SIZE_DP.dp
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
                        // Preset ring hides with the preset badge while mic permission is
                        // denied: the voice changer only shapes local capture, and with no
                        // RECORD_AUDIO there is no capture — advertising it would mislead.
                        border = if (voicePreset.isEnabled && !showMicPermissionBadge) BorderStroke(
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
                                .testTag("call_btn_mic")
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {
                                        L.i { "[call] LCallActivity onClick Mic" }
                                        if (viewModel.isControlButtonClickEnabled() && context is LCallActivity) {
                                            context.onMediaControlTapped(CallMediaPermission.Microphone)
                                        }
                                    }
                                )
                        )
                    }
                    if (showMicPermissionBadge) {
                        MicPermissionBadge(modifier = Modifier.align(Alignment.TopEnd))
                    }
                    // The permission badge replaces the entire voice-preset treatment (badge
                    // here, ring above) while visible — Figma 17129:3875 shows only the alert
                    // badge, and an inactive capture chain makes the preset state meaningless.
                    if (voicePreset.isEnabled && !showMicPermissionBadge) {
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
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .testTag("call_btn_camera")
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                L.i { "[call] LCallActivity onClick Camera" }
                                if (viewModel.isControlButtonClickEnabled() && context is LCallActivity) {
                                    context.onMediaControlTapped(CallMediaPermission.Camera)
                                }
                            }
                    )
                }

                Spacer(modifier = Modifier.width(controlPadding))

                AudioRouteControl(
                    viewModel = viewModel,
                    isOneVOneCall = isOneVOneCall,
                    controlSize = controlSize,
                )

                if (isUserSharingScreen) {
                    Spacer(modifier = Modifier.width(controlPadding))
                    Surface(
                        modifier = Modifier.size(controlSize),
                        color = Color.Transparent
                    ) {
                        Box {
                            Image(
                                painter = usersPainter,
                                contentDescription = "Users",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .testTag("call_btn_users")
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        L.i { "[call] LCallActivity onClick Users" }
                                        viewModel.callUiController.setShowUsersEnabled(
                                            !viewModel.callUiController.showUsersEnabled.value
                                        )
                                    }
                            )
                            if (participants.isNotEmpty()) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(20.dp)
                                        .background(
                                            color = DifftTheme.colors.backgroundTooltip,
                                            shape = CircleShape
                                        )
                                ) {
                                    Text(
                                        text = "${participants.size}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        lineHeight = 16.sp,
                                        fontFamily = FontFamily.Default,
                                        fontWeight = FontWeight(590),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.wrapContentSize(Alignment.Center)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(controlPadding))

                Row(
                    modifier = Modifier
                        .testTag("call_btn_more")
                        .width(48.dp)
                        .height(48.dp)
                        .background(
                            color = DifftTheme.colors.backgroundSecondary,
                            shape = RoundedCornerShape(size = 100.00001.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            viewModel.callUiController.setShowToolBarBottomViewEnable(true)
                        }
                        .padding(12.dp),
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

                if (currentCallType == CallType.ONE_ON_ONE.type) {
                    OneOnOneHangupButton(
                        onHangup = { endCallAction(currentCallType, CallEndType.END) }
                    )
                } else if (!isLandscape) {
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

/**
 * Red alert badge on the mic toggle while RECORD_AUDIO is denied (spec: persistent,
 * non-blocking denial indicator; mic only — camera/screen-share are dialog-only).
 *
 * Per the Figma spec (node 17129:3860): 16dp tabler alert-circle-filled, error red,
 * flush to the button's top-end corner; the "!" is a cutout showing the background.
 */
@Composable
internal fun MicPermissionBadge(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.call_ic_mic_permission_badge),
        contentDescription = null,
        modifier = modifier
            .testTag("call_mic_permission_badge")
            .size(16.dp)
    )
}
