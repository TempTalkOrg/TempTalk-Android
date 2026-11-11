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


@Composable
fun ScreenSharingView(
    room: Room,
    participant: Participant,
    modifier: Modifier = Modifier,
    reconnectCount: Int = 0,
    
    zoomResetKey: Any? = null,
    
    zoomResetRequest: Int = 0,
    scaleType: ScaleType = ScaleType.FitInside,
    
    onFrameSize: FrameSizeListener? = null,
    panSlack: Offset = Offset.Zero,
){
    val context = LocalContext.current
    val activity = context.getActivity() ?: return
    val coroutineScope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        
        val callActivity = activity as? LCallActivity
        callActivity?.screenShareOrientationActive = true
        val policyWritten = applyScreenShareOrientation(activity, halfOpen = callActivity?.foldHalfOpen == true)
        callActivity?.policyAppliedOrientation = policyWritten
        
        
        room.localParticipant.deviceRotation = null
        onDispose {
            callActivity?.screenShareOrientationActive = false
            
            
            
            
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
            zoomResetRequest = zoomResetRequest,
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


internal val LocalCallStatusBarHidden = compositionLocalOf<Boolean?> { null }






@Composable
internal fun isCallImmersive(): Boolean =
    LocalCallStatusBarHidden.current ?: ((LocalContext.current.getActivity() as? LCallActivity)?.screenShareOrientationActive == true)

