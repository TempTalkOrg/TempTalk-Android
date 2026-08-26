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

/**
 * Mic / camera toggle-tap permission flow (spec: unified LiveKit media permission handling).
 *
 * One tap resolves to exactly ONE of: run the toggle, fire the system permission dialog,
 * or show the app Settings guide — the system dialog and the app dialog can never stack.
 * The system request is only ever fired from here (user tap), so a Granted result always
 * carries explicit user intent to enable — never auto-enable from any other path.
 */
internal fun LCallActivity.onMediaControlTapped(permission: CallMediaPermission) {
    // Turning OFF never consults permission state (spec: close never checks permission), so a
    // grant lost out-of-band can never trap an enabled toggle behind the permission flow.
    if (isMediaControlEnabled(permission)) {
        toggleMediaControl(permission)
        return
    }
    // One permission flow per tap: while a system request is launching/up, further taps are
    // ignored. Without this, the requested-before flag (persisted BEFORE launch) makes a rapid
    // second tap resolve PermanentlyDenied and stack the Settings guide on the system dialog.
    if (viewModel.isRequestingPermission()) {
        L.i { "[Call] LCallActivity media tap ignored, permission request in flight" }
        return
    }
    when (viewModel.mediaPermissions.decideTapAction(this, permission)) {
        MediaPermissionTapAction.Proceed -> toggleMediaControl(permission)

        MediaPermissionTapAction.LaunchSystemRequest -> {
            viewModel.callUiController.setRequestPermissionStatus(true)
            lifecycleScope.launch {
                // Persist-before-launch ordering kept, but off the main thread:
                // commit=true blocks its caller on the disk write.
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

/** System permission dialog result for [permission], routed from the launchers in [LCallActivity]. */
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
        // The request was fired by a tap meaning "turn it on", and the toggle was
        // necessarily off (it could not have been enabled without the permission).
        enableMediaControl(permission)
    }
    // Denied / PermanentlyDenied: no dialog here — the tap already showed the system
    // dialog, and the badge (mic) keeps signalling. The NEXT tap routes to the guide.
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
    // Foreground service type must include the freshly granted capability BEFORE the
    // track starts, or Android 14+ rejects the microphone/camera FGS usage.
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
    // Body copy embeds the launcher label ("Quicall" / "Quicall Test") per the Figma spec.
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
