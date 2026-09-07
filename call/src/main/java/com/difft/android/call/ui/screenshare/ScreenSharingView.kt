package com.difft.android.call.ui.screenshare

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.OrientationPolicy
import com.difft.android.call.LCallActivity
import com.difft.android.call.ui.video.ScaleType
import com.difft.android.call.ui.video.VideoItemTrackSelector
import com.difft.android.call.ui.video.ViewType
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track

// Screen sharing requires landscape on phones; restored on dispose.
@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun ScreenSharingView(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    reconnectCount: Int = 0,
){
    val context = LocalContext.current
    val activity = context.getActivity() ?: return
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        hideSystemBars(activity.window)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        room.localParticipant.deviceRotation = 90
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                show(WindowInsetsCompat.Type.statusBars())
                hide(WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            // Restore via the policy itself and hand ownership back: the bucket may have
            // changed while the landscape lock was held (fold/unfold mid-share), and a
            // hand-written restore would leave policyAppliedOrientation stale, silencing
            // every later re-apply for the rest of the call.
            val restored = OrientationPolicy.applyTo(activity)
            (activity as? LCallActivity)?.policyAppliedOrientation = restored
            room.localParticipant.deviceRotation =
                if (restored == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) 0 else null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .clip(shape = RoundedCornerShape(8.dp))
            .background(DifftTheme.colors.background),
        contentAlignment = Alignment.Center )
    {
        VideoItemTrackSelector(
            coroutineScope = coroutineScope,
            room = room,
            participant = participant,
            sourceType = Track.Source.SCREEN_SHARE,
            scaleType = ScaleType.FitInside,
            viewType = ViewType.ScreenShare,
            draggable = true,
            reconnectCount = reconnectCount,
        )

    }

}

fun Context.getActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

fun hideSystemBars(window: Window) {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

