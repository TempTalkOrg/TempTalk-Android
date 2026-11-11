package com.difft.android.call

import androidx.lifecycle.lifecycleScope
import com.difft.android.base.android.permission.PermissionUtil
import com.difft.android.base.android.permission.PermissionUtil.launchSinglePermission
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.call.permission.CallMediaPermission
import com.difft.android.call.permission.MediaPermissionTapAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext









internal fun LCallActivity.onMediaControlTapped(permission: CallMediaPermission) {
    
    
    if (isMediaControlEnabled(permission)) {
        toggleMediaControl(permission)
        return
    }
    
    
    
    if (viewModel.isRequestingPermission()) {
        L.i { "[Call] LCallActivity media tap ignored, permission request in flight" }
        return
    }
    when (viewModel.mediaPermissions.decideTapAction(this, permission)) {
        MediaPermissionTapAction.Proceed -> toggleMediaControl(permission)

        MediaPermissionTapAction.LaunchSystemRequest -> {
            viewModel.callUiController.setRequestPermissionStatus(true)
            lifecycleScope.launch {
                
                
                withContext(Dispatchers.IO) {
                    viewModel.mediaPermissions.onSystemRequestLaunched(permission)
                }
                mediaPermissionLauncher(permission).launchSinglePermission(permission.manifestPermission)
            }
        }

        MediaPermissionTapAction.ShowSettingsGuide -> showMediaPermissionSettingsDialog(permission)
    }
}

private fun LCallActivity.isMediaControlEnabled(permission: CallMediaPermission): Boolean =
    when (permission) {
        CallMediaPermission.Microphone -> viewModel.micEnabled.value
        CallMediaPermission.Camera -> viewModel.cameraEnabled.value
    }


internal fun LCallActivity.onMediaPermissionResult(
    permission: CallMediaPermission,
    state: PermissionUtil.PermissionState,
) {
    L.i { "[Call] LCallActivity media permission result $permission state=$state" }
    viewModel.callUiController.setRequestPermissionStatus(false)
    viewModel.mediaPermissions.onSystemRequestResult(
        activity = this,
        permission = permission,
        granted = state == PermissionUtil.PermissionState.Granted,
    )
    if (state == PermissionUtil.PermissionState.Granted) {
        
        
        enableMediaControl(permission)
    }
    
    
}

private fun LCallActivity.toggleMediaControl(permission: CallMediaPermission) {
    when (permission) {
        CallMediaPermission.Microphone -> {
            updateForegroundServiceType()
            viewModel.setMicEnabled(!viewModel.micEnabled.value)
        }
        CallMediaPermission.Camera -> {
            updateForegroundServiceType()
            viewModel.setCameraEnabled(!viewModel.cameraEnabled.value)
        }
    }
}

private fun LCallActivity.enableMediaControl(permission: CallMediaPermission) {
    
    
    updateForegroundServiceType()
    when (permission) {
        CallMediaPermission.Microphone -> viewModel.setMicEnabled(true)
        CallMediaPermission.Camera -> viewModel.setCameraEnabled(true)
    }
}

private fun LCallActivity.showMediaPermissionSettingsDialog(permission: CallMediaPermission) {
    val (title, message) = when (permission) {
        CallMediaPermission.Microphone ->
            R.string.call_microphone_permission_deny_title to R.string.call_microphone_permission_deny_content
        CallMediaPermission.Camera ->
            R.string.call_camera_permission_deny_title to R.string.call_camera_permission_deny_content
    }
    
    val appName = PackageUtil.getAppName().orEmpty()
    ComposeDialogManager.showMessageDialog(
        context = this,
        cancelable = true,
        title = getString(title),
        message = getString(message, appName),
        confirmText = getString(R.string.call_permission_button_settings),
        cancelText = getString(R.string.call_permission_button_setting_cancel),
        onConfirm = {
            PermissionUtil.launchSettings(this)
            viewModel.callUiController.setRequestPermissionStatus(true)
        }
    )
}
