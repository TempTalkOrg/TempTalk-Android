package com.difft.android.call.ui.incoming

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

object IncomingCallControlsDefaults {
    
    val HitSize = 72.dp
    val IconSize = 64.dp
    const val DISABLED_ALPHA = 0.5f

    const val REJECT_TEST_TAG = "incoming_reject_button"
    const val ACCEPT_TEST_TAG = "incoming_accept_button"
}






@Composable
fun IncomingCallControls(
    rejectIcon: Painter,
    acceptIcon: Painter,
    onReject: () -> Unit,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IncomingCallControlsDefaults.HitSize)
            .alpha(if (enabled) 1f else IncomingCallControlsDefaults.DISABLED_ALPHA),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TapButton(rejectIcon, enabled, IncomingCallControlsDefaults.REJECT_TEST_TAG, onReject)
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TapButton(acceptIcon, enabled, IncomingCallControlsDefaults.ACCEPT_TEST_TAG, onAccept)
        }
    }
}

@Composable
private fun TapButton(icon: Painter, enabled: Boolean, testTag: String, onClick: () -> Unit) {
    
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(IncomingCallControlsDefaults.HitSize)
            .clip(CircleShape)
            .testTag(testTag)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Image(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(IncomingCallControlsDefaults.IconSize),
        )
    }
}
