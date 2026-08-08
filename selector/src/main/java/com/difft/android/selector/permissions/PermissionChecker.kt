package com.difft.android.selector.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.selector.basic.PictureCommonFragment
import com.difft.android.selector.config.SelectMimeType
import com.difft.android.selector.utils.ActivityCompatHelper
import com.difft.android.selector.utils.SdkVersionUtils
import com.difft.android.selector.utils.SpUtils

class PermissionChecker private constructor() {

    fun requestPermissions(fragment: Fragment, permissionArray: Array<String>, callback: PermissionResultCallback?) {
        val groupList: MutableList<Array<String>> = ArrayList()
        groupList.add(permissionArray)
        requestPermissions(fragment, groupList, REQUEST_CODE, callback)
    }

    fun requestPermissions(fragment: Fragment, permissionGroupList: List<Array<String>>, callback: PermissionResultCallback?) {
        requestPermissions(fragment, permissionGroupList, REQUEST_CODE, callback)
    }

    private fun requestPermissions(fragment: Fragment, permissionGroupList: List<Array<String>>, requestCode: Int, permissionResultCallback: PermissionResultCallback?) {
        if (ActivityCompatHelper.isDestroy(fragment.activity)) {
            return
        }
        if (fragment is PictureCommonFragment) {
            val activity = fragment.activity
            val permissionList: MutableList<String> = ArrayList()
            for (permissionArray in permissionGroupList) {
                for (permission in permissionArray) {
                    if (ContextCompat.checkSelfPermission(activity!!, permission) != PackageManager.PERMISSION_GRANTED) {
                        permissionList.add(permission)
                    }
                }
            }
            if (permissionList.size > 0) {
                fragment.setPermissionsResultAction(permissionResultCallback)
                val requestArray = permissionList.toTypedArray()
                fragment.requestPermissions(requestArray, requestCode)
                ActivityCompat.requestPermissions(activity!!, requestArray, requestCode)
            } else {
                permissionResultCallback?.onGranted()
            }
        }
    }

    fun onRequestPermissionsResult(context: Context, permissions: Array<String>, grantResults: IntArray, action: PermissionResultCallback) {
        val activity = context as Activity
        for (permission in permissions) {
            val should = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            SpUtils.putBoolean(context, permission, should)
        }
        if (PermissionUtil.isAllGranted(context, permissions, grantResults)) {
            action.onGranted()
        } else {
            action.onDenied()
        }
    }

    companion object {

        private const val REQUEST_CODE = 10086

        @Volatile
        private var mInstance: PermissionChecker? = null

        @JvmStatic
        fun getInstance(): PermissionChecker {
            if (mInstance == null) {
                synchronized(PermissionChecker::class.java) {
                    if (mInstance == null) {
                        mInstance = PermissionChecker()
                    }
                }
            }
            return mInstance!!
        }

        /**
         * 检查是否有某个权限
         */
        @JvmStatic
        fun checkSelfPermission(ctx: Context, permissions: Array<String>?): Boolean {
            var isAllGranted = true
            if (permissions != null) {
                for (permission in permissions) {
                    if (ContextCompat.checkSelfPermission(ctx.applicationContext, permission)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        isAllGranted = false
                        break
                    }
                }
            }
            return isAllGranted
        }

        /**
         * 检查读写权限是否存在
         */
        @JvmStatic
        fun isCheckReadStorage(chooseMode: Int, context: Context): Boolean {
            return if (SdkVersionUtils.isTIRAMISU()) {
                // Android 14+ "Select photos" partial access: system holds READ_MEDIA_VISUAL_USER_SELECTED
                // while IMAGES/VIDEO stay denied. Treat it as readable so this upfront gate does not
                // re-launch the system re-selection dialog on every gallery open. Audio has no partial
                // visual access, so it is excluded. Mirrors PermissionUtil.isAllGranted's skip logic.
                if (chooseMode != SelectMimeType.ofAudio() && isPartialVisualAccessGranted(context)) {
                    return true
                }
                if (chooseMode == SelectMimeType.ofImage()) {
                    isCheckReadImages(context)
                } else if (chooseMode == SelectMimeType.ofVideo()) {
                    isCheckReadVideo(context)
                } else if (chooseMode == SelectMimeType.ofAudio()) {
                    isCheckReadAudio(context)
                } else {
                    isCheckReadImages(context) && isCheckReadVideo(context)
                }
            } else {
                isCheckReadExternalStorage(context)
            }
        }

        /**
         * Android 14+ partial visual access ("Select photos"): READ_MEDIA_VISUAL_USER_SELECTED granted.
         * Guarded by targetSdk to mirror PermissionUtil.isAllGranted.
         */
        private fun isPartialVisualAccessGranted(context: Context): Boolean {
            if (context.applicationInfo.targetSdkVersion < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                return false
            }
            return ContextCompat.checkSelfPermission(
                context,
                PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 检查读取图片权限是否存在
         */
        @RequiresApi(api = 33)
        @JvmStatic
        fun isCheckReadImages(context: Context): Boolean {
            return checkSelfPermission(context, arrayOf(PermissionConfig.READ_MEDIA_IMAGES))
        }

        /**
         * 检查读取视频权限是否存在
         */
        @RequiresApi(api = 33)
        @JvmStatic
        fun isCheckReadVideo(context: Context): Boolean {
            return checkSelfPermission(context, arrayOf(PermissionConfig.READ_MEDIA_VIDEO))
        }

        /**
         * 检查读取音频权限是否存在
         */
        @RequiresApi(api = 33)
        @JvmStatic
        fun isCheckReadAudio(context: Context): Boolean {
            return checkSelfPermission(context, arrayOf(PermissionConfig.READ_MEDIA_AUDIO))
        }

        /**
         * 检查读取权限是否存在
         */
        @JvmStatic
        fun isCheckReadExternalStorage(context: Context): Boolean {
            return checkSelfPermission(context, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }

        /**
         * 权限是否已申请
         */
        @JvmStatic
        fun isCheckSelfPermission(context: Context, permissions: Array<String>): Boolean {
            return checkSelfPermission(context, permissions)
        }
    }
}
