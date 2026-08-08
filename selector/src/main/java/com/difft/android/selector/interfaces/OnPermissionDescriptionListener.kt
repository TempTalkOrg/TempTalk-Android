package com.difft.android.selector.interfaces

import androidx.fragment.app.Fragment

interface OnPermissionDescriptionListener {
    fun onPermissionDescription(fragment: Fragment, permissionArray: Array<String>)

    fun onDismiss(fragment: Fragment)
}
