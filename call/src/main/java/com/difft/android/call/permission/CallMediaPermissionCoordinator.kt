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

/**
 * Ambient permission state for a call media capability.
 *
 * Richer than [com.difft.android.base.android.permission.PermissionUtil.PermissionState]
 * (which classifies one request result): this is resolvable at ANY time, before any request,
 * because the mic button badge must reflect denial on call entry. `rationale == false` alone
 * cannot separate [NotDetermined] from [PermanentlyDenied] — the persisted
 * "has the app ever fired the system request" flag breaks that tie.
 */
enum class MediaPermissionState {
    /** Not granted and the app never fired the system request. No badge. */
    NotDetermined,

    /** Denied but the system still allows another request (rationale == true). Badge on mic. */
    Denied,

    /** Denied and the system no longer shows the request dialog. Badge on mic; tap → Settings guide. */
    PermanentlyDenied,

    Granted,
    ;

    /**
     * Whether the mic toggle renders the red denial badge in this state.
     * Camera never renders a badge regardless of this value (dialog-only by spec).
     */
    val showsBadge: Boolean get() = this == Denied || this == PermanentlyDenied
}

/** What a user tap on a media toggle should do, given the current permission state. */
enum class MediaPermissionTapAction {
    /** Permission granted — run the toggle. */
    Proceed,

    /** Fire the system permission dialog ([NotDetermined] / [Denied]). */
    LaunchSystemRequest,

    /** Show the app "Cancel / Go to Settings" dialog ([PermanentlyDenied]). */
    ShowSettingsGuide,
}

/** The two runtime permissions gating local call media publication. */
enum class CallMediaPermission(val manifestPermission: String) {
    Microphone(Manifest.permission.RECORD_AUDIO),
    Camera(Manifest.permission.CAMERA),
}

/**
 * Single decision point for call media (mic / camera) permission interactions, per the
 * unified media-permission spec: join never prompts, only a user tap may fire the system
 * dialog or the Settings guide, close never checks permission, and returning from Settings
 * refreshes state but never auto-enables.
 *
 * State resolution stays here; UI side effects (system request launcher, dialogs, badge)
 * stay in the Activity/Compose layer, and the LiveKit last-line gate stays in
 * [com.difft.android.call.media.CallMediaController].
 */
class CallMediaPermissionCoordinator(
    private val userManager: UserManager,
) {

    private val _micState = MutableStateFlow(MediaPermissionState.NotDetermined)

    /** Drives the mic-button badge: badge visible when Denied / PermanentlyDenied. */
    val micState: StateFlow<MediaPermissionState> = _micState.asStateFlow()

    private val _cameraState = MutableStateFlow(MediaPermissionState.NotDetermined)

    /** Camera is dialog-only by spec — no badge may ever be derived from this state. */
    val cameraState: StateFlow<MediaPermissionState> = _cameraState.asStateFlow()

    /** Re-reads both permissions. Call on entry and on every onResume (Settings return). */
    fun refresh(activity: Activity) {
        val mic = resolve(activity, CallMediaPermission.Microphone)
        val camera = resolve(activity, CallMediaPermission.Camera)
        if (mic != _micState.value || camera != _cameraState.value) {
            L.i { "[Call] MediaPermissionCoordinator refresh mic=$mic camera=$camera" }
        }
        _micState.value = mic
        _cameraState.value = camera
    }

    /** Routes a user tap. Resolves live (not from the cached flows) so a stale badge can't misroute. */
    fun decideTapAction(activity: Activity, permission: CallMediaPermission): MediaPermissionTapAction {
        val state = resolve(activity, permission)
        return when (state) {
            MediaPermissionState.Granted -> MediaPermissionTapAction.Proceed
            MediaPermissionState.NotDetermined,
            MediaPermissionState.Denied -> MediaPermissionTapAction.LaunchSystemRequest
            MediaPermissionState.PermanentlyDenied -> MediaPermissionTapAction.ShowSettingsGuide
        }
    }

    /**
     * Persist "the system request has been fired" BEFORE launching, so a process death while
     * the dialog is up still leaves the disambiguation flag behind. `commit = true` because the
     * default fire-and-forget write gives no durability across that window.
     */
    fun onSystemRequestLaunched(permission: CallMediaPermission) {
        L.i { "[Call] MediaPermissionCoordinator system request launched for $permission" }
        when (permission) {
            CallMediaPermission.Microphone -> userManager.update(commit = true) { callMicPermissionRequested = true }
            CallMediaPermission.Camera -> userManager.update(commit = true) { callCameraPermissionRequested = true }
        }
    }

    /** Result callback of the system dialog — state may have changed either way. */
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
        /**
         * Pure state mapping (spec "Android 权限状态映射"):
         * granted → Granted; rationale → Denied (system will still show the dialog);
         * asked before without rationale → PermanentlyDenied; otherwise NotDetermined.
         */
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
