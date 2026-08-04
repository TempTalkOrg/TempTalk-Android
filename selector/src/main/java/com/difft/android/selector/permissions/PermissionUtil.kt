package com.difft.android.selector.permissions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.difft.android.base.log.lumberjack.L
import com.difft.android.selector.utils.SpUtils

object PermissionUtil {
    /**
     * 默认未请求授权状态
     */
    const val DEFAULT = 0

    /**
     * 获取权限成功
     */
    const val SUCCESS = 1

    /**
     * 申请权限拒绝, 但是下次申请权限还会弹窗
     */
    const val REFUSE = 2

    /**
     * 申请权限拒绝，并且是永久，不会再弹窗
     */
    const val REFUSE_PERMANENT = 3

    @JvmStatic
    fun getPermissionStatus(activity: Activity, permission: String): Int {
        val flag = ActivityCompat.checkSelfPermission(activity, permission)
        val should = ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
        if (should) {
            return REFUSE
        }
        if (flag == PackageManager.PERMISSION_GRANTED) {
            return SUCCESS
        }
        if (!SpUtils.contains(activity, permission)) {
            return DEFAULT
        }
        return REFUSE_PERMANENT
    }

    @JvmStatic
    fun isAllGranted(context: Context, permissions: Array<String>, grantResults: IntArray): Boolean {
        var isAllGranted = true
        var skipPermissionReject = false
        val targetSdkVersion = context.applicationInfo.targetSdkVersion
        if (targetSdkVersion >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (ContextCompat.checkSelfPermission(context, PermissionConfig.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
                skipPermissionReject = true
            }
        }
        if (grantResults.isNotEmpty()) {
            for (i in grantResults.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    if (skipPermissionReject) {
                        if (permissions[i] == PermissionConfig.READ_MEDIA_IMAGES ||
                            permissions[i] == PermissionConfig.READ_MEDIA_VIDEO
                        ) {
                            break
                        }
                    }
                    isAllGranted = false
                    break
                }
            }
        } else {
            isAllGranted = false
        }
        return isAllGranted
    }

    /**
     * 跳转到系统设置页面
     */
    @JvmStatic
    fun goIntentSetting(fragment: Fragment, requestCode: Int) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", fragment.requireActivity().packageName, null)
            intent.data = uri
            fragment.startActivityForResult(intent, requestCode)
        } catch (e: Exception) {
            L.w(e) { "[PermissionUtil] goIntentSetting error:" }
        }
    }
}
