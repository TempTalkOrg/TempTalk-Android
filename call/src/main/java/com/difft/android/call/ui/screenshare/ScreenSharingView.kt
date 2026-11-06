package com.difft.android.call.ui.screenshare

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.OrientationPolicy
import com.difft.android.call.LCallActivity
import com.difft.android.call.ui.video.FrameSizeListener
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track

// Screen sharing turns phones landscape (see applyScreenShareOrientation); restored on dispose.
@Composable
fun ScreenSharingView(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    reconnectCount: Int = 0,
    // A change resets the pinch, e.g. entering picture in picture, which shows the whole share.
    zoomResetKey: Any? = null,
    scaleType: ScaleType = ScaleType.FitInside,
    // Rotated frame sizes of the share, for the call layout.
    onFrameSize: FrameSizeListener? = null,
    panSlack: Offset = Offset.Zero,
){
    val context = LocalContext.current
    val activity = context.getActivity() ?: return
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        // The status bar is the call page's (CallContent); the window is edge to edge for the call.
        val callActivity = activity as? LCallActivity
        callActivity?.screenShareOrientationActive = true
        val policyWritten = applyScreenShareOrientation(activity, halfOpen = callActivity?.foldHalfOpen == true)
        callActivity?.policyAppliedOrientation = policyWritten
        // Sensor landscape can sit at 90 or 270 and a large display rotates freely, so the camera
        // reads the display's real rotation per frame; a fixed 90 renders upside down at 270.
        room.localParticipant.deviceRotation = null
        onDispose {
            callActivity?.screenShareOrientationActive = false
            // Restore via the policy itself and hand ownership back: the bucket may have
            // changed while the landscape lock was held (fold/unfold mid-share), and a
            // hand-written restore would leave policyAppliedOrientation stale, silencing
            // every later re-apply for the rest of the call.
            val restored = OrientationPolicy.applyTo(activity)
            callActivity?.policyAppliedOrientation = restored
            room.localParticipant.deviceRotation =
                if (restored == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) 0 else null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(DifftTheme.colors.background),
        contentAlignment = Alignment.Center )
    {
        VideoItemTrackSelector(
            coroutineScope = coroutineScope,
            room = room,
            participant = participant,
            sourceType = Track.Source.SCREEN_SHARE,
            scaleType = scaleType,
            viewType = ViewType.ScreenShare,
            draggable = true,
            reconnectCount = reconnectCount,
            zoomResetKey = zoomResetKey,
            onFrameSize = onFrameSize,
            panSlack = panSlack,
        )

    }

}

fun Context.getActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

/** Whether the call page hides the status bar now (CallContent); null outside the call page. */
internal val LocalCallStatusBarHidden = compositionLocalOf<Boolean?> { null }

/**
 * Whether the call page holds its window without a status bar: controls hidden, the tabletop
 * posture or a share. The call's bottom sheets pass it as `hideSystemBars` so opening one does not
 * bring the status bar back (#1451). Outside the page, whether a share holds the window immersive.
 */
@Composable
internal fun isCallImmersive(): Boolean =
    LocalCallStatusBarHidden.current ?: ((LocalContext.current.getActivity() as? LCallActivity)?.screenShareOrientationActive == true)

