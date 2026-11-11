package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.core.CallUiController
import com.difft.android.call.network.resolveBadge


private val BADGE_BOX = 20.dp
private val BADGE_ICON = 14.dp
private val BADGE_CORNER = 4.dp















@Composable
internal fun ParticipantWeakNetworkBadge(
    controller: CallUiController,
    localIdentity: String,
    identity: String,
    participantCount: Int,
    modifier: Modifier = Modifier,
) {
    val view by controller.networkQuality.collectAsState()
    if (!resolveBadge(view, localIdentity, participantCount, identity)) return

    Box(
        modifier = modifier
            .testTag("call_weak_network_badge")
            .size(BADGE_BOX)
            .background(DifftTheme.colors.backgroundPagePrimary, RoundedCornerShape(BADGE_CORNER)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = R.drawable.call_ic_wifi_x),
            
            
            contentDescription = stringResource(id = R.string.call_other_network_poor_tip),
            
            tint = Color.White,
            modifier = Modifier.size(BADGE_ICON),
        )
    }
}
