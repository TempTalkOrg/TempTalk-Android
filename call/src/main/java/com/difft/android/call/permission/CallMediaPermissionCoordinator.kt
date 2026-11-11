package com.difft.android.call.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow










enum class MediaPermissionState {
    
    NotDetermined,

    
    Denied,

    
    PermanentlyDenied,

    Granted,
    ;

    



    val showsBadge: Boolean get() = this == Denied || this == PermanentlyDenied
}


enum class MediaPermissionTapAction {
    
    Proceed,

    
    LaunchSystemRequest,

    
    ShowSettingsGuide,
}


enum class CallMediaPermission(val manifestPermission: String) {
    Microphone(Manifest.permission.RECORD_AUDIO),
    Camera(Manifest.permission.CAMERA),
}










class CallMediaPermissionCoordinator(
    private val userManager: UserManager,
) {

    private val _micState = MutableStateFlow(MediaPermissionState.NotDetermined)

    
    val micState: StateFlow<MediaPermissionState> = _micState.asStateFlow()

    private val _cameraState = MutableStateFlow(MediaPermissionState.NotDetermined)

    
    val cameraState: StateFlow<MediaPermissionState> = _cameraState.asStateFlow()

    
    fun refresh(activity: Activity) {
        val mic = resolve(activity, CallMediaPermission.Microphone)
        val camera = resolve(activity, CallMediaPermission.Camera)
        if (mic != _micState.value || camera != _cameraState.value) {
            L.i { "[Call] MediaPermissionCoordinator refresh mic=$mic camera=$camera" }
        }
        _micState.value = mic
        _cameraState.value = camera
    }

    
    fun decideTapAction(activity: Activity, permission: CallMediaPermission): MediaPermissionTapAction {
        val state = resolve(activity, permission)
        return when (state) {
            MediaPermissionState.Granted -> MediaPermissionTapAction.Proceed
            MediaPermissionState.NotDetermined,
            MediaPermissionState.Denied -> MediaPermissionTapAction.LaunchSystemRequest
            MediaPermissionState.PermanentlyDenied -> MediaPermissionTapAction.ShowSettingsGuide
        }
    }

    




    fun onSystemRequestLaunched(permission: CallMediaPermission) {
        L.i { "[Call] MediaPermissionCoordinator system request launched for $permission" }
        when (permission) {
            CallMediaPermission.Microphone -> userManager.update(commit = true) { callMicPermissionRequested = true }
            CallMediaPermission.Camera -> userManager.update(commit = true) { callCameraPermissionRequested = true }
        }
    }

    
    fun onSystemRequestResult(activity: Activity, permission: CallMediaPermission, granted: Boolean) {
        L.i { "[Call] MediaPermissionCoordinator request result $permission granted=$granted" }
        refresh(activity)
    }

    private fun resolve(activity: Activity, permission: CallMediaPermission): MediaPermissionState {
        val granted = ContextCompat.checkSelfPermission(activity, permission.manifestPermission) ==
            PackageManager.PERMISSION_GRANTED
        val rationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission.manifestPermission)
        val requestedBefore = when (permission) {
            CallMediaPermission.Microphone -> userManager.getUserData()?.callMicPermissionRequested ?: false
            CallMediaPermission.Camera -> userManager.getUserData()?.callCameraPermissionRequested ?: false
        }
        return resolveState(granted = granted, rationale = rationale, requestedBefore = requestedBefore)
    }

    companion object {
        

        internal fun resolveState(
            granted: Boolean,
            rationale: Boolean,
            requestedBefore: Boolean,
        ): MediaPermissionState = when {
            granted -> MediaPermissionState.Granted
            rationale -> MediaPermissionState.Denied
            requestedBefore -> MediaPermissionState.PermanentlyDenied
            else -> MediaPermissionState.NotDetermined
        }
    }
}
