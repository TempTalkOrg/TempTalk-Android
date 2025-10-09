package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.CallStatus


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShowItemsBottomView(viewModel: LCallViewModel, isOneVOneCall: Boolean, onDismiss: () -> Unit, deNoiseCallBack: (Boolean) -> Unit, handleInviteUsersClick: () -> Unit = {}) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    val context = LocalContext.current
    val showToolBarBottomViewEnable by viewModel.callUiController.showToolBarBottomViewEnable.collectAsState(false)
    val isParticipantSharedScreen by viewModel.callUiController.isShareScreening.collectAsState(false)
    val deNoiseEnable by viewModel.deNoiseEnable.collectAsState(true)
    val isInPipMode by viewModel.callUiController.isInPipMode.collectAsState(false)
    val handsUpEnabled by viewModel.callUiController.handsUpEnabled.collectAsState(false)

    val itemSpace = if (isOneVOneCall) 50.dp else 32.dp

    if(showToolBarBottomViewEnable && !isInPipMode){
        ModalBottomSheet (
            modifier = Modifier
                .then(if (isParticipantSharedScreen) Modifier.width(375.dp) else Modifier.fillMaxWidth())
                .wrapContentHeight(),
            sheetState = sheetState,
            containerColor = colorResource(id = com.difft.android.base.R.color.bg3_night),
            onDismissRequest = {
                onDismiss()
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(color = colorResource(id = com.difft.android.base.R.color.bg3_night), shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 0.dp, bottomEnd = 0.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                    horizontalArrangement = Arrangement.spacedBy(itemSpace, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .height(76.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier
                                .width(48.dp)
                                .height(48.dp)
                                .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 100.dp))
                                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    L.i { "[call] ShowItemsBottomView onClick invite" }
                                    handleInviteUsersClick()
                                    onDismiss()
                                },
                            horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(24.dp)
                                    .padding(start = 3.dp, top = 3.dp, end = 2.dp, bottom = 2.99961.dp),
                                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    modifier = Modifier
                                        .padding(0.dp)
                                        .width(19.dp)
                                        .height(18.00039.dp),
                                    painter = painterResource(id = R.drawable.call_bottom_invite),
                                    contentDescription = "invite",
                                    contentScale = ContentScale.Fit
                                )
                            }
                        }
                        Text(
                            text = context.getString(R.string.call_toolbar_bottom_invite_text),
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = SfProFont,
                                fontWeight = FontWeight(400),
                                color = colorResource(id = com.difft.android.base.R.color.gray_50),
                            )
                        )
                    }

                    if(!isOneVOneCall) {
                        Column(
                            modifier = Modifier
                                .width(80.dp)
                                .height(76.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(48.dp)
                                    .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 100.dp))
                                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null){
                                        L.i { "[call] ShowItemsBottomView onClick raise hand" }
                                        viewModel.callStatus.value.let { status ->
                                            if(status == CallStatus.CONNECTED || status == CallStatus.RECONNECTED) {
                                                viewModel.rtm.sendRaiseHandsRtmMessage(!handsUpEnabled, viewModel.room.localParticipant, onComplete = { status ->
                                                    if(status){
                                                        viewModel.callUiController.setHandsUpEnable(!handsUpEnabled)
                                                    } else {
                                                        L.e { "[call] ShowItemsBottomView Error sending raise hand: $status" }
                                                    }
                                                })
                                            } else
                                                (context.getActivity() as? LCallActivity)?.let {
                                                    it.showStyledPopTip(it.getString(R.string.call_connecting_retry_tip))
                                                }

                                            onDismiss()
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Image(
                                    modifier = Modifier
                                        .padding(1.dp)
                                        .width(24.dp)
                                        .height(24.dp),
                                    painter = painterResource(id = R.drawable.call_bottom_hand_stop),
                                    contentDescription = "raise hand",
                                    contentScale = ContentScale.Fit
                                )
                            }
                            Text(
                                text = context.getString(R.string.call_toolbar_bottom_raise_hand_text),
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontFamily = SfProFont,
                                    fontWeight = FontWeight(400),
                                    color = colorResource(id = com.difft.android.base.R.color.gray_50),
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .width(80.dp)
                            .height(76.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier
                                .width(48.dp)
                                .height(48.dp)
                                .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 100.dp))
                                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    try {
                                        L.i { "[call] ShowItemsBottomView onClick switch" }
                                        viewModel.flipCamera()
                                    } catch (e: Exception) {
                                        L.e { "[call] ShowItemsBottomView Error switching camera: ${e.message}" }
                                    }
                                },
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.Start),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Image(
                                modifier = Modifier
                                    .padding(1.dp)
                                    .width(24.dp)
                                    .height(24.dp),
                                painter = painterResource(id = R.drawable.chat_tabler_camera_rotate),
                                contentDescription = "switch camera",
                                contentScale = ContentScale.Fit
                            )
                        }

                        Text(
                            text = context.getString(R.string.call_toolbar_bottom_switch_text),
                            style = TextStyle(
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontFamily = SfProFont,
                                fontWeight = FontWeight(400),
                                color = colorResource(id = com.difft.android.base.R.color.gray_50)
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .then(if (isParticipantSharedScreen) Modifier.width(375.dp) else Modifier.fillMaxWidth())
                        .height(79.dp)
                        .background(color = colorResource(id = com.difft.android.base.R.color.bg3_night), shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
                        .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(55.dp)
                            .background(color = colorResource(id = com.difft.android.base.R.color.gray_600), shape = RoundedCornerShape(size = 8.dp))
                            .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(24.dp),
                            text = context.getString(R.string.call_toolbar_noise_suppression_text),
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                fontFamily = SfProFont,
                                fontWeight = FontWeight(400),
                                color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                            )
                        )

                        Switch(
                            modifier = Modifier
                                .width(51.dp)
                                .height(31.dp)
                                .padding(end = 10.dp)
                                .semantics { contentDescription = "DeNoise" },
                            checked = deNoiseEnable,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = colorResource(id = com.difft.android.base.R.color.t_white),
                                checkedTrackColor = colorResource(id = com.difft.android.base.R.color.primary),
                                uncheckedThumbColor = colorResource(id = com.difft.android.base.R.color.t_white),
                                uncheckedTrackColor = colorResource(id = com.difft.android.base.R.color.gray_600)
                            ),
                            onCheckedChange = {
                                viewModel.audioDeviceManager.switchDeNoiseEnable(it)
                                deNoiseCallBack(it)
                            }
                        )
                    }
                }
            }
        }
    }


}