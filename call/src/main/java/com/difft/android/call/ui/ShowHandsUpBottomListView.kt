package com.difft.android.call.ui


import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.LCallViewModel
import com.difft.android.call.data.HandUpUserInfo
import com.difft.android.call.util.StringUtil


@Composable
fun ShowHandsUpBottomListView(viewModel: LCallViewModel, handUpUserInfo: HandUpUserInfo, viewHeight: Dp, fontSize: Int, lineHeight: Int){
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewHeight),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start,
    ) {
        // Child views.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewHeight),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {

            key (handUpUserInfo.userId){
                AndroidView(
                    factory = { handUpUserInfo.userAvatar },
                    modifier = Modifier
                        .height(viewHeight)
                        .width(viewHeight)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = StringUtil.getShowUserName(handUpUserInfo.userName, 10),
                // SF/P2
                style = TextStyle(
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                    fontFamily = SfProFont,
                    fontWeight = FontWeight(400),
                    color = colorResource(id = com.difft.android.base.R.color.t_white),
                )
            )

            Spacer(modifier = Modifier.weight(1f)) // 用于将"Lower"按钮推向右侧

            Text(
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null){
                    viewModel.participants.value.firstOrNull { it.identity?.value == handUpUserInfo.userId }
                        ?.let { participant ->
                            viewModel.setHandsUpEnable(false, participant)
                        }
                },
                text = "Lower",
                // SF/P3
                style = TextStyle(
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                    fontFamily = SfProFont,
                    fontWeight = FontWeight(400),
                    color = colorResource(id = com.difft.android.base.R.color.t_secondary_night),
                )
            )
        }
    }
}