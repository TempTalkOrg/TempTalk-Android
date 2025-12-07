package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.difft.android.base.R
import com.difft.android.call.LCallManager
import com.difft.android.call.data.CallUserDisplayInfo
import com.difft.android.call.util.StringUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx3.asFlow
import kotlin.collections.contains


/**
 * Widget for displaying peer participant.
 */
@Composable
fun CalleeParticipantItem(
    modifier: Modifier = Modifier,
    userId: String,
    statusTip: String? = null
){
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var userDisplayInfo: CallUserDisplayInfo by remember { mutableStateOf(CallUserDisplayInfo(null, null, null)) }

    val contactsUpdate by LCallManager.getContactsUpdateListener().map {
        Pair(System.currentTimeMillis(), it)
    }.asFlow().collectAsState(Pair(0L, emptyList()))

    suspend fun updateNameAndAvatar(userId: String) {
        userDisplayInfo = LCallManager.getParticipantDisplayInfo(context, userId)
    }

    LaunchedEffect(Unit) {
        coroutineScope.launch {
            updateNameAndAvatar(userId)
        }
    }

    LaunchedEffect(contactsUpdate) {
        if(contactsUpdate.second.contains(LCallManager.getUidByIdentity(userId))){
            coroutineScope.launch {
                updateNameAndAvatar(userId)
            }
        }
    }

    ConstraintLayout(
        modifier = modifier
            .background(colorResource(id = R.color.bg1_night))
    ){
        val (avatarView) = createRefs()

        Column(
            modifier = Modifier.constrainAs(avatarView) {
                centerVerticallyTo(parent)
                top.linkTo(parent.top)
                bottom.linkTo(parent.bottom)
                start.linkTo(parent.start)
                end.linkTo(parent.end)
                width = Dimension.fillToConstraints
//                height = Dimension.fillToConstraints
            },
//            verticalArrangement = Arrangement.Top, // 设置垂直居中
            horizontalAlignment = Alignment.CenterHorizontally // 如果还需要水平居中，可以添加此行
        ) {

            userDisplayInfo.avatar?.let { avatarImage ->
                AndroidView(
                    factory = { avatarImage },
                    modifier = Modifier
                        .height(96.dp)
                        .width(96.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val username = "${userDisplayInfo.name ?: LCallManager.convertToBase58UserName(userId)}"

            Text(
                text = StringUtil.getShowUserName(username, 14),
                color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            statusTip?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusTip,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }

    }

}