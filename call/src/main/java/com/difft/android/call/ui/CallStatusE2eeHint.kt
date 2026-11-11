package com.difft.android.call.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.isAnimationsDisabled
import com.difft.android.call.R






@Composable
internal fun AlternatingCallStatusText(
    primaryLabel: String,
    shouldAnimate: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = DifftTheme.typography.p3.copy(color = DifftTheme.colors.textPrimary)
    if (!shouldAnimate) {
        EncryptedStatusRow(modifier = modifier, textStyle = textStyle, testTag = "call_topbar_status_text")
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "callStatusEncryptionCrossfade")
    
    val primaryAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                1f at 0; 1f at 1376; 0f at 1600; 0f at 2976; 1f at 3200
            },
        ),
        label = "callStatusEncryptionCrossfadeValue",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            modifier = Modifier.testTag("call_topbar_status_text").alpha(primaryAlpha),
            text = primaryLabel,
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        EncryptedStatusRow(
            modifier = Modifier.alpha(1f - primaryAlpha),
            textStyle = textStyle,
            testTag = "call_topbar_status_text_encrypted",
        )
    }
}





@Composable
internal fun EncryptedStatusRow(
    textStyle: TextStyle,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        
        Icon(
            imageVector = ImageVector.vectorResource(id = com.difft.android.base.R.drawable.base_tabler_lock),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            modifier = Modifier.testTag(testTag),
            text = ResUtils.getString(R.string.call_status_encrypted),
            style = textStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@Composable
internal fun rememberShouldAnimateCallStatus(isInPipMode: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    
    val reducedMotion = remember { isAnimationsDisabled(context) }
    return isForeground && !isInPipMode && !reducedMotion
}
