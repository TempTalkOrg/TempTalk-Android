package com.difft.android.call.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.utils.WindowSizeClassUtil
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track

@Composable
fun ScreenSharingView(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
){
    val context = LocalContext.current
    val activity = context.getActivity() ?: return
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        hideSystemBars(activity.window)
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        room.localParticipant.deviceRotation = 90
        onDispose {
            showSystemBars(activity.window)
            // 仅在非宽屏设备上强制竖屏，宽屏设备允许自由旋转
            if (!WindowSizeClassUtil.shouldUseDualPaneLayout(activity) && (activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            room.localParticipant.deviceRotation = null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .clip(shape = RoundedCornerShape(8.dp))
            .background(colorResource(id = com.difft.android.base.R.color.bg1_night)),
        contentAlignment = Alignment.Center )
    {
        VideoItemTrackSelector(
            coroutineScope = coroutineScope,
            room = room,
            participant = participant,
            // Specifies this view should display screen sharing content
            sourceType = Track.Source.SCREEN_SHARE,
            scaleType = ScaleType.FitInside,
            viewType = ViewType.ScreenShare,
            draggable = true,
        )

    }

}

fun Context.getActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.getActivity()
    else -> null
}

fun hideSystemBars(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}

fun showSystemBars(window: Window) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.let { controller ->
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION") window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
