package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.SfProFont
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R

@Composable
fun ShowHandsUpTipView(viewModel: LCallViewModel) {

    val showHandsUpEnabled by viewModel.showHandsUpEnabled.collectAsState(false)
    val handsUpUserInfo by viewModel.handsUpUserInfo.collectAsState(emptyList())

    val shouldShowTip = handsUpUserInfo.isNotEmpty()

    if(shouldShowTip) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .alpha(0.9f)
                .shadow(elevation = 6.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                .shadow(elevation = 14.dp, spotColor = Color(0x14000000), ambientColor = Color(0x14000000))
                .wrapContentWidth()
                .height(36.dp)
                .background(color = colorResource(id = com.difft.android.base.R.color.bg2_night), shape = RoundedCornerShape(size = 8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable( interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    if(viewModel.isParticipantSharedScreen.value){
                        viewModel.setShowUserEnabled(!viewModel.showUsersEnabled.value)
                    }else{
                        viewModel.setShowHandsUpBottomViewEnabled(!showHandsUpEnabled)
                    }
                },
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Child views.
            Row(
                modifier = Modifier
                    .wrapContentWidth()
                    .height(20.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    modifier = Modifier
                        .width(16.dp)
                        .height(16.dp),
                    painter = painterResource(id = R.drawable.call_tabler_hand_stop),
                    contentDescription = "raise hand icon",
                    contentScale = ContentScale.None
                )
                val nameListText = handsUpUserInfo.map { it.userName }.joinToString(", ")
                Text(
                    text = nameListText,
                    modifier = Modifier
                        .widthIn(max = 138.dp)
                        .height(20.dp)
                    ,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = SfProFont,
                        fontWeight = FontWeight(510),
                        color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                    )
                )
            }
        }
    }


}