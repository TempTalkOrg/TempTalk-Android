package com.difft.android.selector.interfaces

import androidx.fragment.app.Fragment

interface OnPermissionDeniedListener {
    fun onDenied(
        fragment: Fragment,
        permissionArray: Array<String>,
        requestCode: Int,
        call: OnCallbackListener<Boolean>
    )
}
