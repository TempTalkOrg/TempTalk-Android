package com.difft.android.selector.interfaces

import androidx.fragment.app.Fragment

interface OnPermissionsInterceptListener {
    fun requestPermission(
        fragment: Fragment,
        permissionArray: Array<String>,
        call: OnRequestPermissionListener
    )

    fun hasPermissions(fragment: Fragment, permissionArray: Array<String>): Boolean
}
