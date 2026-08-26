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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.isAnimationsDisabled
import com.difft.android.call.R

/**
 * Cross-fades [primaryLabel] ("等待接听…", the only remaining title-borne status — connection
 * statuses live in [CallStatusNotificationBar]) with the E2EE-encrypted label. The crossfade is
 * an E2EE-hints product requirement (PR #1125, Mac parity): the title fits two lines, so status
 * and encrypted hint timeshare one of them. Mounts `rememberInfiniteTransition` ONLY while
 * [shouldAnimate] is true — an unconditioned infinite transition drives main-thread
 * recomposition for the entire call, the exact bug class that guard exists for. When not
 * animating, renders a single static encrypted row — the reduced-motion fallback AND the
 * background/PiP fallback (deliberately the same single branch).
 */
@Composable
internal fun AlternatingCallStatusText(
    primaryLabel: String,
    shouldAnimate: Boolean,
    modifier: Modifier = Modifier,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, color = DifftTheme.colors.textPrimary)
    if (!shouldAnimate) {
        EncryptedStatusRow(modifier = modifier, textStyle = textStyle, testTag = "call_topbar_status_text")
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "callStatusEncryptionCrossfade")
    // 0-43% primary visible, 43-50% fade out, 50-93% encrypted visible, 93-100% fade back in.
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

/**
 * Lock icon + "End-to-end encrypted" text — matches `ConnectedStatusContent`'s lock-icon row.
 * Also shown statically in the title while connection statuses render in the floating bar.
 */
@Composable
internal fun EncryptedStatusRow(
    textStyle: TextStyle,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        // Literal Color.White (not DifftTheme.colors.*) matches every other icon/text in this
        // forced-dark-theme call surface (CallContent.kt:90).
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

/** Foreground + not-PiP + animations-enabled gate for the E2EE crossfade. */
@Composable
internal fun rememberShouldAnimateCallStatus(isInPipMode: Boolean): Boolean {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val isForeground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    // Read once per composition entry, not per-frame — Settings reads are cheap but not free.
    val reducedMotion = remember { isAnimationsDisabled(context) }
    return isForeground && !isInPipMode && !reducedMotion
}
