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


enum class MediaAccessState { FULL, PARTIAL, NONE }






enum class MediaReadDenialKind { PERMISSION_MISSING, PARTIAL_SELECTION, GRANTED_BUT_UNREADABLE, NOT_MEDIA_SCOPED }


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

    





    @JvmStatic
    fun getMediaAccessState(context: Context = application): MediaAccessState = when {
        isFullMediaGranted(context) -> MediaAccessState.FULL
        isPartialMediaGranted(context) -> MediaAccessState.PARTIAL
        else -> MediaAccessState.NONE
    }

    
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

    
    private fun isFullMediaGranted(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            hasAnyPermission(
                context,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                )
            )
        else -> isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE) 
    }

    private fun hasAnyPermission(context: Context, permissions: Array<String>): Boolean =
        permissions.any { isGranted(context, it) }

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    





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

    
    private fun isMediaStoreUri(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        val authority = uri.authority ?: return false
        return authority == MediaStore.AUTHORITY || authority.endsWith("@${MediaStore.AUTHORITY}")
    }

    
    fun Permission.launchMediaSelectionOrOpen(context: Context = application, onUsable: () -> Unit) {
        if (getMediaAccessState(context) != MediaAccessState.NONE) {
            onUsable()
        } else {
            L.i { "[MediaAccess] request media permission (state=NONE) sdk=${Build.VERSION.SDK_INT}" }
            launchMultiplePermission(picturePermissions)
        }
    }
}