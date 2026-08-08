package com.difft.android.selector.basic

import com.difft.android.selector.config.PermissionEvent
import com.difft.android.selector.config.PictureConfig
import com.difft.android.selector.interfaces.OnCallbackListener
import com.difft.android.selector.interfaces.OnRequestPermissionListener
import com.difft.android.selector.permissions.PermissionChecker
import com.difft.android.selector.permissions.PermissionConfig
import com.difft.android.selector.permissions.PermissionResultCallback
import com.difft.android.selector.permissions.PermissionUtil

/**
 * Runtime-permission flow extracted from PictureCommonFragment (issue #1077).
 * Reaches base state through [host]; owns only the transient result callback.
 */
internal class FragmentPermissionDispatcher(private val host: PictureCommonFragment) {

    private var permissionResultCallback: PermissionResultCallback? = null

    fun setPermissionsResultAction(callback: PermissionResultCallback?) {
        permissionResultCallback = callback
    }

    fun onRequestPermissionsResult(permissions: Array<String>, grantResults: IntArray) {
        val callback = permissionResultCallback ?: return
        PermissionChecker.getInstance()
            .onRequestPermissionsResult(host.requireContext(), permissions, grantResults, callback)
        permissionResultCallback = null
    }

    fun handlePermissionDenied(permissionArray: Array<String>) {
        PermissionConfig.CURRENT_REQUEST_PERMISSION = permissionArray
        val listener = host.selectorConfig.onPermissionDeniedListener
        if (listener != null) {
            host.onPermissionExplainEvent(false, permissionArray)
            listener.onDenied(host, permissionArray, PictureConfig.REQUEST_GO_SETTING,
                object : OnCallbackListener<Boolean> {
                    override fun onCall(data: Boolean?) {
                        if (data == true) {
                            host.handlePermissionSettingResult(PermissionConfig.CURRENT_REQUEST_PERMISSION)
                        }
                    }
                })
        } else {
            PermissionUtil.goIntentSetting(host, PictureConfig.REQUEST_GO_SETTING)
        }
    }

    fun onApplyPermissionsEvent(event: Int, permissionArray: Array<String>) {
        host.selectorConfig.onPermissionsEventListener?.requestPermission(host, permissionArray,
            object : OnRequestPermissionListener {
                override fun onCall(permissionArray: Array<String>, isResult: Boolean) {
                    if (isResult) {
                        if (event == PermissionEvent.EVENT_VIDEO_CAMERA) {
                            host.camera.startCameraVideoCapture()
                        } else {
                            host.camera.startCameraImageCapture()
                        }
                    } else {
                        handlePermissionDenied(permissionArray)
                    }
                }
            })
    }

    fun onPermissionExplainEvent(isDisplayExplain: Boolean, permissionArray: Array<String>) {
        val descriptionListener = host.selectorConfig.onPermissionDescriptionListener ?: return
        if (PermissionChecker.isCheckSelfPermission(host.getAppContext(), permissionArray)) {
            descriptionListener.onDismiss(host)
        } else {
            if (isDisplayExplain) {
                val permissionStatus = PermissionUtil.getPermissionStatus(host.requireActivity(), permissionArray[0])
                if (permissionStatus != PermissionUtil.REFUSE_PERMANENT) {
                    descriptionListener.onPermissionDescription(host, permissionArray)
                }
            } else {
                descriptionListener.onDismiss(host)
            }
        }
    }
}
