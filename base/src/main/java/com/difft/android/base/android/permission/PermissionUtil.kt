package com.difft.android.base.android.permission

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application

/** Three-state media read access, mirroring Signal StorageUtil.canReadAny/canOnlyReadSelected. */
enum class MediaAccessState { FULL, PARTIAL, NONE }

/**
 * Why a media read that ALREADY FAILED failed, as a permission-layer classification.
 *
 * Complements [MediaAccessState]: that enum answers "what is granted right now", this one answers
 * "the open failed — what does the permission layer say about it". [PermissionUtil.PermissionState]
 * answers a third, unrelated question (the outcome of one permission request), so the three must
 * stay separate.
 *
 * [GRANTED_BUT_UNREADABLE] is the state the permission layer previously could not express: it has
 * only ever modelled "nothing granted" (request flow) and "permanently denied" (Settings flow),
 * while full access demonstrably coexists with failing reads.
 */
enum class MediaReadDenialKind { PERMISSION_MISSING, PARTIAL_SELECTION, GRANTED_BUT_UNREADABLE, NOT_MEDIA_SCOPED }

/** A classified read denial together with the throwable that proved the read had already failed. */
data class MediaReadDenial(val kind: MediaReadDenialKind, val cause: Throwable?)

object PermissionUtil {

    val picturePermissions = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            arrayOf(
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        else -> {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    val callPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA
    )

    @JvmInline
    value class Permission(val result: ActivityResultLauncher<Array<String>>)

    sealed class PermissionState {
        object Granted : PermissionState()
        object Denied : PermissionState()
        object PermanentlyDenied : PermissionState()
    }

    @VisibleForTesting
    internal fun getPermissionState(
        activity: Activity?,
        result: Map<String, @JvmSuppressWildcards Boolean>
    ): PermissionState {
        var deniedList: List<String> = result.filter {
            it.value.not()
        }.map {
            it.key
        }

        // Android 14+ partial access ("Select photos"): the system grants
        // READ_MEDIA_VISUAL_USER_SELECTED while keeping full IMAGES/VIDEO denied.
        // Media selection is fully usable in this state, so those two denials
        // must not count — otherwise partial grants escalate to PermanentlyDenied
        // after two picker sessions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            deniedList.isNotEmpty() &&
            ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            deniedList = deniedList - setOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
        }

        // Symmetric guard: any granted real media read permission means media is
        // usable, so a VISUAL_USER_SELECTED denial must not count. Defends against
        // an OEM that reports IMAGES/VIDEO granted but VISUAL denied.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            deniedList.contains(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            (ContextCompat.checkSelfPermission(
                application,
                Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    application,
                    Manifest.permission.READ_MEDIA_VIDEO
                ) == PackageManager.PERMISSION_GRANTED)
        ) {
            deniedList = deniedList - Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }

        var state = when (deniedList.isEmpty()) {
            true -> PermissionState.Granted
            false -> PermissionState.Denied
        }

        if (state == PermissionState.Denied) {
            val permanentlyMappedList = deniedList.map {
                activity?.let { activity ->
                    shouldShowRequestPermissionRationale(activity, it)
                }
            }

            if (permanentlyMappedList.contains(false)) {
                state = PermissionState.PermanentlyDenied
            }
        }
        return state
    }

    fun Fragment.registerPermission(onPermissionResult: (PermissionState) -> Unit): Permission {
        return Permission(
            this.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                onPermissionResult(getPermissionState(activity, it))
            }
        )
    }

    fun AppCompatActivity.registerPermission(onPermissionResult: (PermissionState) -> Unit): Permission {
        return Permission(
            this.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                onPermissionResult(getPermissionState(this, it))
            }
        )
    }

    fun Permission.launchSinglePermission(permission: String) {
        this.result.launch(arrayOf(permission))
    }

    fun Permission.launchMultiplePermission(permissionList: Array<String>) {
        this.result.launch(permissionList)
    }

    fun launchSettings(context: Context) {
        try {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", application.packageName, null)
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                .also { context.startActivity(it) }
        } catch (e: Exception) {
            L.w { "[PermissionUtil] error: ${e.stackTraceToString()}" }
            L.i { "[PermissionUtil] launchSettings fail:" + e.stackTraceToString() }
        }
    }

    fun arePermissionsGranted(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Synchronous pre-check of current media read access (does NOT request).
     * FULL    = full library readable (IMAGES/VIDEO granted, or READ_EXTERNAL_STORAGE < 33).
     * PARTIAL = 34+ "Select photos": only VISUAL_USER_SELECTED granted, IMAGES/VIDEO denied.
     * NONE    = nothing readable → caller must request.
     */
    @JvmStatic
    fun getMediaAccessState(context: Context = application): MediaAccessState = when {
        isFullMediaGranted(context) -> MediaAccessState.FULL
        isPartialMediaGranted(context) -> MediaAccessState.PARTIAL
        else -> MediaAccessState.NONE
    }

    // PARTIAL: 34+ && VISUAL granted && neither IMAGES nor VIDEO granted (Signal canOnlyReadSelected)
    private fun isPartialMediaGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            isGranted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !hasAnyPermission(
                context,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            )

    // FULL: any "real" read grant for the current SDK tier
    private fun isFullMediaGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasAnyPermission(
                context,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            )
        else -> isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE) // 26–32
    }

    private fun hasAnyPermission(context: Context, permissions: Array<String>): Boolean =
        permissions.any { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * Classifies a read failure that HAS ALREADY BEEN OBSERVED. [cause] is the throwable thrown by
     * the failed open; requiring it in the signature makes that precondition part of the contract.
     *
     * MUST NOT be used as a pre-check. Permission state is not a readability predicate — the whole
     * point of this classification is that FULL access coexists with failing reads. Readability can
     * only be established by actually opening the item.
     *
     * Side-effect free: reads permission state, performs no IO, writes no log. The caller that owns
     * the failure log format is the single owner of the log line.
     *
     * [MediaReadDenialKind.NOT_MEDIA_SCOPED] is mandatory, not defensive: without it a sandbox path
     * whose file is genuinely gone would be reported as a permission problem while access is FULL,
     * which is exactly the mis-attribution this classification exists to prevent.
     */
    @JvmStatic
    fun classifyReadDenial(
        uri: Uri,
        cause: Throwable?,
        context: Context = application
    ): MediaReadDenial {
        if (!isMediaStoreUri(uri)) return MediaReadDenial(MediaReadDenialKind.NOT_MEDIA_SCOPED, cause)
        val kind = when (getMediaAccessState(context)) {
            MediaAccessState.NONE -> MediaReadDenialKind.PERMISSION_MISSING
            MediaAccessState.PARTIAL -> MediaReadDenialKind.PARTIAL_SELECTION
            MediaAccessState.FULL -> MediaReadDenialKind.GRANTED_BUT_UNREADABLE
        }
        return MediaReadDenial(kind, cause)
    }

    // MediaStore authority, including the cross-profile "<userId>@media" form.
    private fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return authority == MediaStore.AUTHORITY || authority.endsWith("@${MediaStore.AUTHORITY}")
    }

    /**
     * Media entry gate: if media is already usable (full or partial), open directly without prompting;
     * otherwise fire the permission request. Removes the "re-prompt on every click under partial
     * access" bug by pre-checking access before launching the system request.
     */
    fun Permission.launchMediaSelectionOrOpen(context: Context = application, onUsable: () -> Unit) {
        if (getMediaAccessState(context) != MediaAccessState.NONE) {
            onUsable()
        } else {
            L.i { "[MediaAccess] request media permission (state=NONE) sdk=${Build.VERSION.SDK_INT}" }
            launchMultiplePermission(picturePermissions)
        }
    }
}