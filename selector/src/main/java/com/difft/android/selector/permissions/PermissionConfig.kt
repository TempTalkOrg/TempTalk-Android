package com.difft.android.selector.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.utils.SdkVersionUtils

object PermissionConfig {

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @JvmField
    val READ_MEDIA_AUDIO: String = Manifest.permission.READ_MEDIA_AUDIO

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @JvmField
    val READ_MEDIA_IMAGES: String = Manifest.permission.READ_MEDIA_IMAGES

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @JvmField
    val READ_MEDIA_VIDEO: String = Manifest.permission.READ_MEDIA_VIDEO

    @RequiresApi(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @JvmField
    val READ_MEDIA_VISUAL_USER_SELECTED: String = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED

    @JvmField
    val READ_EXTERNAL_STORAGE: String = Manifest.permission.READ_EXTERNAL_STORAGE

    @JvmField
    val WRITE_EXTERNAL_STORAGE: String = Manifest.permission.WRITE_EXTERNAL_STORAGE

    @JvmField
    var CURRENT_REQUEST_PERMISSION = arrayOf<String>()

    @JvmField
    val CAMERA = arrayOf(Manifest.permission.CAMERA)

    @JvmStatic
    fun getReadPermissionArray(context: Context, chooseMode: Int): Array<String> {
        if (SdkVersionUtils.isUPSIDE_DOWN_CAKE()) {
            val targetSdkVersion = context.applicationInfo.targetSdkVersion
            return if (chooseMode == SelectMimeType.ofImage()) {
                if (targetSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    arrayOf(READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_IMAGES)
                } else if (targetSdkVersion == Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(READ_MEDIA_IMAGES)
                } else {
                    arrayOf(READ_EXTERNAL_STORAGE)
                }
            } else if (chooseMode == SelectMimeType.ofVideo()) {
                if (targetSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    arrayOf(READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_VIDEO)
                } else if (targetSdkVersion == Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(READ_MEDIA_VIDEO)
                } else {
                    arrayOf(READ_EXTERNAL_STORAGE)
                }
            } else if (chooseMode == SelectMimeType.ofAudio()) {
                if (targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) arrayOf(READ_MEDIA_AUDIO)
                else arrayOf(READ_EXTERNAL_STORAGE)
            } else {
                if (targetSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    arrayOf(READ_MEDIA_VISUAL_USER_SELECTED, READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
                } else if (targetSdkVersion == Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
                } else {
                    arrayOf(READ_EXTERNAL_STORAGE)
                }
            }
        } else if (SdkVersionUtils.isTIRAMISU()) {
            val targetSdkVersion = context.applicationInfo.targetSdkVersion
            return if (chooseMode == SelectMimeType.ofImage()) {
                if (targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) arrayOf(READ_MEDIA_IMAGES)
                else arrayOf(READ_EXTERNAL_STORAGE)
            } else if (chooseMode == SelectMimeType.ofVideo()) {
                if (targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) arrayOf(READ_MEDIA_VIDEO)
                else arrayOf(READ_EXTERNAL_STORAGE)
            } else if (chooseMode == SelectMimeType.ofAudio()) {
                if (targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) arrayOf(READ_MEDIA_AUDIO)
                else arrayOf(READ_EXTERNAL_STORAGE)
            } else {
                if (targetSdkVersion >= Build.VERSION_CODES.TIRAMISU) arrayOf(READ_MEDIA_IMAGES, READ_MEDIA_VIDEO)
                else arrayOf(READ_EXTERNAL_STORAGE)
            }
        }
        
        
        
        return if (SdkVersionUtils.isQ()) {
            arrayOf(READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(READ_EXTERNAL_STORAGE, WRITE_EXTERNAL_STORAGE)
        }
    }
}
